import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.Signature;
import java.util.Base64;

/** Signs tokens with a throwaway key and checks that only genuine ones pass. */
public class GoogleAuthTest {

    private static final String CLIENT = "client-123.apps.googleusercontent.com";
    private static final long NOW = 2_000_000_000L;
    private static int failed = 0;

    private static String b64(String text) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(text.getBytes(StandardCharsets.UTF_8));
    }

    private static String sign(PrivateKey key, String header, String body) throws Exception {
        String head = b64(header) + "." + b64(body);
        Signature signer = Signature.getInstance("SHA256withRSA");
        signer.initSign(key);
        signer.update(head.getBytes(StandardCharsets.US_ASCII));
        return head + "." + Base64.getUrlEncoder().withoutPadding().encodeToString(signer.sign());
    }

    private static String body(String aud, String iss, long exp) {
        return "{\"aud\":\"" + aud + "\",\"iss\":\"" + iss + "\",\"exp\":\"" + exp
                + "\",\"email\":\"a@b.com\",\"email_verified\":true}";
    }

    private static void refused(String label, String token) {
        try {
            GoogleAuth.verify(token, CLIENT, NOW);
            System.out.println("NOT REFUSED: " + label);
            failed++;
        } catch (IllegalArgumentException e) {
            // correct
        } catch (Exception e) {
            System.out.println("WRONG ERROR for " + label + ": " + e);
            failed++;
        }
    }

    public static void main(String[] args) throws Exception {

        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        KeyPair good = generator.generateKeyPair();
        KeyPair evil = generator.generateKeyPair();

        GoogleAuth.addKey("k1", good.getPublic());

        String header = "{\"alg\":\"RS256\",\"kid\":\"k1\"}";
        String valid = sign(good.getPrivate(), header, body(CLIENT, "https://accounts.google.com", NOW + 600));

        var claims = GoogleAuth.verify(valid, CLIENT, NOW);

        if (!"a@b.com".equals(claims.get("email")) || !"true".equals(claims.get("email_verified"))) {
            System.out.println("Claims were not read: " + claims);
            failed++;
        }

        refused("expired", sign(good.getPrivate(), header, body(CLIENT, "accounts.google.com", NOW - 1)));
        refused("other application", sign(good.getPrivate(), header, body("someone-else", "accounts.google.com", NOW + 600)));
        refused("other issuer", sign(good.getPrivate(), header, body(CLIENT, "https://evil.example", NOW + 600)));
        refused("signed by another key", sign(evil.getPrivate(), header, body(CLIENT, "accounts.google.com", NOW + 600)));
        refused("not a token", "abc");

        // Body swapped after signing.
        String[] parts = valid.split("\\.");
        refused("tampered body", parts[0] + "." + b64(body(CLIENT, "accounts.google.com", NOW + 99999)) + "." + parts[2]);

        // The classic attack: claim that no signature is needed.
        refused("alg none", b64("{\"alg\":\"none\",\"kid\":\"k1\"}") + "." + b64(body(CLIENT, "accounts.google.com", NOW + 600)) + ".");
        refused("alg HS256", b64("{\"alg\":\"HS256\",\"kid\":\"k1\"}") + "." + b64(body(CLIENT, "accounts.google.com", NOW + 600)) + ".abc");

        if (failed > 0) {
            System.exit(1);
        }

        System.out.println("Google token checks passed");
    }
}
