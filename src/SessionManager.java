import com.sun.net.httpserver.HttpExchange;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayDeque;
import java.util.Base64;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Login sessions (bearer tokens) kept in the database, plus a simple
 * sliding-window rate limiter.
 *
 * The browser holds the raw token. The database only holds its SHA-256, so
 * sessions survive a server restart and a copied database cannot be used to
 * impersonate anyone.
 */
public final class SessionManager {

    private static final long SESSION_TTL_MS = 12L * 60 * 60 * 1000; // 12 hours
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Map<String, Deque<Long>> HITS = new ConcurrentHashMap<>();

    private SessionManager() {}

    /** Who is calling: username, the shop their data belongs to, and their role. */
    public static final class Principal {
        public final String username;
        public final long shopId;
        public final String role;

        Principal(String username, long shopId, String role) {
            this.username = username;
            this.shopId = shopId;
            this.role = role;
        }
    }

    // ---------------- sessions ----------------

    private static String hash(String token) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(token.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (byte b : digest) hex.append(String.format("%02x", b));
            return hex.toString();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** Starts a session for an existing user and returns the token to give the browser. */
    public static String create(String username) {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);

        DatabaseHelper.createSession(hash(token), username, System.currentTimeMillis() + SESSION_TTL_MS);
        return token;
    }

    public static String tokenFrom(HttpExchange exchange) {
        String header = exchange.getRequestHeaders().getFirst("Authorization");
        if (header == null || !header.startsWith("Bearer ")) return null;
        String token = header.substring(7).trim();
        return token.isEmpty() ? null : token;
    }

    /** Returns the caller (with shop and role) for this request, or null if not signed in. */
    public static Principal principalFor(HttpExchange exchange) {
        String token = tokenFrom(exchange);
        if (token == null) return null;

        DatabaseHelper.UserInfo info = DatabaseHelper.findSession(hash(token));
        return info == null ? null : new Principal(info.username, info.shopId, info.role);
    }

    public static void revoke(String token) {
        if (token != null) DatabaseHelper.deleteSession(hash(token));
    }

    public static void revokeAllFor(String username) {
        if (username != null) DatabaseHelper.deleteSessionsForUser(username);
    }

    // ---------------- rate limiting ----------------

    /** Returns false when more than maxHits calls for this key happened within windowMs. */
    public static boolean allow(String key, int maxHits, long windowMs) {
        long now = System.currentTimeMillis();
        Deque<Long> hits = HITS.computeIfAbsent(key, k -> new ArrayDeque<>());
        synchronized (hits) {
            while (!hits.isEmpty() && now - hits.peekFirst() > windowMs) hits.pollFirst();
            if (hits.size() >= maxHits) return false;
            hits.addLast(now);
            return true;
        }
    }

    /** Frees memory used by rate-limit entries that have gone quiet. */
    public static void cleanupRateLimits(long olderThanMs) {
        long now = System.currentTimeMillis();
        HITS.entrySet().removeIf(entry -> {
            synchronized (entry.getValue()) {
                Long last = entry.getValue().peekLast();
                return last == null || now - last > olderThanMs;
            }
        });
    }
}
