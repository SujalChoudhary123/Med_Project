import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.security.MessageDigest;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import javax.swing.table.DefaultTableModel;

public class DatabaseHelper {

    private static final String URL = "jdbc:sqlite:" + resolveDatabasePath();

    /*
     * Resolve database path consistently.
     *
     * If the application is started from the project root,
     * the database is normally inside:
     *
     * src/medicine_inventory.db
     *
     * If that database is not found, the current working directory
     * is used as a fallback.
     */
    private static String resolveDatabasePath() {

        // DB_PATH wins: lets a server keep the database on a mounted volume.
        String configured = Config.get("DB_PATH", "");

        if (!configured.isEmpty()) {

            Path configuredPath = Paths.get(configured).toAbsolutePath().normalize();

            try {
                Files.createDirectories(configuredPath.getParent());
            } catch (Exception e) {
                System.out.println("Could not create database folder: " + e.getMessage());
            }

            return configuredPath.toString();
        }

        Path workingDir = Paths.get(System.getProperty("user.dir"))
                .toAbsolutePath()
                .normalize();

        Path projectDatabase = workingDir.resolve("src")
                .resolve("medicine_inventory.db");

        if (Files.exists(projectDatabase)) {
            return projectDatabase.toString();
        }

        Path currentDatabase = workingDir.resolve("medicine_inventory.db");

        return currentDatabase.toString();
    }

    // =========================================================
    // DATABASE CONNECTION
    // =========================================================

    /** True when the database answers a trivial query (used by /api/health). */
    public static boolean isHealthy() {

        try (Connection conn = connect();
             Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT 1")) {

            return rs.next();

        } catch (Exception e) {
            return false;
        }
    }

    /** Absolute path of the SQLite file in use. */
    public static String getDatabasePath() {
        return URL.substring("jdbc:sqlite:".length());
    }

    // Shared by every connection: WAL lets readers and a writer work at the
    // same time, and the busy timeout makes a writer wait for the lock
    // instead of failing with "database is locked" under concurrent requests.
    private static final org.sqlite.SQLiteConfig SQLITE_CONFIG = buildSqliteConfig();

    private static org.sqlite.SQLiteConfig buildSqliteConfig() {

        org.sqlite.SQLiteConfig config = new org.sqlite.SQLiteConfig();

        config.setJournalMode(org.sqlite.SQLiteConfig.JournalMode.WAL);
        config.setSynchronous(org.sqlite.SQLiteConfig.SynchronousMode.NORMAL);
        config.setBusyTimeout(10000);
        config.setTransactionMode(org.sqlite.SQLiteConfig.TransactionMode.IMMEDIATE);

        return config;
    }

    public static Connection connect() {

        try {
            return DriverManager.getConnection(URL, SQLITE_CONFIG.toProperties());

        } catch (Exception e) {

            System.out.println(
                    "Connection Error: " + e.getMessage());

            return null;
        }
    }

    // =========================================================
    // CREATE MEDICINES TABLE
    // =========================================================

    public static void createTable() {

        String sql =
                "CREATE TABLE IF NOT EXISTS medicines ("
                        + "id TEXT PRIMARY KEY,"
                        + "name TEXT NOT NULL,"
                        + "batchNumber TEXT NOT NULL,"
                        + "category TEXT,"
                        + "quantity INTEGER,"
                        + "price REAL,"
                        + "manufacturer TEXT,"
                        + "disease TEXT,"
                        + "mfgDate TEXT,"
                        + "expiryDate TEXT"
                        + ");";

        try (Connection conn = connect();
             Statement stmt = conn.createStatement()) {

            stmt.execute(sql);

        } catch (Exception e) {

            System.out.println(
                    "Error creating medicines table: "
                            + e.getMessage());
        }
    }

    // =========================================================
    // CREATE USERS TABLE
    // =========================================================

    public static void createUsersTable() {

        String sql =
                "CREATE TABLE IF NOT EXISTS users ("
                        + "id INTEGER PRIMARY KEY AUTOINCREMENT,"
                        + "username TEXT UNIQUE NOT NULL,"
                        + "password TEXT NOT NULL,"
                        + "email TEXT"
                        + ");";

        try (Connection conn = connect();
             Statement stmt = conn.createStatement()) {

            stmt.execute(sql);

            // Safe migration for databases created by older versions.
            boolean hasEmailColumn = false;
            try (ResultSet columns = stmt.executeQuery("PRAGMA table_info(users)")) {
                while (columns.next()) {
                    if ("email".equalsIgnoreCase(columns.getString("name"))) {
                        hasEmailColumn = true;
                        break;
                    }
                }
            }
            if (!hasEmailColumn) {
                stmt.execute("ALTER TABLE users ADD COLUMN email TEXT");
            }
            stmt.execute("CREATE UNIQUE INDEX IF NOT EXISTS idx_users_email ON users(email) WHERE email IS NOT NULL AND email <> ''");

        } catch (Exception e) {

            System.out.println(
                    "Error creating users table: "
                            + e.getMessage());
        }
    }

    // =========================================================
    // CREATE SALES TABLE
    // =========================================================

    public static void createSalesTable() {

        String sql =
                "CREATE TABLE IF NOT EXISTS sales ("
                        + "saleId INTEGER PRIMARY KEY AUTOINCREMENT,"
                        + "medicineId TEXT NOT NULL,"
                        + "medicineName TEXT NOT NULL,"
                        + "customerName TEXT,"
                        + "quantity INTEGER NOT NULL,"
                        + "unitPrice REAL NOT NULL,"
                        + "totalAmount REAL NOT NULL,"
                        + "saleDate TEXT NOT NULL"
                        + ");";

        try (Connection conn = connect();
             Statement stmt = conn.createStatement()) {

            stmt.execute(sql);

        } catch (Exception e) {

            System.out.println(
                    "Error creating sales table: "
                            + e.getMessage());
        }
    }

    // =========================================================
    // INITIALIZE DATABASE
    // =========================================================

    public static void initializeDatabase() {

        createTable();
        createUsersTable();
        createSalesTable();
        migrateToShops();
        migrateBilling();
        migrateBusiness();
        createSessionsTable();
    }

    // =========================================================
    // PURCHASES, SUPPLIERS, AUDIT LOG, PLANS
    // =========================================================

