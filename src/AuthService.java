import javax.net.ssl.SSLSocket;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/** Email OTP and Google ID-token validation for the MedTrack API. */
public final class AuthService {
    private static final long OTP_TTL_SECONDS = 300;
    private static final long RESEND_WAIT_SECONDS = 60;
    private static final int MAX_ATTEMPTS = 5;
    private static final Map<String, Pending> PENDING = new ConcurrentHashMap<>();
    private static final SecureRandom RANDOM = new SecureRandom();
    private AuthService() {}

    /** Bump this when the Terms change; each account stores the version it agreed to. */
    public static final String TERMS_VERSION = "2026-11";

    /** null when the password is acceptable, otherwise the reason. */
    public static String passwordProblem(String password) {
        if (password == null || password.length() < 8) return "Password must be at least 8 characters";
        if (password.length() > 128) return "Password must be 128 characters or fewer";
        if (!password.matches(".*[A-Za-z].*") || !password.matches(".*[0-9].*")) return "Password must contain at least one letter and one number";
        return null;
    }

    public static final class Result {
        public final int status; public final String body;
        public Result(int status, String body) { this.status=status; this.body=body; }
    }
    private static final class Pending {
        String type, username, email, password, otp, terms="", newUsername; long expiresAt, lastSentAt; int attempts;
        boolean fake; // decoy challenge for an account that does not exist
    }

    public static Result beginSignup(Map<String,String> d) throws Exception {
        String username = val(d,"username").trim();
        String email = val(d,"email").trim().toLowerCase(Locale.ROOT);
        String password = val(d,"password");
        if (!username.matches("[A-Za-z0-9_.-]{3,30}")) return err(400,"Username must be 3-30 characters and use letters, numbers, dots, underscores or hyphens");
        if (!validEmail(email)) return err(400,"Please enter a valid email address");
        String weak=passwordProblem(password); if (weak!=null) return err(400,weak);
        if (!"true".equalsIgnoreCase(val(d,"acceptTerms"))) return err(400,"Please accept the Terms of Service and Privacy Policy to create an account");
        if (DatabaseHelper.usernameExists(username) || DatabaseHelper.getUsernameByEmail(email)!=null) return err(409,"That username or email is already registered");
        Pending p = new Pending(); p.type="signup"; p.username=username; p.email=email; p.password=password; p.terms=TERMS_VERSION;
        return issue(p, null);
    }

    public static Result beginLogin(Map<String, String> d) {
    String identity = val(d, "username").trim();
    String password = val(d, "password");

    if (identity.isEmpty() || password.isEmpty()) {
        return err(400, "Username/email and password are required");
    }

    String username = DatabaseHelper.getUsernameForLogin(identity, password);

    if (username == null) {
        return err(401, "Invalid username/email or password");
    }

    String[] profile = DatabaseHelper.getUserProfile(username);
    String email = (profile != null && profile.length > 1 && profile[1] != null)
            ? profile[1].trim().toLowerCase(Locale.ROOT)
            : "";

    return loginOk("Login successful", username, email);
}
    
// OTP login and forgot-password answer the same way whether or not the
// account exists, so they cannot be used to find out who is registered.
public static Result beginOtpLogin(Map<String, String> d) throws Exception {
    String identity = val(d, "username").trim();

    if (identity.isEmpty()) {
        return err(400, "Username or email is required");
    }

    String username = DatabaseHelper.getUsernameByEmail(identity);
    String email = null;

    if (username != null) {
        email = identity.toLowerCase(Locale.ROOT);
    } else {
        String[] profile = DatabaseHelper.getUserProfile(identity);
        if (profile != null) {
            username = profile[0];
            email = profile[1] == null ? "" : profile[1].trim().toLowerCase(Locale.ROOT);
        }
    }

    String shownMask = identity.contains("@")
            ? maskEmail(identity.toLowerCase(Locale.ROOT))
            : "your registered email";

    if (username == null || !validEmail(email)) {
        return fakeChallenge("login", shownMask);
    }

    Pending p = new Pending();
    p.type = "login";
    p.username = username;
    p.email = email;

    return issueQuietly(p, shownMask);
}

public static Result beginForgotPassword(Map<String, String> d) throws Exception {
    String email = val(d, "email").trim().toLowerCase(Locale.ROOT);

    if (!validEmail(email)) {
        return err(400, "Please enter a valid email address");
    }

    String username = DatabaseHelper.getUsernameByEmail(email);

    if (username == null) {
        return fakeChallenge("forgot", maskEmail(email));
    }

    Pending p = new Pending();
    p.type = "forgot";
    p.username = username;
    p.email = email;

    return issueQuietly(p, maskEmail(email));
}

