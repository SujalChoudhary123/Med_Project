import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;

/** Makes a backup the way the server does, opens it as a restore would, and compares it. */
public class BackupDrill {

    private static final String[] TABLES = {
            "users", "shops", "medicines", "invoices", "invoice_items", "credit_notes",
            "customers", "customer_ledger", "suppliers", "supplier_ledger", "purchases", "audit_log"
    };

    private static long count(Connection c, String table) throws Exception {
        try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM " + table)) {
            rs.next();
            return rs.getLong(1);
        }
    }

    public static void main(String[] args) throws Exception {

        File backup = Backup.runOnce();

        if (backup == null || !backup.exists()) {
            System.out.println("No backup was made");
            System.exit(1);
        }

        try (Connection live = DatabaseHelper.connect();
             Connection restored = DriverManager.getConnection("jdbc:sqlite:" + backup.getAbsolutePath())) {

            for (String table : TABLES) {
                if (count(live, table) != count(restored, table)) {
                    System.out.println("Row counts differ in " + table);
                    System.exit(1);
                }
            }

            try (Statement st = restored.createStatement();
                 ResultSet rs = st.executeQuery("PRAGMA integrity_check")) {
                rs.next();
                if (!"ok".equals(rs.getString(1))) {
                    System.out.println("Integrity check failed: " + rs.getString(1));
                    System.exit(1);
                }
            }
        }

        System.out.println("RESTORE OK");
    }
}
