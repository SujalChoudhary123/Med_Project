import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.math.BigInteger;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.RSAPublicKeySpec;
import java.time.Instant;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Checks Google sign-in tokens on this server.
 *
 * The token is signed by Google, so it is enough to hold Google's public keys.
 * They are fetched in the background and kept in memory, which means a sign-in
 * needs no call to Google and a slow connection cannot make it fail.
 */
public final class GoogleAuth {

    private GoogleAuth() {}

    private static final String KEYS_HOST = "www.googleapis.com";
    private static final String KEYS_PATH = "/oauth2/v3/certs";
    private static final int CONNECT_MS = 4000;
    private static final int READ_MS = 8000;

    private static final Map<String, PublicKey> KEYS = new ConcurrentHashMap<>();

    /** Fetches the keys now and then every hour. Failures only log: old keys stay valid for days. */
    public static void start() {

        if (Config.get("GOOGLE_CLIENT_ID", "").isEmpty()) {
            return;
        }

        ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor(task -> {
            Thread thread = new Thread(task, "google-keys");
            thread.setDaemon(true);
            return thread;
        });

        timer.scheduleWithFixedDelay(() -> {
            try {
                boolean first = KEYS.isEmpty();
                refresh();
                if (first) System.out.println("Google sign-in keys loaded: " + KEYS.size());
            } catch (Exception e) {
                System.out.println("Google keys not refreshed: " + e);
            }
        }, 0, 1, TimeUnit.HOURS);
    }

    @SuppressWarnings("unchecked")
    static void refresh() throws Exception {

        Object parsed = Json.parse(httpsGet(KEYS_HOST, KEYS_PATH));
        Object list = Json.asObject(parsed).get("keys");

        if (!(list instanceof List)) {
            throw new IOException("Google keys were not in the expected form");
        }

        Map<String, PublicKey> fresh = new HashMap<>();
        KeyFactory factory = KeyFactory.getInstance("RSA");

        for (Object item : (List<Object>) list) {
            Map<String, Object> key = Json.asObject(item);
            if (!"RSA".equals(Json.str(key, "kty"))) continue;
            BigInteger n = new BigInteger(1, Base64.getUrlDecoder().decode(Json.str(key, "n")));
            BigInteger e = new BigInteger(1, Base64.getUrlDecoder().decode(Json.str(key, "e")));
            fresh.put(Json.str(key, "kid"), factory.generatePublic(new RSAPublicKeySpec(n, e)));
        }

        if (fresh.isEmpty()) {
            throw new IOException("Google returned no keys");
        }

        KEYS.putAll(fresh);
    }

    /** Lets the automatic tests sign tokens with their own key. */
    static void addKey(String keyId, PublicKey key) {
        KEYS.put(keyId, key);
    }