    /**
     * A new email address must be proved by a code sent to it. Otherwise someone could put a
     * stranger's address on their account, and the stranger's Google sign-in would land there.
     */
    public static Result beginEmailChange(String username, String newUsername, String email) throws Exception {
        String holder=DatabaseHelper.getUsernameByEmail(email);
        if (holder!=null && !holder.equals(username)) return err(409,"That username or email is already in use");
        if (!newUsername.equalsIgnoreCase(username) && DatabaseHelper.usernameExists(newUsername)) return err(409,"That username or email is already in use");
        Pending p=new Pending(); p.type="email"; p.username=username; p.newUsername=newUsername; p.email=email;
        return issue(p,null);
    }

    /** Codes nobody used would otherwise stay in memory for ever. */
    public static void purgeExpired() {
        long now=Instant.now().getEpochSecond();
        PENDING.values().removeIf(p -> p.expiresAt<now);
    }

    public static Result beginGoogle(Map<String,String> d) throws Exception {
        String idToken=val(d,"credential").trim();
        if (idToken.isEmpty()) idToken=val(d,"idToken").trim();
        if (idToken.isEmpty()) return err(400,"Google credential is required");
        Map<String,String> info=verifyGoogleToken(idToken);
        String email=val(info,"email").trim().toLowerCase(Locale.ROOT);
        if (!"true".equalsIgnoreCase(val(info,"email_verified")) || !validEmail(email)) return err(401,"Google account email is not verified");
        String username=DatabaseHelper.getUsernameByEmail(email);
        boolean newGoogleUser=username==null;
        if (newGoogleUser) {
            String base=email.substring(0,email.indexOf('@')).replaceAll("[^A-Za-z0-9_.-]","_");
            if (base.length()<3) base="user_"+base;
            if (base.length()>30) base=base.substring(0,30);
            username=base; int n=1;
            while (DatabaseHelper.usernameExists(username)) {
                String suffix="_"+(n++); username=base.substring(0,Math.min(base.length(),30-suffix.length()))+suffix;
            }
        }
        if (!newGoogleUser) {
    return loginOk("Google login successful", username, email);
}

Pending p = new Pending();
p.type = "google";
p.username = username;
p.email = email;
p.password = UUID.randomUUID().toString() + "Aa9!";
p.terms = TERMS_VERSION; // the Google button says that continuing accepts the Terms

return issue(p, null);
    }

    
public static Result verifyOtp(Map<String, String> d) {
    String id = val(d, "challengeId").trim();
    String code = val(d, "otp").trim();

    Pending p = PENDING.get(id);

    if (p == null) {
        return err(400, "OTP session not found. Please start again.");
    }

    if (Instant.now().getEpochSecond() > p.expiresAt) {
        PENDING.remove(id);
        return err(400, "OTP expired. Please request a new one.");
    }

    if (++p.attempts > MAX_ATTEMPTS) {
        PENDING.remove(id);
        return err(429, "Too many incorrect OTP attempts. Start again.");
    }

    if (p.fake || !constantTimeEquals(p.otp, code)) {
        return err(401, "Incorrect OTP. Please try again.");
    }

    PENDING.remove(id);

    if ("email".equals(p.type)) {
        int changed = DatabaseHelper.updateUserProfile(p.username, p.newUsername, p.email);
        if (changed == -1) return err(409, "That username or email is already in use");
        if (changed == 0) return err(404, "Account not found");
        SessionManager.revokeAllFor(p.newUsername);
        return new Result(200, "{\"success\":true,\"emailChanged\":true,\"message\":\"Email address changed\"}");
    }

    // Forgot-password OTP: create a temporary reset token.
    if ("forgot".equals(p.type)) {
        String resetToken = UUID.randomUUID().toString();

        Pending reset = new Pending();
        reset.type = "reset";
        reset.username = p.username;
        reset.email = p.email;
        reset.expiresAt = Instant.now().getEpochSecond() + OTP_TTL_SECONDS;

        PENDING.put(resetToken, reset);

        return new Result(
            200,
            "{\"success\":true,\"message\":\"OTP verified\","
            + "\"resetToken\":" + json(resetToken)
            + ",\"requiresPasswordReset\":true}"
        );
    }

    // Create a new account only after signup OTP verification.
    if ("signup".equals(p.type)
            || ("google".equals(p.type)
            && DatabaseHelper.getUsernameByEmail(p.email) == null)) {

        if (!DatabaseHelper.registerUser(
                p.username, p.email, p.password, p.terms)) {
            return err(409,
                "Could not create account. Username/email may already exist.");
        }
    }

    return loginOk("OTP verified", p.username, p.email);
}