    private static void migrateBusiness() {

        try (Connection conn = connect()) {

            if (conn == null) {
                return;
            }

            conn.setAutoCommit(false);

            try (Statement st = conn.createStatement()) {

                // What a unit cost the shop. Copied onto each bill line, so profit stays
                // correct after the cost of a medicine changes.
                if (!hasColumn(conn, "medicines", "cost_price")) {
                    st.execute("ALTER TABLE medicines ADD COLUMN cost_price REAL NOT NULL DEFAULT 0");
                }

                if (!hasColumn(conn, "invoice_items", "cost_price")) {
                    st.execute("ALTER TABLE invoice_items ADD COLUMN cost_price REAL NOT NULL DEFAULT 0");
                }

                // Sent by the browser so a retried bill can never be saved twice.
                if (!hasColumn(conn, "invoices", "client_ref")) {
                    st.execute("ALTER TABLE invoices ADD COLUMN client_ref TEXT");
                }

                st.execute("CREATE UNIQUE INDEX IF NOT EXISTS idx_invoices_client_ref "
                        + "ON invoices(shop_id, client_ref) WHERE client_ref IS NOT NULL");

                String[][] shopColumns = {
                        {"next_purchase_no", "INTEGER NOT NULL DEFAULT 1"},
                        {"plan", "TEXT NOT NULL DEFAULT 'trial'"},
                        {"trial_ends", "TEXT NOT NULL DEFAULT ''"},
                        {"paid_until", "TEXT NOT NULL DEFAULT ''"},
                        {"disabled", "INTEGER NOT NULL DEFAULT 0"},
                        {"created_at", "TEXT NOT NULL DEFAULT ''"}
                };

                for (String[] column : shopColumns) {
                    if (!hasColumn(conn, "shops", column[0])) {
                        st.execute("ALTER TABLE shops ADD COLUMN " + column[0] + " " + column[1]);
                    }
                }

                // Shops that existed before plans get a fresh trial.
                try (PreparedStatement ps = conn.prepareStatement(
                        "UPDATE shops SET trial_ends = ? WHERE trial_ends = ''")) {
                    ps.setString(1, java.time.LocalDate.now().plusDays(Plans.TRIAL_DAYS).toString());
                    ps.executeUpdate();
                }

                if (!hasColumn(conn, "users", "terms_accepted_at")) {
                    st.execute("ALTER TABLE users ADD COLUMN terms_accepted_at TEXT NOT NULL DEFAULT ''");
                }

                if (!hasColumn(conn, "users", "terms_version")) {
                    st.execute("ALTER TABLE users ADD COLUMN terms_version TEXT NOT NULL DEFAULT ''");
                }

                st.execute(
                        "CREATE TABLE IF NOT EXISTS suppliers ("
                                + "id INTEGER PRIMARY KEY AUTOINCREMENT,"
                                + "shop_id INTEGER NOT NULL,"
                                + "name TEXT NOT NULL,"
                                + "phone TEXT NOT NULL DEFAULT '',"
                                + "gstin TEXT NOT NULL DEFAULT '',"
                                + "drug_license TEXT NOT NULL DEFAULT '',"
                                + "address TEXT NOT NULL DEFAULT '',"
                                + "notes TEXT NOT NULL DEFAULT '',"
                                + "created_at TEXT NOT NULL,"
                                + "created_by TEXT NOT NULL DEFAULT ''"
                                + ")");

                st.execute("CREATE UNIQUE INDEX IF NOT EXISTS idx_suppliers_phone "
                        + "ON suppliers(shop_id, phone) WHERE phone <> ''");
                st.execute("CREATE INDEX IF NOT EXISTS idx_suppliers_shop ON suppliers(shop_id, name)");

                st.execute(
                        "CREATE TABLE IF NOT EXISTS purchases ("
                                + "id INTEGER PRIMARY KEY AUTOINCREMENT,"
                                + "shop_id INTEGER NOT NULL,"
                                + "purchase_no TEXT NOT NULL,"
                                + "supplier_id INTEGER NOT NULL,"
                                + "supplier_name TEXT NOT NULL,"
                                + "supplier_gstin TEXT NOT NULL DEFAULT '',"
                                + "bill_no TEXT NOT NULL DEFAULT '',"
                                + "bill_date TEXT NOT NULL,"
                                + "entry_date TEXT NOT NULL,"
                                + "taxable REAL NOT NULL DEFAULT 0,"
                                + "cgst REAL NOT NULL DEFAULT 0,"
                                + "sgst REAL NOT NULL DEFAULT 0,"
                                + "total REAL NOT NULL DEFAULT 0,"
                                + "amount_paid REAL NOT NULL DEFAULT 0,"
                                + "paid_mode TEXT NOT NULL DEFAULT '',"
                                + "notes TEXT NOT NULL DEFAULT '',"
                                + "status TEXT NOT NULL DEFAULT 'active',"
                                + "client_ref TEXT,"
                                + "created_by TEXT NOT NULL DEFAULT '',"
                                + "cancelled_at TEXT,"
                                + "cancelled_by TEXT,"
                                + "UNIQUE(shop_id, purchase_no)"
                                + ")");

                st.execute("CREATE UNIQUE INDEX IF NOT EXISTS idx_purchases_client_ref "
                        + "ON purchases(shop_id, client_ref) WHERE client_ref IS NOT NULL");
                st.execute("CREATE INDEX IF NOT EXISTS idx_purchases_shop ON purchases(shop_id, id)");
                st.execute("CREATE INDEX IF NOT EXISTS idx_purchases_date ON purchases(shop_id, bill_date)");
                st.execute("CREATE INDEX IF NOT EXISTS idx_purchases_supplier ON purchases(supplier_id)");

                st.execute(
                        "CREATE TABLE IF NOT EXISTS purchase_items ("
                                + "id INTEGER PRIMARY KEY AUTOINCREMENT,"
                                + "purchase_id INTEGER NOT NULL,"
                                + "medicine_id TEXT NOT NULL,"
                                + "medicine_name TEXT NOT NULL,"
                                + "batch TEXT NOT NULL DEFAULT '',"
                                + "hsn TEXT NOT NULL DEFAULT '',"
                                + "expiry TEXT NOT NULL DEFAULT '',"
                                + "gst_rate REAL NOT NULL DEFAULT 0,"
                                + "quantity INTEGER NOT NULL,"
                                + "free_qty INTEGER NOT NULL DEFAULT 0,"
                                + "cost_price REAL NOT NULL DEFAULT 0,"
                                + "mrp REAL NOT NULL DEFAULT 0,"
                                + "taxable_value REAL NOT NULL DEFAULT 0,"
                                + "cgst REAL NOT NULL DEFAULT 0,"
                                + "sgst REAL NOT NULL DEFAULT 0,"
                                + "line_total REAL NOT NULL DEFAULT 0"
                                + ")");

                st.execute("CREATE INDEX IF NOT EXISTS idx_purchase_items ON purchase_items(purchase_id)");

                // Append-only. amount > 0: the shop owes the supplier more. amount < 0: it owes less.
                st.execute(
                        "CREATE TABLE IF NOT EXISTS supplier_ledger ("
                                + "id INTEGER PRIMARY KEY AUTOINCREMENT,"
                                + "shop_id INTEGER NOT NULL,"
                                + "supplier_id INTEGER NOT NULL,"
                                + "entry_date TEXT NOT NULL,"
                                + "kind TEXT NOT NULL,"
                                + "amount REAL NOT NULL,"
                                + "mode TEXT NOT NULL DEFAULT '',"
                                + "note TEXT NOT NULL DEFAULT '',"
                                + "purchase_id INTEGER,"
                                + "created_by TEXT NOT NULL DEFAULT ''"
                                + ")");

                st.execute("CREATE INDEX IF NOT EXISTS idx_sledger_supplier ON supplier_ledger(supplier_id, id)");

                // Who did what. Rows are only ever added, never changed.
                st.execute(
                        "CREATE TABLE IF NOT EXISTS audit_log ("
                                + "id INTEGER PRIMARY KEY AUTOINCREMENT,"
                                + "shop_id INTEGER NOT NULL,"
                                + "at TEXT NOT NULL,"
                                + "username TEXT NOT NULL DEFAULT '',"
                                + "role TEXT NOT NULL DEFAULT '',"
                                + "action TEXT NOT NULL,"
                                + "target TEXT NOT NULL DEFAULT '',"
                                + "detail TEXT NOT NULL DEFAULT '',"
                                + "ip TEXT NOT NULL DEFAULT ''"
                                + ")");

                st.execute("CREATE INDEX IF NOT EXISTS idx_audit_shop ON audit_log(shop_id, id)");
            }

            conn.commit();

        } catch (Exception e) {

            System.out.println("Business migration failed: " + e.getMessage());
            throw new RuntimeException("Database migration failed", e);
        }
    }

    // =========================================================
    // GST BILLING TABLES + SHOP / MEDICINE GST FIELDS
    // =========================================================

