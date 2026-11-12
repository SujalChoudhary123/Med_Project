import java.io.File;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.Comparator;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Scheduled database backups and housekeeping.
 *
 * Backups use SQLite's VACUUM INTO, which writes a consistent copy while the
 * server keeps running (copying the .db file by hand can catch it half-written).
 *
 * Settings (environment variables):
 *   BACKUP_ENABLED          true/false (default true)
 *   BACKUP_DIR              folder for backups (default: "backups" next to the database)
 *   BACKUP_INTERVAL_HOURS   hours between backups (default 24)
 *   BACKUP_KEEP             how many backups to keep (default 14)
 */
public final class Backup {

    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    private Backup() {}

    public static void start() {

        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(task -> {
            Thread thread = new Thread(task, "housekeeping");
            thread.setDaemon(true);
            return thread;
        });

        // Hourly cleanup of expired sessions and idle rate-limit counters.
        scheduler.scheduleWithFixedDelay(() -> {
            try {
                DatabaseHelper.purgeSessions();
                SessionManager.cleanupRateLimits(2 * 60 * 60 * 1000L);
                AuthService.purgeExpired();
            } catch (Exception e) {
                System.out.println("Housekeeping error: " + e.getMessage());
            }
        }, 1, 60, TimeUnit.MINUTES);

        if (!Config.getBool("BACKUP_ENABLED", true)) {
            System.out.println("Automatic backups are disabled (BACKUP_ENABLED=false).");
            return;
        }

        long intervalMinutes = Math.max(1, Config.getInt("BACKUP_INTERVAL_HOURS", 24)) * 60L;
        long firstRunSeconds = Math.max(1, Config.getInt("BACKUP_FIRST_RUN_SECONDS", 120));

        scheduler.scheduleAtFixedRate(Backup::runOnce, firstRunSeconds, intervalMinutes * 60, TimeUnit.SECONDS);

        System.out.println("Backups: every " + (intervalMinutes / 60) + "h into " + backupDirectory());
    }

    private static Path backupDirectory() {

        String configured = Config.get("BACKUP_DIR", "");

        if (!configured.isEmpty()) {
            return Paths.get(configured).toAbsolutePath().normalize();
        }

        return Paths.get(DatabaseHelper.getDatabasePath()).toAbsolutePath().getParent().resolve("backups");
    }

    /** Creates one backup now and prunes old ones. Returns the new file, or null on failure. */
    public static File runOnce() {

        try {

            File directory = backupDirectory().toFile();

            if (!directory.exists() && !directory.mkdirs()) {
                System.out.println("Backup failed: cannot create " + directory);
                return null;
            }

            File target = new File(directory, "medtrack-" + LocalDateTime.now().format(STAMP) + ".db");

            try (Connection conn = DatabaseHelper.connect();
                 Statement st = conn.createStatement()) {

                st.execute("VACUUM INTO '" + target.getAbsolutePath().replace("'", "''") + "'");
            }

            System.out.println("Backup saved: " + target + " (" + target.length() / 1024 + " KB)");

            prune(directory, Math.max(1, Config.getInt("BACKUP_KEEP", 14)));

            return target;

        } catch (Exception e) {
            System.out.println("Backup failed: " + e.getMessage());
            return null;
        }
    }

    private static void prune(File directory, int keep) {

        File[] backups = directory.listFiles(
                (dir, name) -> name.startsWith("medtrack-") && name.endsWith(".db"));

        if (backups == null || backups.length <= keep) {
            return;
        }

        Arrays.sort(backups, Comparator.comparing(File::getName).reversed());

        for (int i = keep; i < backups.length; i++) {
            if (!backups[i].delete()) {
                System.out.println("Could not delete old backup " + backups[i].getName());
            }
        }
    }
}