    public static Result resendOtp(Map<String,String> d) throws Exception {
        String id=val(d,"challengeId").trim(); Pending old=PENDING.get(id);
        if (old==null) return err(400,"OTP session not found. Start again.");
        long now=Instant.now().getEpochSecond();
        if (now-old.lastSentAt<RESEND_WAIT_SECONDS) return err(429,"Please wait before requesting another OTP.");
        Pending replacement=new Pending(); replacement.type=old.type; replacement.username=old.username; replacement.email=old.email; replacement.password=old.password; replacement.terms=old.terms; replacement.newUsername=old.newUsername; replacement.fake=old.fake;
        if (old.fake) return fakeChallenge(old.type,"your registered email",id);
        return issue(replacement,id);
    }

    // Sends the code in the background so the response time is the same
    // whether or not the account exists.
    private static final java.util.concurrent.ExecutorService MAILER =
        java.util.concurrent.Executors.newFixedThreadPool(2, r -> { Thread t=new Thread(r,"otp-mailer"); t.setDaemon(true); return t; });

    private static Result issueQuietly(Pending p,String shownMask) {
        p.otp=String.format(Locale.ROOT,"%06d",RANDOM.nextInt(1_000_000));
        long now=Instant.now().getEpochSecond(); p.expiresAt=now+OTP_TTL_SECONDS; p.lastSentAt=now;
        final String to=p.email, code=p.otp;
        MAILER.submit(() -> {
            try { sendEmail(to,"Your MedTrack verification code","Your MedTrack OTP is: "+code+"\n\nIt expires in 5 minutes. Do not share this code with anyone."); }
            catch (Exception e) { System.out.println("OTP email failed: "+e.getMessage()); }
        });
        String id=UUID.randomUUID().toString(); PENDING.put(id,p);
        return new Result(200,"{\"success\":true,\"otpRequired\":true,\"challengeId\":"+json(id)+",\"emailMasked\":"+json(shownMask)+",\"message\":\"Verification code sent\"}");
    }

    private static Result fakeChallenge(String type,String shownMask) { return fakeChallenge(type,shownMask,null); }

    private static Result fakeChallenge(String type,String shownMask,String replaceId) {
        Pending p=new Pending(); p.type=type; p.fake=true;
        p.otp=String.format(Locale.ROOT,"%06d",RANDOM.nextInt(1_000_000));
        long now=Instant.now().getEpochSecond(); p.expiresAt=now+OTP_TTL_SECONDS; p.lastSentAt=now;
        String id=UUID.randomUUID().toString(); PENDING.put(id,p); if(replaceId!=null) PENDING.remove(replaceId);
        return new Result(200,"{\"success\":true,\"otpRequired\":true,\"challengeId\":"+json(id)+",\"emailMasked\":"+json(shownMask)+",\"message\":\"Verification code sent\"}");
    }

    private static Result issue(Pending p,String replaceId) throws Exception {
        p.otp=String.format(Locale.ROOT,"%06d",RANDOM.nextInt(1_000_000));
        long now=Instant.now().getEpochSecond(); p.expiresAt=now+OTP_TTL_SECONDS; p.lastSentAt=now;
        sendEmail(p.email,"Your MedTrack verification code", "Your MedTrack OTP is: "+p.otp+"\n\nIt expires in 5 minutes. Do not share this code with anyone.");
        String id=UUID.randomUUID().toString(); PENDING.put(id,p); if(replaceId!=null) PENDING.remove(replaceId);
        return new Result(200,"{\"success\":true,\"otpRequired\":true,\"challengeId\":"+json(id)+",\"emailMasked\":"+json(maskEmail(p.email))+",\"message\":\"Verification code sent\"}");
    }

    private static Map<String,String> verifyGoogleToken(String token) throws Exception {
        String expected=System.getenv("GOOGLE_CLIENT_ID");
        if(expected==null||expected.trim().isEmpty()) throw new IllegalStateException("GOOGLE_CLIENT_ID environment variable is not configured");
        return GoogleAuth.verify(token,expected.trim());
    }