    private static void migrateBilling() {

        try (Connection conn = connect()) {

            if (conn == null) {
                return;
            }

            conn.setAutoCommit(false);

            try (Statement st = conn.createStatement()) {

                // Shop details printed on every invoice.
                String[][] shopColumns = {
                        {"address", "TEXT NOT NULL DEFAULT ''"},
                        {"phone", "TEXT NOT NULL DEFAULT ''"},
                        {"gstin", "TEXT NOT NULL DEFAULT ''"},
                        {"drug_license", "TEXT NOT NULL DEFAULT ''"},
                        {"state", "TEXT NOT NULL DEFAULT ''"},
                        {"invoice_prefix", "TEXT NOT NULL DEFAULT 'INV-'"},
                        {"prices_include_gst", "INTEGER NOT NULL DEFAULT 1"},
                        {"next_invoice_no", "INTEGER NOT NULL DEFAULT 1"}
                };

                for (String[] column : shopColumns) {
                    if (!hasColumn(conn, "shops", column[0])) {
                        st.execute("ALTER TABLE shops ADD COLUMN " + column[0] + " " + column[1]);
                    }
                }

                // GST rate (percent) and HSN code per medicine.
                if (!hasColumn(conn, "medicines", "gst_rate")) {
                    st.execute("ALTER TABLE medicines ADD COLUMN gst_rate REAL NOT NULL DEFAULT 5");
                }

                if (!hasColumn(conn, "medicines", "hsn")) {
                    st.execute("ALTER TABLE medicines ADD COLUMN hsn TEXT NOT NULL DEFAULT '3004'");
                }

                // Each line of a bill is also written to sales, so the
                // dashboard and reports keep working. This links them.
                if (!hasColumn(conn, "sales", "invoice_id")) {
                    st.execute("ALTER TABLE sales ADD COLUMN invoice_id INTEGER");
                }

                st.execute(
                        "CREATE TABLE IF NOT EXISTS invoices ("
                                + "id INTEGER PRIMARY KEY AUTOINCREMENT,"
                                + "shop_id INTEGER NOT NULL,"
                                + "invoice_no TEXT NOT NULL,"
                                + "invoice_date TEXT NOT NULL,"
                                + "customer_name TEXT NOT NULL DEFAULT '',"
                                + "customer_phone TEXT NOT NULL DEFAULT '',"
                                + "customer_gstin TEXT NOT NULL DEFAULT '',"
                                + "payment_mode TEXT NOT NULL DEFAULT 'cash',"
                                + "prices_include_gst INTEGER NOT NULL DEFAULT 1,"
                                + "discount REAL NOT NULL DEFAULT 0,"
                                + "taxable REAL NOT NULL DEFAULT 0,"
                                + "cgst REAL NOT NULL DEFAULT 0,"
                                + "sgst REAL NOT NULL DEFAULT 0,"
                                + "round_off REAL NOT NULL DEFAULT 0,"
                                + "total REAL NOT NULL DEFAULT 0,"
                                + "status TEXT NOT NULL DEFAULT 'active',"
                                + "created_by TEXT NOT NULL DEFAULT '',"
                                + "cancelled_at TEXT,"
                                + "cancelled_by TEXT,"
                                + "seller_name TEXT NOT NULL DEFAULT '',"
                                + "seller_address TEXT NOT NULL DEFAULT '',"
                                + "seller_phone TEXT NOT NULL DEFAULT '',"
                                + "seller_gstin TEXT NOT NULL DEFAULT '',"
                                + "seller_license TEXT NOT NULL DEFAULT '',"
                                + "seller_state TEXT NOT NULL DEFAULT '',"
                                + "UNIQUE (shop_id, invoice_no)"
                                + ")");

                st.execute(
                        "CREATE TABLE IF NOT EXISTS invoice_items ("
                                + "id INTEGER PRIMARY KEY AUTOINCREMENT,"
                                + "invoice_id INTEGER NOT NULL,"
                                + "medicine_id TEXT NOT NULL,"
                                + "medicine_name TEXT NOT NULL,"
                                + "batch TEXT NOT NULL DEFAULT '',"
                                + "hsn TEXT NOT NULL DEFAULT '',"
                                + "expiry TEXT NOT NULL DEFAULT '',"
                                + "gst_rate REAL NOT NULL DEFAULT 0,"
                                + "quantity INTEGER NOT NULL,"
                                + "unit_price REAL NOT NULL,"
                                + "discount_percent REAL NOT NULL DEFAULT 0,"
                                + "discount_amount REAL NOT NULL DEFAULT 0,"
                                + "taxable_value REAL NOT NULL,"
                                + "cgst REAL NOT NULL,"
                                + "sgst REAL NOT NULL,"
                                + "line_total REAL NOT NULL"
                                + ")");

                // ----- returns (credit notes) -----

                String[][] returnColumns = {
                        {"returned_qty", "INTEGER NOT NULL DEFAULT 0"},
                        {"returned_taxable", "REAL NOT NULL DEFAULT 0"},
                        {"returned_cgst", "REAL NOT NULL DEFAULT 0"},
                        {"returned_sgst", "REAL NOT NULL DEFAULT 0"},
                        {"returned_total", "REAL NOT NULL DEFAULT 0"}
                };

                for (String[] column : returnColumns) {
                    if (!hasColumn(conn, "invoice_items", column[0])) {
                        st.execute("ALTER TABLE invoice_items ADD COLUMN " + column[0] + " " + column[1]);
                    }
                }

                if (!hasColumn(conn, "shops", "next_credit_no")) {
                    st.execute("ALTER TABLE shops ADD COLUMN next_credit_no INTEGER NOT NULL DEFAULT 1");
                }

                st.execute(
                        "CREATE TABLE IF NOT EXISTS credit_notes ("
                                + "id INTEGER PRIMARY KEY AUTOINCREMENT,"
                                + "shop_id INTEGER NOT NULL,"
                                + "note_no TEXT NOT NULL,"
                                + "note_date TEXT NOT NULL,"
                                + "invoice_id INTEGER NOT NULL,"
                                + "invoice_no TEXT NOT NULL,"
                                + "invoice_date TEXT NOT NULL,"
                                + "customer_name TEXT NOT NULL DEFAULT '',"
                                + "customer_phone TEXT NOT NULL DEFAULT '',"
                                + "customer_gstin TEXT NOT NULL DEFAULT '',"
                                + "reason TEXT NOT NULL DEFAULT '',"
                                + "refund_mode TEXT NOT NULL DEFAULT 'cash',"
                                + "restocked INTEGER NOT NULL DEFAULT 1,"
                                + "prices_include_gst INTEGER NOT NULL DEFAULT 1,"
                                + "discount REAL NOT NULL DEFAULT 0,"
                                + "taxable REAL NOT NULL DEFAULT 0,"
                                + "cgst REAL NOT NULL DEFAULT 0,"
                                + "sgst REAL NOT NULL DEFAULT 0,"
                                + "round_off REAL NOT NULL DEFAULT 0,"
                                + "total REAL NOT NULL DEFAULT 0,"
                                + "created_by TEXT NOT NULL DEFAULT '',"
                                + "seller_name TEXT NOT NULL DEFAULT '',"
                                + "seller_address TEXT NOT NULL DEFAULT '',"
                                + "seller_phone TEXT NOT NULL DEFAULT '',"
                                + "seller_gstin TEXT NOT NULL DEFAULT '',"
                                + "seller_license TEXT NOT NULL DEFAULT '',"
                                + "seller_state TEXT NOT NULL DEFAULT '',"
                                + "UNIQUE (shop_id, note_no)"
                                + ")");

                st.execute(
                        "CREATE TABLE IF NOT EXISTS credit_note_items ("
                                + "id INTEGER PRIMARY KEY AUTOINCREMENT,"
                                + "credit_note_id INTEGER NOT NULL,"
                                + "invoice_item_id INTEGER NOT NULL,"
                                + "medicine_id TEXT NOT NULL,"
                                + "medicine_name TEXT NOT NULL,"
                                + "batch TEXT NOT NULL DEFAULT '',"
                                + "hsn TEXT NOT NULL DEFAULT '',"
                                + "gst_rate REAL NOT NULL DEFAULT 0,"
                                + "quantity INTEGER NOT NULL,"
                                + "unit_price REAL NOT NULL,"
                                + "discount_percent REAL NOT NULL DEFAULT 0,"
                                + "discount_amount REAL NOT NULL DEFAULT 0,"
                                + "taxable_value REAL NOT NULL,"
                                + "cgst REAL NOT NULL,"
                                + "sgst REAL NOT NULL,"
                                + "line_total REAL NOT NULL"
                                + ")");

                st.execute("CREATE INDEX IF NOT EXISTS idx_credit_notes_shop ON credit_notes(shop_id, id)");
                st.execute("CREATE INDEX IF NOT EXISTS idx_credit_notes_invoice ON credit_notes(invoice_id)");
                st.execute("CREATE INDEX IF NOT EXISTS idx_credit_items_note ON credit_note_items(credit_note_id)");

                // ----- customers and their udhaar (credit) ledger -----

                st.execute(
                        "CREATE TABLE IF NOT EXISTS customers ("
                                + "id INTEGER PRIMARY KEY AUTOINCREMENT,"
                                + "shop_id INTEGER NOT NULL,"
                                + "name TEXT NOT NULL,"
                                + "phone TEXT NOT NULL DEFAULT '',"
                                + "gstin TEXT NOT NULL DEFAULT '',"
                                + "address TEXT NOT NULL DEFAULT '',"
                                + "notes TEXT NOT NULL DEFAULT '',"
                                + "credit_limit REAL NOT NULL DEFAULT 0,"
                                + "created_at TEXT NOT NULL,"
                                + "created_by TEXT NOT NULL DEFAULT ''"
                                + ")");

                // One phone number belongs to one customer inside a shop.
                st.execute("CREATE UNIQUE INDEX IF NOT EXISTS idx_customers_phone "
                        + "ON customers(shop_id, phone) WHERE phone <> ''");
                st.execute("CREATE INDEX IF NOT EXISTS idx_customers_shop ON customers(shop_id, name)");

                // Append-only. amount > 0: the customer owes more. amount < 0: they owe less.
                // The balance is simply the sum of a customer's entries.
                st.execute(
                        "CREATE TABLE IF NOT EXISTS customer_ledger ("
                                + "id INTEGER PRIMARY KEY AUTOINCREMENT,"
                                + "shop_id INTEGER NOT NULL,"
                                + "customer_id INTEGER NOT NULL,"
                                + "entry_date TEXT NOT NULL,"
                                + "kind TEXT NOT NULL,"
                                + "amount REAL NOT NULL,"
                                + "mode TEXT NOT NULL DEFAULT '',"
                                + "note TEXT NOT NULL DEFAULT '',"
                                + "invoice_id INTEGER,"
                                + "credit_note_id INTEGER,"
                                + "created_by TEXT NOT NULL DEFAULT ''"
                                + ")");

                st.execute("CREATE INDEX IF NOT EXISTS idx_ledger_customer ON customer_ledger(customer_id, id)");
                st.execute("CREATE INDEX IF NOT EXISTS idx_ledger_shop_date ON customer_ledger(shop_id, entry_date)");

                if (!hasColumn(conn, "invoices", "customer_id")) {
                    st.execute("ALTER TABLE invoices ADD COLUMN customer_id INTEGER");
                }

                // For a credit bill: what the customer paid on the spot.
                if (!hasColumn(conn, "invoices", "amount_paid")) {
                    st.execute("ALTER TABLE invoices ADD COLUMN amount_paid REAL NOT NULL DEFAULT 0");
                }

                st.execute("CREATE INDEX IF NOT EXISTS idx_invoices_customer ON invoices(customer_id)");

                st.execute("CREATE INDEX IF NOT EXISTS idx_invoices_shop ON invoices(shop_id, id)");
                st.execute("CREATE INDEX IF NOT EXISTS idx_invoice_items_invoice ON invoice_items(invoice_id)");
                st.execute("CREATE INDEX IF NOT EXISTS idx_sales_invoice ON sales(invoice_id)");
            }

            conn.commit();

        } catch (Exception e) {

            System.out.println("Billing migration failed: " + e.getMessage());
            throw new RuntimeException("Database migration failed", e);
        }
    }