    /**
     * Returns the token's claims, or throws IllegalArgumentException for a token that must
     * be refused and IOException when Google's keys could not be fetched at all.
     */
    public static Map<String, String> verify(String token, String clientId, long nowSeconds) throws Exception {

        String[] parts = token.split("\\.");

        if (parts.length != 3 || token.length() > 8192) {
            throw new IllegalArgumentException("Google sign-in could not be verified.");
        }

        Map<String, Object> header;
        Map<String, Object> body;

        try {
            header = Json.asObject(Json.parse(decode(parts[0])));
            body = Json.asObject(Json.parse(decode(parts[1])));
        } catch (Json.JsonException | IllegalArgumentException e) {
            throw new IllegalArgumentException("Google sign-in could not be verified.");
        }

        // Only Google's own algorithm. "none" and friends are refused here.
        if (!"RS256".equals(Json.str(header, "alg"))) {
            throw new IllegalArgumentException("Google sign-in could not be verified.");
        }

        String keyId = Json.str(header, "kid");
        PublicKey key = KEYS.get(keyId);

        if (key == null) {
            // Google has rotated its keys, or the background fetch has not finished yet.
            refresh();
            key = KEYS.get(keyId);
        }

        if (key == null) {
            throw new IllegalArgumentException("Google sign-in could not be verified.");
        }

        Signature check = Signature.getInstance("SHA256withRSA");
        check.initVerify(key);
        check.update((parts[0] + "." + parts[1]).getBytes(StandardCharsets.US_ASCII));

        boolean genuine;

        try {
            genuine = check.verify(Base64.getUrlDecoder().decode(parts[2]));
        } catch (Exception e) {
            genuine = false;
        }

        if (!genuine) {
            throw new IllegalArgumentException("Google sign-in could not be verified.");
        }

        Map<String, String> claims = new HashMap<>();

        for (Map.Entry<String, Object> entry : body.entrySet()) {
            claims.put(entry.getKey(), Json.str(body, entry.getKey()));
        }

        if (!clientId.equals(claims.getOrDefault("aud", ""))) {
            System.out.println("Google token is for client " + claims.get("aud") + ", this server expects " + clientId);
            throw new IllegalArgumentException("This Google sign-in is for a different application.");
        }

        String issuer = claims.getOrDefault("iss", "");

        if (!"accounts.google.com".equals(issuer) && !"https://accounts.google.com".equals(issuer)) {
            throw new IllegalArgumentException("Google sign-in could not be verified.");
        }

        long expires;

        try {
            expires = Long.parseLong(claims.getOrDefault("exp", "0"));
        } catch (NumberFormatException e) {
            expires = 0;
        }

        if (expires <= nowSeconds) {
            throw new IllegalArgumentException("The Google sign-in expired. Please try again.");
        }

        return claims;
    }

    public static Map<String, String> verify(String token, String clientId) throws Exception {
        return verify(token, clientId, Instant.now().getEpochSecond());
    }

    private static String decode(String part) {
        return new String(Base64.getUrlDecoder().decode(part), StandardCharsets.UTF_8);
    }

    // ---------------------------------------------------------
    // Network helpers
    // ---------------------------------------------------------

    /**
     * Connects to the first address of the host that answers. A host has several addresses
     * and one of them can be unreachable from a given connection; a plain Java connection
     * tries only the first and then times out.
     */
    public static SSLSocket openTls(String host, int port, int connectMs, int readMs) throws IOException {

        IOException last = null;

        for (InetAddress address : InetAddress.getAllByName(host)) {

            Socket plain = new Socket();

            try {
                plain.connect(new InetSocketAddress(address, port), connectMs);
                plain.setSoTimeout(readMs);

                SSLSocket tls = (SSLSocket) ((SSLSocketFactory) SSLSocketFactory.getDefault())
                        .createSocket(plain, host, port, true);

                // Without this the certificate's host name would not be checked.
                SSLParameters parameters = tls.getSSLParameters();
                parameters.setEndpointIdentificationAlgorithm("HTTPS");
                tls.setSSLParameters(parameters);
                tls.startHandshake();
                return tls;

            } catch (IOException e) {
                last = e;
                try {
                    plain.close();
                } catch (IOException ignored) {
                    // nothing more to do
                }
            }
        }

        throw last != null ? last : new IOException("No address for " + host);
    }

    private static String httpsGet(String host, String path) throws IOException {

        try (SSLSocket socket = openTls(host, 443, CONNECT_MS, READ_MS)) {

            // HTTP/1.0: the reply is a plain body that ends when the connection closes.
            OutputStream out = socket.getOutputStream();
            out.write(("GET " + path + " HTTP/1.0\r\nHost: " + host + "\r\nUser-Agent: MedTrack\r\n\r\n")
                    .getBytes(StandardCharsets.US_ASCII));
            out.flush();

            InputStream in = socket.getInputStream();
            ByteArrayOutputStream all = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int read;

            while ((read = in.read(buffer)) != -1) {
                all.write(buffer, 0, read);
                if (all.size() > 1024 * 1024) {
                    throw new IOException("Reply from " + host + " is too large");
                }
            }

            String reply = all.toString(StandardCharsets.UTF_8);
            int split = reply.indexOf("\r\n\r\n");

            if (split < 0 || !reply.startsWith("HTTP/1.") || !reply.substring(9, 12).equals("200")) {
                throw new IOException("Unexpected reply from " + host);
            }

            return reply.substring(split + 4);
        }
    }
}