    // Minimal SMTP over implicit TLS; uses a Google App Password, never a Google OAuth client secret.
    private static void sendEmail(String to,String subject,String body) throws Exception {
        String user=System.getenv("SMTP_EMAIL"), pass=System.getenv("SMTP_APP_PASSWORD");
        if(user==null||user.trim().isEmpty()||pass==null||pass.trim().isEmpty()) throw new IllegalStateException("Set SMTP_EMAIL and SMTP_APP_PASSWORD environment variables");
        try(SSLSocket socket=GoogleAuth.openTls("smtp.gmail.com",465,5000,10000)) {
            BufferedReader in=new BufferedReader(new InputStreamReader(socket.getInputStream(),StandardCharsets.UTF_8));
            BufferedWriter out=new BufferedWriter(new OutputStreamWriter(socket.getOutputStream(),StandardCharsets.UTF_8));
            expect(in,220); command(out,in,"EHLO localhost",250); command(out,in,"AUTH LOGIN",334);
            command(out,in,Base64.getEncoder().encodeToString(user.getBytes(StandardCharsets.UTF_8)),334);
            command(out,in,Base64.getEncoder().encodeToString(pass.replace(" ","").getBytes(StandardCharsets.UTF_8)),235);
            command(out,in,"MAIL FROM:<"+user+">",250); command(out,in,"RCPT TO:<"+to+">",250,251); command(out,in,"DATA",354);
            out.write("From: MedTrack <"+user+">\r\nTo: <"+to+">\r\nSubject: "+subject+"\r\nMIME-Version: 1.0\r\nContent-Type: text/plain; charset=UTF-8\r\n\r\n"+body.replace("\r","").replace("\n","\r\n")+"\r\n.\r\n"); out.flush(); expect(in,250); command(out,in,"QUIT",221);
        }
    }
    private static void command(BufferedWriter out,BufferedReader in,String s,int... expected)throws IOException { out.write(s+"\r\n");out.flush();expect(in,expected); }
    private static void expect(BufferedReader in,int... expected)throws IOException { String line=in.readLine(); if(line==null)throw new IOException("SMTP server closed connection"); String last=line; while(last.length()>3&&last.charAt(3)=='-'){last=in.readLine();if(last==null)throw new IOException("Incomplete SMTP response");} int code=Integer.parseInt(last.substring(0,3)); for(int e:expected)if(code==e)return; throw new IOException("SMTP error: "+last); }
    private static Map<String,String> parseFlatJson(String json) { Map<String,String> m=new HashMap<>(); java.util.regex.Matcher x=java.util.regex.Pattern.compile("\\\"([^\\\"]+)\\\"\\s*:\\s*(?:\\\"((?:\\\\.|[^\\\"])*)\\\"|([^,}\\s]+))").matcher(json); while(x.find())m.put(x.group(1),x.group(2)!=null?x.group(2):x.group(3)); return m; }
    private static boolean validEmail(String s){return s!=null&&s.matches("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$");}
    private static String val(Map<String,String> m,String k){String v=m.get(k);return v==null?"":v;}
    private static Result loginOk(String message, String username, String email) {
        DatabaseHelper.UserInfo info = DatabaseHelper.getUserInfo(username);
        String role = info == null ? "cashier" : info.role;
        return new Result(200,
            "{\"success\":true,\"message\":" + json(message)
            + ",\"username\":" + json(username)
            + ",\"email\":" + json(email)
            + ",\"role\":" + json(role)
            + ",\"admin\":" + Plans.isAdminEmail(email)
            + ",\"token\":" + json(SessionManager.create(username)) + "}");
    }
    private static Result err(int status,String message){return new Result(status,"{\"success\":false,\"error\":"+json(message)+"}");}
    private static String json(String s){if(s==null)return "null";return "\""+s.replace("\\","\\\\").replace("\"","\\\"").replace("\r","\\r").replace("\n","\\n")+"\"";}
    private static String maskEmail(String e){int at=e.indexOf('@'); if(at<2)return "***"+e.substring(Math.max(0,at)); return e.substring(0,2)+"***"+e.substring(at);}
    private static boolean constantTimeEquals(String a,String b){if(a==null||b==null)return false;return java.security.MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8),b.getBytes(StandardCharsets.UTF_8));}

public static Result resetPassword(Map<String, String> d) {
    String token = val(d, "resetToken").trim();
    String newPassword = val(d, "newPassword");

    if (token.isEmpty()) {
        return err(400, "Password reset token is required");
    }

    String weak = passwordProblem(newPassword);

    if (weak != null) {
        return err(400, weak);
    }

    Pending p = PENDING.get(token);

    if (p == null || !"reset".equals(p.type)) {
        return err(400,
            "Invalid password reset token. Verify your email again.");
    }

    if (Instant.now().getEpochSecond() > p.expiresAt) {
        PENDING.remove(token);
        return err(400,
            "Password reset token expired. Please start again.");
    }

    if (!DatabaseHelper.updatePasswordByEmail(
            p.email, newPassword)) {
        return err(400,
            "Password could not be updated. Please try again.");
    }

    PENDING.remove(token);
    SessionManager.revokeAllFor(p.username);

    return new Result(
        200,
        "{\"success\":true,\"message\":\"Password updated successfully\"}"
    );
}

}