    /** Sets the cost before GST. null leaves it unchanged. */
    public static void setMedicineCost(long shopId, String medicineId, Double cost) {

        if (cost == null) {
            return;
        }

        try (Connection conn = connect();
             PreparedStatement ps = conn.prepareStatement(
                     "UPDATE medicines SET cost_price = ? WHERE id = ? AND shop_id = ?")) {

            ps.setDouble(1, cost);
            ps.setString(2, medicineId);
            ps.setLong(3, shopId);
            ps.executeUpdate();

        } catch (Exception e) {
            System.out.println("Set cost error: " + e.getMessage());
        }
    }

    /** Sets GST rate and/or HSN for one medicine. Pass null to leave a value unchanged. */
    public static boolean setMedicineGst(long shopId, String medicineId, Double gstRate, String hsn) {

        if (gstRate == null && hsn == null) {
            return true;
        }

        String sql = "UPDATE medicines SET "
                + (gstRate != null ? "gst_rate = ?" : "gst_rate = gst_rate")
                + ", "
                + (hsn != null ? "hsn = ?" : "hsn = hsn")
                + " WHERE id = ? AND shop_id = ?";

        try (Connection conn = connect();
             PreparedStatement ps = conn.prepareStatement(sql)) {

            int index = 1;

            if (gstRate != null) ps.setDouble(index++, gstRate);
            if (hsn != null) ps.setString(index++, hsn);

            ps.setString(index++, medicineId);
            ps.setLong(index, shopId);

            return ps.executeUpdate() > 0;

        } catch (Exception e) {
            System.out.println("Set GST error: " + e.getMessage());
            return false;
        }
    }

    // ----- shop profile -----

    public static final class Shop {
        public long id;
        public String name = "", address = "", phone = "", gstin = "", drugLicense = "";
        public String state = "", invoicePrefix = "INV-";
        public boolean pricesIncludeGst = true;
        public long nextInvoiceNo = 1;
    }

    public static Shop getShop(long shopId) {

        String sql = "SELECT id, name, address, phone, gstin, drug_license, state, "
                + "invoice_prefix, prices_include_gst, next_invoice_no FROM shops WHERE id = ?";

        try (Connection conn = connect();
             PreparedStatement ps = conn.prepareStatement(sql)) {

            ps.setLong(1, shopId);

            try (ResultSet rs = ps.executeQuery()) {

                if (rs.next()) {
                    Shop shop = new Shop();
                    shop.id = rs.getLong("id");
                    shop.name = rs.getString("name");
                    shop.address = rs.getString("address");
                    shop.phone = rs.getString("phone");
                    shop.gstin = rs.getString("gstin");
                    shop.drugLicense = rs.getString("drug_license");
                    shop.state = rs.getString("state");
                    shop.invoicePrefix = rs.getString("invoice_prefix");
                    shop.pricesIncludeGst = rs.getInt("prices_include_gst") == 1;
                    shop.nextInvoiceNo = rs.getLong("next_invoice_no");
                    return shop;
                }
            }

        } catch (Exception e) {
            System.out.println("Get shop error: " + e.getMessage());
        }

        return null;
    }

    public static boolean updateShop(long shopId, Shop s) {

        String sql = "UPDATE shops SET name = ?, address = ?, phone = ?, gstin = ?, "
                + "drug_license = ?, state = ?, invoice_prefix = ?, prices_include_gst = ? "
                + "WHERE id = ?";

        try (Connection conn = connect();
             PreparedStatement ps = conn.prepareStatement(sql)) {

            ps.setString(1, s.name);
            ps.setString(2, s.address);
            ps.setString(3, s.phone);
            ps.setString(4, s.gstin);
            ps.setString(5, s.drugLicense);
            ps.setString(6, s.state);
            ps.setString(7, s.invoicePrefix);
            ps.setInt(8, s.pricesIncludeGst ? 1 : 0);
            ps.setLong(9, shopId);

            return ps.executeUpdate() == 1;

        } catch (Exception e) {
            System.out.println("Update shop error: " + e.getMessage());
            return false;
        }
    }

    // =========================================================
    // LOGIN SESSIONS (stored in the database so they survive a restart)
    // =========================================================
    // Only the SHA-256 of the token is stored, never the token itself, so a
    // leaked database cannot be used to take over a running session.

    private static void createSessionsTable() {

        try (Connection conn = connect();
             Statement st = conn.createStatement()) {

            st.execute(
                    "CREATE TABLE IF NOT EXISTS sessions ("
                            + "token_hash TEXT PRIMARY KEY,"
                            + "user_id INTEGER NOT NULL,"
                            + "expires_at INTEGER NOT NULL"
                            + ")");

            st.execute("CREATE INDEX IF NOT EXISTS idx_sessions_user ON sessions(user_id)");

        } catch (Exception e) {
            throw new RuntimeException("Could not create sessions table", e);
        }
    }

    public static void createSession(String tokenHash, String username, long expiresAtMillis) {

        String sql = "INSERT INTO sessions(token_hash, user_id, expires_at) "
                + "SELECT ?, id, ? FROM users WHERE username = ? COLLATE NOCASE";

        try (Connection conn = connect();
             PreparedStatement ps = conn.prepareStatement(sql)) {

            ps.setString(1, tokenHash);
            ps.setLong(2, expiresAtMillis);
            ps.setString(3, username);

            if (ps.executeUpdate() != 1) {
                throw new IllegalStateException("Cannot start a session for unknown user");
            }

        } catch (java.sql.SQLException e) {
            throw new RuntimeException("Could not save session", e);
        }
    }

    /** Role and shop are read fresh on every request, so changes apply immediately. */
    public static UserInfo findSession(String tokenHash) {

        String sql = "SELECT u.username, u.shop_id, u.role FROM sessions s "
                + "JOIN users u ON u.id = s.user_id "
                + "WHERE s.token_hash = ? AND s.expires_at > ?";

        try (Connection conn = connect();
             PreparedStatement ps = conn.prepareStatement(sql)) {

            ps.setString(1, tokenHash);
            ps.setLong(2, System.currentTimeMillis());

            try (ResultSet rs = ps.executeQuery()) {

                if (rs.next()) {
                    return new UserInfo(
                            rs.getString("username"),
                            rs.getLong("shop_id"),
                            rs.getString("role"));
                }
            }

        } catch (Exception e) {
            System.out.println("Session lookup error: " + e.getMessage());
        }

        return null;
    }

    public static void deleteSession(String tokenHash) {

        try (Connection conn = connect();
             PreparedStatement ps = conn.prepareStatement(
                     "DELETE FROM sessions WHERE token_hash = ?")) {

            ps.setString(1, tokenHash);
            ps.executeUpdate();

        } catch (Exception e) {
            System.out.println("Session delete error: " + e.getMessage());
        }
    }

