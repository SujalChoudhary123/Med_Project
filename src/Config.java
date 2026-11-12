import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

/** Server settings, all read from environment variables with safe local defaults. */
public final class Config {

    private Config() {}

    public static String get(String key, String fallback) {
        String value = System.getenv(key);
        return value == null || value.trim().isEmpty() ? fallback : value.trim();
    }

    public static int getInt(String key, int fallback) {
        try {
            return Integer.parseInt(get(key, String.valueOf(fallback)));
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    public static boolean getBool(String key, boolean fallback) {
        return Boolean.parseBoolean(get(key, String.valueOf(fallback)));
    }

    /** TCP port the API listens on. */
    public static final int PORT = getInt("PORT", 8080);

    /** Worker threads handling requests. */
    public static final int THREADS = Math.max(2, getInt("THREADS", 16));

    /**
     * Browser origins allowed to call the API from another site (CORS).
     * Not needed when the app and API share one domain. Comma separated.
     */
    public static final Set<String> ALLOWED_ORIGINS = Arrays.stream(
            get("ALLOWED_ORIGINS",
                "http://localhost:5173,http://127.0.0.1:5173,http://localhost:5174,http://127.0.0.1:5174")
                .split(","))
            .map(String::trim)
            .filter(origin -> !origin.isEmpty())
            .collect(Collectors.toSet());

    /**
     * Set to true ONLY when the API sits behind your own reverse proxy (Caddy,
     * Nginx). Then the client IP is read from X-Forwarded-For for rate limiting.
     * Never enable it when the API is reachable directly: the header can be forged.
     */
    public static final boolean TRUST_PROXY = getBool("TRUST_PROXY", false);

    /** Largest request body accepted, in bytes. */
    public static final int MAX_BODY_BYTES = getInt("MAX_BODY_BYTES", 2 * 1024 * 1024);

    /** Requests one address may make per minute before it gets "too many requests". */
    public static final int RATE_PER_MINUTE = Math.max(60, getInt("RATE_PER_MINUTE", 3000));
}