    public static void deleteSessionsForUser(String username) {

        String sql = "DELETE FROM sessions WHERE user_id IN "
                + "(SELECT id FROM users WHERE username = ? COLLATE NOCASE)";

        try (Connection conn = connect();
             PreparedStatement ps = conn.prepareStatement(sql)) {

            ps.setString(1, username);
            ps.executeUpdate();

        } catch (Exception e) {
            System.out.println("Session revoke error: " + e.getMessage());
        }
    }

    /** Removes expired sessions and sessions of users that no longer exist. */
    public static void purgeSessions() {

        try (Connection conn = connect();
             PreparedStatement ps = conn.prepareStatement(
                     "DELETE FROM sessions WHERE expires_at <= ? "
                             + "OR user_id NOT IN (SELECT id FROM users)")) {

            ps.setLong(1, System.currentTimeMillis());
            ps.executeUpdate();

        } catch (Exception e) {
            System.out.println("Session purge error: " + e.getMessage());
        }
    }

    // =========================================================
    // MULTI-SHOP (TENANT) MIGRATION
    // =========================================================
    // Every pharmacy gets its own shop_id. Existing data is assigned to
    // shop 1, and existing users become owners of shop 1, so nothing is lost.

    private static boolean hasColumn(Connection conn, String table, String column)
            throws java.sql.SQLException {

        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("PRAGMA table_info(" + table + ")")) {

            while (rs.next()) {
                if (column.equalsIgnoreCase(rs.getString("name"))) {
                    return true;
                }
            }
        }

        return false;
    }

    public static void migrateToShops() {

        try (Connection conn = connect()) {

            if (conn == null) {
                return;
            }

            conn.setAutoCommit(false);

            try (Statement st = conn.createStatement()) {

                st.execute(
                        "CREATE TABLE IF NOT EXISTS shops ("
                                + "id INTEGER PRIMARY KEY AUTOINCREMENT,"
                                + "name TEXT NOT NULL,"
                                + "created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP"
                                + ")");

                st.execute(
                        "INSERT INTO shops(id, name) "
                                + "SELECT 1, 'My Pharmacy' "
                                + "WHERE NOT EXISTS (SELECT 1 FROM shops WHERE id = 1)");

                if (!hasColumn(conn, "users", "shop_id")) {
                    st.execute("ALTER TABLE users ADD COLUMN shop_id INTEGER NOT NULL DEFAULT 1");
                }

                if (!hasColumn(conn, "users", "role")) {
                    st.execute("ALTER TABLE users ADD COLUMN role TEXT NOT NULL DEFAULT 'owner'");
                }

                if (!hasColumn(conn, "sales", "shop_id")) {
                    st.execute("ALTER TABLE sales ADD COLUMN shop_id INTEGER NOT NULL DEFAULT 1");
                }

                // medicines.id must only be unique INSIDE a shop, so the
                // primary key becomes (shop_id, id). SQLite needs a rebuild.
                if (!hasColumn(conn, "medicines", "shop_id")) {

                    st.execute("ALTER TABLE medicines RENAME TO medicines_old");

                    st.execute(
                            "CREATE TABLE medicines ("
                                    + "shop_id INTEGER NOT NULL DEFAULT 1,"
                                    + "id TEXT NOT NULL,"
                                    + "name TEXT NOT NULL,"
                                    + "batchNumber TEXT NOT NULL,"
                                    + "category TEXT,"
                                    + "quantity INTEGER,"
                                    + "price REAL,"
                                    + "manufacturer TEXT,"
                                    + "disease TEXT,"
                                    + "mfgDate TEXT,"
                                    + "expiryDate TEXT,"
                                    + "PRIMARY KEY (shop_id, id)"
                                    + ")");

                    st.execute(
                            "INSERT INTO medicines(shop_id, id, name, batchNumber, category, "
                                    + "quantity, price, manufacturer, disease, mfgDate, expiryDate) "
                                    + "SELECT 1, id, name, batchNumber, category, "
                                    + "quantity, price, manufacturer, disease, mfgDate, expiryDate "
                                    + "FROM medicines_old");

                    st.execute("DROP TABLE medicines_old");
                }

                st.execute("CREATE INDEX IF NOT EXISTS idx_sales_shop ON sales(shop_id)");
                st.execute("CREATE INDEX IF NOT EXISTS idx_users_shop ON users(shop_id)");
            }

            conn.commit();

        } catch (Exception e) {

            System.out.println("Shop migration failed: " + e.getMessage());
            throw new RuntimeException("Database migration failed", e);
        }
    }

    // =========================================================
    // USER -> SHOP LOOKUP
    // =========================================================

    public static final class UserInfo {
        public final String username;
        public final long shopId;
        public final String role;

        UserInfo(String username, long shopId, String role) {
            this.username = username;
            this.shopId = shopId;
            this.role = role;
        }
    }

    public static UserInfo getUserInfo(String username) {

        String sql = "SELECT username, shop_id, role FROM users "
                + "WHERE username = ? COLLATE NOCASE";

        try (Connection conn = connect();
             PreparedStatement ps = conn.prepareStatement(sql)) {

            ps.setString(1, username);

            try (ResultSet rs = ps.executeQuery()) {

                if (rs.next()) {
                    return new UserInfo(
                            rs.getString("username"),
                            rs.getLong("shop_id"),
                            rs.getString("role"));
                }
            }

        } catch (Exception e) {
            System.out.println("User info error: " + e.getMessage());
        }

        return null;
    }

    // =========================================================
    // TEAM (staff accounts inside one shop)
    // =========================================================
    // Roles: owner (created at signup), pharmacist, cashier.

    public static final class TeamMember {
        public final String username;
        public final String email;
        public final String role;

        TeamMember(String username, String email, String role) {
            this.username = username;
            this.email = email;
            this.role = role;
        }
    }

    public static java.util.List<TeamMember> listTeam(long shopId) {

        java.util.List<TeamMember> members = new java.util.ArrayList<>();

        String sql = "SELECT username, email, role FROM users WHERE shop_id = ? "
                + "ORDER BY CASE role WHEN 'owner' THEN 0 WHEN 'pharmacist' THEN 1 ELSE 2 END, "
                + "username COLLATE NOCASE";

        try (Connection conn = connect();
             PreparedStatement ps = conn.prepareStatement(sql)) {

            ps.setLong(1, shopId);

            try (ResultSet rs = ps.executeQuery()) {

                while (rs.next()) {
                    members.add(new TeamMember(
                            rs.getString("username"),
                            rs.getString("email") == null ? "" : rs.getString("email"),
                            rs.getString("role")));
                }
            }

        } catch (Exception e) {
            System.out.println("List team error: " + e.getMessage());
        }

        return members;
    }

    /** Returns 1 on success, -1 when the username/email already exists, 0 on any other error. */
    public static int createStaffUser(
            long shopId, String username, String email, String password, String role) {

        String sql = "INSERT INTO users(username, password, email, shop_id, role) "
                + "VALUES(?, ?, ?, ?, ?)";

        try (Connection conn = connect();
             PreparedStatement ps = conn.prepareStatement(sql)) {

            ps.setString(1, username);
            ps.setString(2, hashPassword(password));
            ps.setString(3, email.trim().toLowerCase());
            ps.setLong(4, shopId);
            ps.setString(5, role);
            ps.executeUpdate();

            return 1;

        } catch (java.sql.SQLException e) {

            if (e.getMessage() != null && e.getMessage().toLowerCase().contains("unique")) {
                return -1;
            }

            System.out.println("Create staff error: " + e.getMessage());
            return 0;

        } catch (Exception e) {
            System.out.println("Create staff error: " + e.getMessage());
            return 0;
        }
    }

    /** Owners can never be removed or changed here, so a shop always keeps its owner. */
    public static boolean removeTeamMember(long shopId, String username) {

        String sql = "DELETE FROM users WHERE shop_id = ? "
                + "AND username = ? COLLATE NOCASE AND role <> 'owner'";

        try (Connection conn = connect();
             PreparedStatement ps = conn.prepareStatement(sql)) {

            ps.setLong(1, shopId);
            ps.setString(2, username);

            return ps.executeUpdate() > 0;

        } catch (Exception e) {
            System.out.println("Remove team member error: " + e.getMessage());
            return false;
        }
    }

    public static boolean updateTeamRole(long shopId, String username, String role) {

        String sql = "UPDATE users SET role = ? WHERE shop_id = ? "
                + "AND username = ? COLLATE NOCASE AND role <> 'owner'";

        try (Connection conn = connect();
             PreparedStatement ps = conn.prepareStatement(sql)) {

            ps.setString(1, role);
            ps.setLong(2, shopId);
            ps.setString(3, username);

            return ps.executeUpdate() > 0;

        } catch (Exception e) {
            System.out.println("Update team role error: " + e.getMessage());
            return false;
        }
    }

    // =========================================================
    // PASSWORD HASHING
    // =========================================================

    // Stored format: pbkdf2$<iterations>$<saltBase64>$<hashBase64>
    // Older accounts hold a plain unsalted SHA-256 hex string. Those are
    // still accepted once, then silently upgraded at the next login.

    private static final String PBKDF2_PREFIX = "pbkdf2$";
    private static final int PBKDF2_ITERATIONS = 210_000;
    private static final int PBKDF2_KEY_BITS = 256;
    private static final java.security.SecureRandom SALT_RANDOM =
            new java.security.SecureRandom();

    // Verified against when an account does not exist, so "unknown user"
    // and "wrong password" take the same time.
    private static final String DUMMY_HASH = hashPassword("medtrack-dummy-password");

    public static String hashPassword(String password) {

        try {

            byte[] salt = new byte[16];
            SALT_RANDOM.nextBytes(salt);

            byte[] hash = pbkdf2(password, salt, PBKDF2_ITERATIONS);

            java.util.Base64.Encoder encoder = java.util.Base64.getEncoder();

            return PBKDF2_PREFIX
                    + PBKDF2_ITERATIONS + "$"
                    + encoder.encodeToString(salt) + "$"
                    + encoder.encodeToString(hash);

        } catch (Exception e) {

            throw new RuntimeException(
                    "Password hashing error", e);
        }
    }

    private static byte[] pbkdf2(String password, byte[] salt, int iterations)
            throws Exception {

        javax.crypto.spec.PBEKeySpec spec = new javax.crypto.spec.PBEKeySpec(
                password.toCharArray(), salt, iterations, PBKDF2_KEY_BITS);

        try {
            return javax.crypto.SecretKeyFactory
                    .getInstance("PBKDF2WithHmacSHA256")
                    .generateSecret(spec)
                    .getEncoded();
        } finally {
            spec.clearPassword();
        }
    }

    private static String legacySha256(String password) throws Exception {

        byte[] hash = MessageDigest.getInstance("SHA-256")
                .digest(password.getBytes(StandardCharsets.UTF_8));

        StringBuilder hex = new StringBuilder();

        for (byte b : hash) {
            hex.append(String.format("%02x", b));
        }

        return hex.toString();
    }

    public static boolean verifyPassword(String password, String stored) {

        if (password == null || stored == null) {
            return false;
        }

        try {

            if (stored.startsWith(PBKDF2_PREFIX)) {

                String[] parts = stored.split("\\$");

                if (parts.length != 4) {
                    return false;
                }

                int iterations = Integer.parseInt(parts[1]);
                byte[] salt = java.util.Base64.getDecoder().decode(parts[2]);
                byte[] expected = java.util.Base64.getDecoder().decode(parts[3]);

                return MessageDigest.isEqual(
                        expected, pbkdf2(password, salt, iterations));
            }

            // Legacy SHA-256 account.
            return MessageDigest.isEqual(
                    stored.getBytes(StandardCharsets.UTF_8),
                    legacySha256(password).getBytes(StandardCharsets.UTF_8));

        } catch (Exception e) {
            return false;
        }
    }

    private static boolean needsRehash(String stored) {
        return stored == null || !stored.startsWith(PBKDF2_PREFIX);
    }

    private static void upgradePasswordHash(
            Connection conn, String username, String oldStored, String password) {

        String sql = "UPDATE users SET password = ? "
                + "WHERE username = ? COLLATE NOCASE AND password = ?";

        try (PreparedStatement ps = conn.prepareStatement(sql)) {

            ps.setString(1, hashPassword(password));
            ps.setString(2, username);
            ps.setString(3, oldStored);
            ps.executeUpdate();

        } catch (Exception e) {
            System.out.println("Password hash upgrade skipped: " + e.getMessage());
        }
    }

    // =========================================================
    // REGISTER USER
    // =========================================================

    public static boolean registerUser(String username, String password) {
        return registerUser(username, null, password, "");
    }

    public static boolean registerUser(String username, String email, String password) {
        return registerUser(username, email, password, "");
    }

    // Every new signup creates a brand-new shop and becomes its owner.
    // termsVersion = the Terms the person agreed to when signing up ("" when not recorded).
    public static boolean registerUser(String username, String email, String password, String termsVersion) {
        String shopSql = "INSERT INTO shops(name, plan, trial_ends, created_at) VALUES(?, 'trial', ?, ?)";
        String userSql = "INSERT INTO users(username, password, email, shop_id, role, terms_accepted_at, terms_version) "
                + "VALUES(?, ?, ?, ?, 'owner', ?, ?)";

        try (Connection conn = connect()) {

            if (conn == null) {
                return false;
            }

            conn.setAutoCommit(false);

            try {
                long shopId;

                try (PreparedStatement ps = conn.prepareStatement(
                        shopSql, Statement.RETURN_GENERATED_KEYS)) {

                    ps.setString(1, username + "'s Pharmacy");
                    ps.setString(2, java.time.LocalDate.now().plusDays(Plans.TRIAL_DAYS).toString());
                    ps.setString(3, java.time.LocalDateTime.now().toString());
                    ps.executeUpdate();

                    try (ResultSet keys = ps.getGeneratedKeys()) {
                        if (!keys.next()) {
                            throw new java.sql.SQLException("Shop id was not generated");
                        }
                        shopId = keys.getLong(1);
                    }
                }

                try (PreparedStatement pstmt = conn.prepareStatement(userSql)) {

                    pstmt.setString(1, username);
                    pstmt.setString(2, hashPassword(password));

                    if (email == null || email.trim().isEmpty()) {
                        pstmt.setNull(3, java.sql.Types.VARCHAR);
                    } else {
                        pstmt.setString(3, email.trim().toLowerCase());
                    }

                    pstmt.setLong(4, shopId);
                    pstmt.setString(5, termsVersion == null || termsVersion.isEmpty()
                            ? "" : java.time.LocalDateTime.now().toString());
                    pstmt.setString(6, termsVersion == null ? "" : termsVersion);
                    pstmt.executeUpdate();
                }

                conn.commit();
                return true;

            } catch (Exception e) {

                conn.rollback();

                if (e.getMessage() != null && e.getMessage().toLowerCase().contains("unique")) {
                    System.out.println("Username or email already exists.");
                } else {
                    System.out.println("Registration Error: " + e.getMessage());
                }

                return false;
            }

        } catch (Exception e) {
            System.out.println("Registration Error: " + e.getMessage());
            return false;
        }
    }

    // =========================================================
    // LOGIN CHECK
    // =========================================================

    /** Returns the username when the credentials are valid, otherwise null. */
    public static String getUsernameForLogin(String usernameOrEmail, String password) {

        String sql = "SELECT username, password FROM users "
                + "WHERE username = ? COLLATE NOCASE OR email = ? COLLATE NOCASE";

        try (Connection conn = connect()) {

            if (conn == null) {
                return null;
            }

            String username = null;
            String stored = null;

            try (PreparedStatement pstmt = conn.prepareStatement(sql)) {

                pstmt.setString(1, usernameOrEmail);
                pstmt.setString(2, usernameOrEmail);

                try (ResultSet rs = pstmt.executeQuery()) {

                    if (rs.next()) {
                        username = rs.getString("username");
                        stored = rs.getString("password");
                    }
                }
            }

            if (username == null) {
                verifyPassword(password, DUMMY_HASH); // equalise timing
                return null;
            }

            if (!verifyPassword(password, stored)) {
                return null;
            }

            if (needsRehash(stored)) {
                upgradePasswordHash(conn, username, stored, password);
            }

            return username;

        } catch (Exception e) {
            System.out.println("Login Validation Error: " + e.getMessage());
            return null;
        }
    }
    

     
public static boolean updatePasswordByEmail(String email, String newPassword) {
    if (email == null || email.trim().isEmpty()
            || newPassword == null || newPassword.length() < 8) {
        return false;
    }

    String sql = "UPDATE users SET password = ? WHERE email = ? COLLATE NOCASE";

    try (Connection conn = connect()) {
        if (conn == null) return false;

        try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setString(1, hashPassword(newPassword));
            pstmt.setString(2, email.trim());
            return pstmt.executeUpdate() == 1;
        }
    } catch (Exception e) {
        System.out.println("Password Update Error: " + e.getMessage());
        return false;
    }
}

    // =========================================================
    // INSERT MEDICINE
    // =========================================================

    
    // Authentication helpers for Google sign-in and email OTP flow.
    public static String getUsernameByEmail(String email) {
        String sql = "SELECT username FROM users WHERE email = ? COLLATE NOCASE";
        try (Connection conn = connect(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, email == null ? "" : email.trim().toLowerCase());
            try (ResultSet rs = ps.executeQuery()) { return rs.next() ? rs.getString("username") : null; }
        } catch (Exception e) { System.out.println("Email lookup error: " + e.getMessage()); return null; }
    }

    public static boolean usernameExists(String username) {
        String sql = "SELECT 1 FROM users WHERE username = ? COLLATE NOCASE";
        try (Connection conn = connect(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, username);
            try (ResultSet rs = ps.executeQuery()) { return rs.next(); }
        } catch (Exception e) { System.out.println("Username lookup error: " + e.getMessage()); return true; }
    }

    // =========================================================
    // USER PROFILE AND PASSWORD MANAGEMENT
    // =========================================================

    public static String[] getUserProfile(String username) {
        String sql = "SELECT username, email FROM users WHERE username = ? COLLATE NOCASE";
        try (Connection conn = connect();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setString(1, username);
            try (ResultSet rs = pstmt.executeQuery()) {
                if (rs.next()) {
                    return new String[] {
                        rs.getString("username"),
                        rs.getString("email") == null ? "" : rs.getString("email")
                    };
                }
            }
        } catch (Exception e) {
            System.out.println("Get Profile Error: " + e.getMessage());
        }
        return null;
    }

    // Returns 1 on success, 0 when the account was not found, and -1 for duplicate username/email.
    public static int updateUserProfile(String currentUsername, String newUsername, String email) {
        String sql = "UPDATE users SET username = ?, email = ? WHERE username = ? COLLATE NOCASE";
        try (Connection conn = connect();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setString(1, newUsername);
            pstmt.setString(2, email == null || email.trim().isEmpty() ? null : email.trim().toLowerCase());
            pstmt.setString(3, currentUsername);
            int updated = pstmt.executeUpdate();

            // Sessions follow the user id, not the name, so revoke them under the NEW name.
            if (updated > 0) {
                deleteSessionsForUser(newUsername);
            }

            return updated > 0 ? 1 : 0;
        } catch (java.sql.SQLException e) {
            if (e.getMessage() != null && e.getMessage().toLowerCase().contains("unique")) {
                return -1;
            }
            System.out.println("Update Profile Error: " + e.getMessage());
            return 0;
        } catch (Exception e) {
            System.out.println("Update Profile Error: " + e.getMessage());
            return 0;
        }
    }

    public static boolean changeUserPassword(String username, String currentPassword, String newPassword) {
        String selectSql = "SELECT password FROM users WHERE username = ? COLLATE NOCASE";
        String updateSql = "UPDATE users SET password = ? WHERE username = ? COLLATE NOCASE";
        try (Connection conn = connect()) {
            try (PreparedStatement check = conn.prepareStatement(selectSql)) {
                check.setString(1, username);
                try (ResultSet rs = check.executeQuery()) {
                    if (!rs.next() || !verifyPassword(currentPassword, rs.getString("password"))) {
                        return false;
                    }
                }
            }
            try (PreparedStatement update = conn.prepareStatement(updateSql)) {
                update.setString(1, hashPassword(newPassword));
                update.setString(2, username);
                return update.executeUpdate() > 0;
            }
        } catch (Exception e) {
            System.out.println("Change Password Error: " + e.getMessage());
            return false;
        }
    }

// =========================================================
// INSERT MEDICINE
// =========================================================

public static boolean insertMedicine(long shopId, Medicine med) {

    String sql =
            "INSERT INTO medicines "
                    + "(id, name, batchNumber, category, "
                    + "quantity, price, manufacturer, disease, "
                    + "mfgDate, expiryDate, shop_id) "
                    + "VALUES(?,?,?,?,?,?,?,?,?,?,?)";

    try (Connection conn = connect();
         PreparedStatement pstmt =
                 conn.prepareStatement(sql)) {

        pstmt.setString(1, med.getId());
        pstmt.setString(2, med.getName());
        pstmt.setString(3, med.getBatchNumber());
        pstmt.setString(4, med.getCategory());
        pstmt.setInt(5, med.getQuantity());
        pstmt.setDouble(6, med.getPrice());
        pstmt.setString(7, med.getManufacturer());
        pstmt.setString(8, med.getDisease());
        pstmt.setString(9, med.getMfgDate().toString());
        pstmt.setString(10, med.getExpiryDate().toString());
        pstmt.setLong(11, shopId);

        pstmt.executeUpdate();

        return true;

    } catch (java.sql.SQLException e) {

        if (e.getMessage() != null
                && e.getMessage()
                .toLowerCase()
                .contains("unique")) {

            System.out.println(
                    "Medicine ID already exists: "
                            + med.getId());

        } else {

            System.out.println(
                    "Database Insert Error: "
                            + e.getMessage());
        }

        return false;

    } catch (Exception e) {

        System.out.println(
                "Database Insert Error: "
                        + e.getMessage());

        return false;
    }
}

    // =========================================================
    // DELETE MEDICINE
    // =========================================================

    public static boolean deleteMedicine(long shopId, String id) {

        String sql =
                "DELETE FROM medicines WHERE id = ? AND shop_id = " + shopId;

        try (Connection conn = connect();
             PreparedStatement pstmt =
                     conn.prepareStatement(sql)) {

            pstmt.setString(1, id);

            int rows = pstmt.executeUpdate();

            return rows > 0;

        } catch (Exception e) {

            System.out.println(
                    "Delete Error: "
                            + e.getMessage());

            return false;
        }
    }

    // =========================================================
    // GET MEDICINE DATA
    // =========================================================

    public static DefaultTableModel getMedicineData(
            long shopId,
            String searchText,
            String filterBy) {

        String[] columns = {
                "ID",
                "Name",
                "Batch",
                "Category",
                "Qty",
                "Price",
                "Company",
                "Disease",
                "Expiry",
                "GST",
                "HSN"
        };

        DefaultTableModel model =
                new DefaultTableModel(columns, 0);

        String dbColumn =
                getSafeColumn(filterBy);

        String sql;

        boolean hasSearch =
                searchText != null
                        && !searchText.trim().isEmpty();

        if (hasSearch) {

            sql =
                    "SELECT * FROM medicines "
                            + "WHERE shop_id = " + shopId
                            + " AND LOWER("
                            + dbColumn
                            + ") LIKE ? "
                            + "ORDER BY CASE WHEN LOWER("
                            + dbColumn
                            + ") LIKE ? THEN 1 ELSE 2 END, "
                            + dbColumn
                            + " ASC";

        } else {

            sql =
                    "SELECT * FROM medicines "
                            + "WHERE shop_id = " + shopId
                            + " ORDER BY name ASC";
        }

        try (Connection conn = connect();
             PreparedStatement pstmt =
                     conn.prepareStatement(sql)) {

            if (hasSearch) {

                String safeText =
                        searchText.trim().toLowerCase();

                pstmt.setString(
                        1,
                        "%" + safeText + "%");

                pstmt.setString(
                        2,
                        safeText + "%");
            }

            try (ResultSet rs =
                         pstmt.executeQuery()) {

                while (rs.next()) {

                    model.addRow(new Object[]{
                            rs.getString("id"),
                            rs.getString("name"),
                            rs.getString("batchNumber"),
                            rs.getString("category"),
                            rs.getInt("quantity"),
                            rs.getDouble("price"),
                            rs.getString("manufacturer"),
                            rs.getString("disease"),
                            rs.getString("expiryDate"),
                            rs.getDouble("gst_rate"),
                            rs.getString("hsn")
                    });
                }
            }

        } catch (Exception e) {

            System.out.println(
                    "Error fetching data: "
                            + e.getMessage());
        }

        return model;
    }


    // =========================================================
    // SAFE FILTER COLUMN
    // =========================================================

   private static String getSafeColumn(String filterBy) {

    if (filterBy == null) {
        return "name";
    }

    switch (filterBy) {

        case "Medicine ID":
            return "id";

        case "Disease":
            return "disease";

        case "Manufacturer":
            return "manufacturer";

        case "Category":
            return "category";

        case "Name":
        default:
            return "name";
    }
}

    // =========================================================
    // GET MEDICINE BY ID
    // =========================================================

    public static Object[] getMedicineById(
            long shopId,
            String id) {

        String sql =
                "SELECT * FROM medicines WHERE id = ? AND shop_id = " + shopId;

        try (Connection conn = connect();
             PreparedStatement pstmt =
                     conn.prepareStatement(sql)) {

            pstmt.setString(1, id);

            try (ResultSet rs =
                         pstmt.executeQuery()) {

                if (rs.next()) {

                    return new Object[]{
                            rs.getString("id"),
                            rs.getString("name"),
                            rs.getString("batchNumber"),
                            rs.getString("category"),
                            rs.getInt("quantity"),
                            rs.getDouble("price"),
                            rs.getString("manufacturer"),
                            rs.getString("disease"),
                            rs.getString("mfgDate"),
                            rs.getString("expiryDate"),
                            rs.getDouble("gst_rate"),
                            rs.getString("hsn")
                    };
                }
            }

        } catch (Exception e) {

            System.out.println(
                    "Get Medicine Error: "
                            + e.getMessage());
        }

        return null;
    }

    // =========================================================
    // UPDATE MEDICINE
    // =========================================================

    public static boolean updateMedicine(
            long shopId,
            String id,
            String name,
            String batchNumber,
            String category,
            int quantity,
            double price,
            String manufacturer,
            String disease,
            String mfgDate,
            String expiryDate) {

        String sql =
                "UPDATE medicines SET "
                        + "name = ?, "
                        + "batchNumber = ?, "
                        + "category = ?, "
                        + "quantity = ?, "
                        + "price = ?, "
                        + "manufacturer = ?, "
                        + "disease = ?, "
                        + "mfgDate = ?, "
                        + "expiryDate = ? "
                        + "WHERE id = ? AND shop_id = " + shopId;

        try (Connection conn = connect();
             PreparedStatement pstmt =
                     conn.prepareStatement(sql)) {

            pstmt.setString(1, name);
            pstmt.setString(2, batchNumber);
            pstmt.setString(3, category);
            pstmt.setInt(4, quantity);
            pstmt.setDouble(5, price);
            pstmt.setString(6, manufacturer);
            pstmt.setString(7, disease);
            pstmt.setString(8, mfgDate);
            pstmt.setString(9, expiryDate);
            pstmt.setString(10, id);

            return pstmt.executeUpdate() > 0;

        } catch (Exception e) {

            System.out.println(
                    "Update Medicine Error: "
                            + e.getMessage());

            return false;
        }
    }


    // =========================================================
    // GET MEDICINE STOCK AND PRICE
    // =========================================================

    public static Object[] getMedicineForSale(
            long shopId,
            String medicineId) {

        String sql =
                "SELECT id, name, quantity, price "
                        + "FROM medicines WHERE id = ? AND shop_id = " + shopId;

        try (Connection conn = connect();
             PreparedStatement pstmt =
                     conn.prepareStatement(sql)) {

            pstmt.setString(1, medicineId);

            try (ResultSet rs =
                         pstmt.executeQuery()) {

                if (rs.next()) {

                    return new Object[]{
                            rs.getString("id"),
                            rs.getString("name"),
                            rs.getInt("quantity"),
                            rs.getDouble("price")
                    };
                }
            }

        } catch (Exception e) {

            System.out.println(
                    "Sale Medicine Error: "
                            + e.getMessage());
        }

        return null;
    }

    // =========================================================
    // SELL MEDICINE AND REDUCE STOCK
    // =========================================================

    public static boolean sellMedicine(
            long shopId,
            String medicineId,
            String customerName,
            int saleQuantity,
            String saleDate) {

        Connection conn = null;

        try {

            conn = connect();

            if (conn == null) {
                return false;
            }

            conn.setAutoCommit(false);

            String selectSql =
                    "SELECT name, quantity, price "
                            + "FROM medicines WHERE id = ? AND shop_id = " + shopId;

            String medicineName;
            int availableQuantity;
            double unitPrice;

            try (PreparedStatement selectStmt =
                         conn.prepareStatement(selectSql)) {

                selectStmt.setString(1, medicineId);

                try (ResultSet rs =
                             selectStmt.executeQuery()) {

                    if (!rs.next()) {

                        conn.rollback();
                        return false;
                    }

                    medicineName =
                            rs.getString("name");

                    availableQuantity =
                            rs.getInt("quantity");

                    unitPrice =
                            rs.getDouble("price");
                }
            }

            if (saleQuantity <= 0
                    || saleQuantity > availableQuantity) {

                conn.rollback();
                return false;
            }

            double totalAmount =
                    saleQuantity * unitPrice;

            // INSERT SALE RECORD
            String insertSaleSql =
                    "INSERT INTO sales("
                            + "medicineId, medicineName, "
                            + "customerName, quantity, "
                            + "unitPrice, totalAmount, saleDate, shop_id"
                            + ") VALUES(?,?,?,?,?,?,?,?)";

            try (PreparedStatement saleStmt =
                         conn.prepareStatement(insertSaleSql)) {

                saleStmt.setString(1, medicineId);
                saleStmt.setString(2, medicineName);
                saleStmt.setString(3, customerName);
                saleStmt.setInt(4, saleQuantity);
                saleStmt.setDouble(5, unitPrice);
                saleStmt.setDouble(6, totalAmount);
                saleStmt.setString(7, saleDate);
                saleStmt.setLong(8, shopId);

                saleStmt.executeUpdate();
            }

            // REDUCE MEDICINE STOCK
            String updateStockSql =
                    "UPDATE medicines "
                            + "SET quantity = quantity - ? "
                            + "WHERE id = ? AND shop_id = " + shopId
                            + " AND quantity >= ?";

            try (PreparedStatement stockStmt =
                         conn.prepareStatement(updateStockSql)) {

                stockStmt.setInt(1, saleQuantity);
                stockStmt.setString(2, medicineId);
                stockStmt.setInt(3, saleQuantity);

                // Guards against overselling when two sales happen at once.
                if (stockStmt.executeUpdate() != 1) {

                    conn.rollback();
                    return false;
                }
            }

            conn.commit();

            return true;

        } catch (Exception e) {

            try {

                if (conn != null) {
                    conn.rollback();
                }

            } catch (Exception rollbackError) {

                System.out.println(
                        "Rollback Error: "
                                + rollbackError.getMessage());
            }

            System.out.println(
                    "Sale Error: "
                            + e.getMessage());

            return false;

        } finally {

            try {

                if (conn != null) {

                    conn.setAutoCommit(true);
                    conn.close();
                }

            } catch (Exception closeError) {

                System.out.println(
                        "Connection Close Error: "
                                + closeError.getMessage());
            }
        }
    }

    // =========================================================
    // GET SALES DATA
    // =========================================================

    public static DefaultTableModel getSalesData(long shopId) {

        return getSalesData(shopId, "", "Medicine ID");
    }

    public static DefaultTableModel getSalesData(
            long shopId,
            String searchText,
            String filterBy) {

        String[] columns = {
                "Sale ID",
                "Medicine ID",
                "Medicine Name",
                "Customer",
                "Quantity",
                "Unit Price",
                "Total Amount",
                "Sale Date"
        };

        DefaultTableModel model =
                new DefaultTableModel(columns, 0);

        String dbColumn;

        if ("Medicine Name".equals(filterBy)) {

            dbColumn = "medicineName";

        } else if ("Customer".equals(filterBy)) {

            dbColumn = "customerName";

        } else {

            dbColumn = "medicineId";
        }

        boolean hasSearch =
                searchText != null
                        && !searchText.trim().isEmpty();

        String sql =
                "SELECT * FROM sales WHERE shop_id = " + shopId;

        if (hasSearch) {

            sql +=
                    " AND LOWER("
                            + dbColumn
                            + ") LIKE ? "
                            + "ORDER BY CASE WHEN LOWER("
                            + dbColumn
                            + ") LIKE ? THEN 1 ELSE 2 END, "
                            + "saleId DESC";

        } else {

            sql += " ORDER BY saleId DESC";
        }

        try (Connection conn = connect();
             PreparedStatement pstmt =
                     conn.prepareStatement(sql)) {

            if (hasSearch) {

                String safeText =
                        searchText.trim().toLowerCase();

                pstmt.setString(
                        1,
                        "%" + safeText + "%");

                pstmt.setString(
                        2,
                        safeText + "%");
            }

            try (ResultSet rs =
                         pstmt.executeQuery()) {

                while (rs.next()) {

                    model.addRow(new Object[]{
                            rs.getInt("saleId"),
                            rs.getString("medicineId"),
                            rs.getString("medicineName"),
                            rs.getString("customerName"),
                            rs.getInt("quantity"),
                            rs.getDouble("unitPrice"),
                            rs.getDouble("totalAmount"),
                            rs.getString("saleDate")
                    });
                }
            }

        } catch (Exception e) {

            System.out.println(
                    "Sales Data Error: "
                            + e.getMessage());
        }

        return model;
    }

}
