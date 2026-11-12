import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * Customers and their udhaar (credit) account.
 *
 * Every change to what a customer owes is one line in customer_ledger:
 *   sale     +  a bill sold on credit
 *   payment  -  money received later (or part-paid on the spot)
 *   return   -  goods returned from a credit bill (credit note, "adjust against account")
 *   cancel   +/- a credit bill that was cancelled is undone
 * A customer's balance is the sum of their lines, so it can never drift.
 * A positive balance means the customer owes the shop. Negative means advance.
 */
public final class Customers {

    private Customers() {}

    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final BigDecimal MAX_AMOUNT = new BigDecimal("10000000");

    public static final class Outcome {
        public final boolean ok;
        public final int status;
        public final String message;
        public final long id;
        public final BigDecimal balance;

        Outcome(boolean ok, int status, String message, long id, BigDecimal balance) {
            this.ok = ok;
            this.status = status;
            this.message = message;
            this.id = id;
            this.balance = balance;
        }
    }

    private static Outcome fail(int status, String message) {
        return new Outcome(false, status, message, 0, null);
    }

    /** A customer as read for billing. */
    public static final class Row {
        public long id;
        public String name = "", phone = "", gstin = "";
        public BigDecimal creditLimit = BigDecimal.ZERO;
    }

    // ---------------------------------------------------------
    // Small helpers (also used by Billing and Returns inside their transactions)
    // ---------------------------------------------------------

    static BigDecimal money(double value) {
        return BigDecimal.valueOf(value).setScale(2, RoundingMode.HALF_UP);
    }

    /** Keeps digits and a leading +, so the same number is always stored the same way. */
    public static String normalizePhone(String phone) {
        return phone == null ? "" : phone.replaceAll("[\\s\\-()]", "");
    }

    public static Row load(Connection c, long shopId, long customerId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT id, name, phone, gstin, credit_limit FROM customers WHERE id = ? AND shop_id = ?")) {
            ps.setLong(1, customerId);
            ps.setLong(2, shopId);

            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return null;
                Row row = new Row();
                row.id = rs.getLong(1);
                row.name = rs.getString(2);
                row.phone = rs.getString(3);
                row.gstin = rs.getString(4);
                row.creditLimit = money(rs.getDouble(5));
                return row;
            }
        }
    }

    public static BigDecimal balance(Connection c, long customerId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT COALESCE(SUM(amount), 0) FROM customer_ledger WHERE customer_id = ?")) {
            ps.setLong(1, customerId);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return money(rs.getDouble(1));
            }
        }
    }

    public static void addEntry(Connection c, long shopId, long customerId, String kind, BigDecimal amount,
                                String mode, String note, Long invoiceId, Long creditNoteId,
                                String user, String stamp) throws SQLException {

        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO customer_ledger(shop_id, customer_id, entry_date, kind, amount, mode, note, "
                        + "invoice_id, credit_note_id, created_by) VALUES(?,?,?,?,?,?,?,?,?,?)")) {

            ps.setLong(1, shopId);
            ps.setLong(2, customerId);
            ps.setString(3, stamp);
            ps.setString(4, kind);
            ps.setDouble(5, amount.doubleValue());
            ps.setString(6, mode == null ? "" : mode);
            ps.setString(7, note == null ? "" : note);

            if (invoiceId == null) ps.setNull(8, java.sql.Types.INTEGER);
            else ps.setLong(8, invoiceId);

            if (creditNoteId == null) ps.setNull(9, java.sql.Types.INTEGER);
            else ps.setLong(9, creditNoteId);

            ps.setString(10, user == null ? "" : user);
            ps.executeUpdate();
        }
    }

    // ---------------------------------------------------------
    // Create / update / delete
    // ---------------------------------------------------------

    public static Outcome create(long shopId, String user, String name, String phone, String gstin,
                                 String address, String notes, BigDecimal creditLimit) {

        try (Connection c = DatabaseHelper.connect();
             PreparedStatement ps = c.prepareStatement(
                     "INSERT INTO customers(shop_id, name, phone, gstin, address, notes, credit_limit, "
                             + "created_at, created_by) VALUES(?,?,?,?,?,?,?,?,?)",
                     Statement.RETURN_GENERATED_KEYS)) {

            ps.setLong(1, shopId);
            ps.setString(2, name);
            ps.setString(3, normalizePhone(phone));
            ps.setString(4, gstin);
            ps.setString(5, address);
            ps.setString(6, notes);
            ps.setDouble(7, creditLimit.doubleValue());
            ps.setString(8, LocalDateTime.now().format(STAMP));
            ps.setString(9, user);
            ps.executeUpdate();

            try (ResultSet keys = ps.getGeneratedKeys()) {
                keys.next();
                return new Outcome(true, 201, "Customer added", keys.getLong(1), BigDecimal.ZERO);
            }

        } catch (SQLException e) {
            if (e.getMessage() != null && e.getMessage().toLowerCase().contains("unique")) {
                return fail(409, "A customer with this phone number already exists");
            }
            System.out.println("Create customer error: " + e.getMessage());
            return fail(500, "The customer could not be saved");
        }
    }

    /** creditLimit null = leave the limit as it is (only the owner may change it). */
    public static Outcome update(long shopId, long id, String name, String phone, String gstin,
                                 String address, String notes, BigDecimal creditLimit) {

        String sql = "UPDATE customers SET name = ?, phone = ?, gstin = ?, address = ?, notes = ?"
                + (creditLimit != null ? ", credit_limit = ?" : "")
                + " WHERE id = ? AND shop_id = ?";

        try (Connection c = DatabaseHelper.connect();
             PreparedStatement ps = c.prepareStatement(sql)) {

            int i = 1;
            ps.setString(i++, name);
            ps.setString(i++, normalizePhone(phone));
            ps.setString(i++, gstin);
            ps.setString(i++, address);
            ps.setString(i++, notes);
            if (creditLimit != null) ps.setDouble(i++, creditLimit.doubleValue());
            ps.setLong(i++, id);
            ps.setLong(i, shopId);

            if (ps.executeUpdate() != 1) {
                return fail(404, "Customer not found");
            }

            return new Outcome(true, 200, "Customer updated", id, balance(c, id));

        } catch (SQLException e) {
            if (e.getMessage() != null && e.getMessage().toLowerCase().contains("unique")) {
                return fail(409, "Another customer already has this phone number");
            }
            System.out.println("Update customer error: " + e.getMessage());
            return fail(500, "The customer could not be saved");
        }
    }

    public static Outcome delete(long shopId, long id) {

        try (Connection c = DatabaseHelper.connect()) {

            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT (SELECT COUNT(*) FROM invoices WHERE customer_id = ? AND shop_id = ?), "
                            + "(SELECT COUNT(*) FROM customer_ledger WHERE customer_id = ? AND shop_id = ?)")) {

                ps.setLong(1, id);
                ps.setLong(2, shopId);
                ps.setLong(3, id);
                ps.setLong(4, shopId);

                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    if (rs.getInt(1) > 0 || rs.getInt(2) > 0) {
                        return fail(409, "This customer has bills or payments on record, so they cannot be deleted");
                    }
                }
            }

            try (PreparedStatement ps = c.prepareStatement("DELETE FROM customers WHERE id = ? AND shop_id = ?")) {
                ps.setLong(1, id);
                ps.setLong(2, shopId);
                if (ps.executeUpdate() != 1) return fail(404, "Customer not found");
            }

            return new Outcome(true, 200, "Customer deleted", id, BigDecimal.ZERO);

        } catch (SQLException e) {
            System.out.println("Delete customer error: " + e.getMessage());
            return fail(500, "The customer could not be deleted");
        }
    }

    // ---------------------------------------------------------
    // Money received from a customer
    // ---------------------------------------------------------

    public static Outcome receivePayment(long shopId, String user, long customerId, BigDecimal amount,
                                         String mode, String note) {

        if (amount == null || amount.compareTo(new BigDecimal("0.01")) < 0 || amount.compareTo(MAX_AMOUNT) > 0) {
            return fail(400, "Enter an amount between 0.01 and 1,00,00,000");
        }

        if (amount.scale() > 2) {
            return fail(400, "The amount can have at most 2 decimal places");
        }

        if (!"cash".equals(mode) && !"upi".equals(mode) && !"card".equals(mode)) {
            return fail(400, "Payment mode must be cash, upi or card");
        }

        Connection c = null;

        try {

            c = DatabaseHelper.connect();
            c.setAutoCommit(false);

            if (load(c, shopId, customerId) == null) {
                c.rollback();
                return fail(404, "Customer not found");
            }

            addEntry(c, shopId, customerId, "payment", amount.negate(), mode, note, null, null, user,
                    LocalDateTime.now().format(STAMP));

            BigDecimal balance = balance(c, customerId);
            c.commit();

            return new Outcome(true, 201, "Payment recorded", customerId, balance);

        } catch (SQLException e) {

            try {
                if (c != null) c.rollback();
            } catch (SQLException ignored) {
                // nothing more to do
            }

            System.out.println("Receive payment error: " + e.getMessage());
            return fail(500, "The payment could not be saved. Nothing was changed.");

        } finally {

            try {
                if (c != null) {
                    c.setAutoCommit(true);
                    c.close();
                }
            } catch (SQLException ignored) {
                // nothing more to do
            }
        }
    }

    // ---------------------------------------------------------
    // Reading (JSON text)
    // ---------------------------------------------------------

    private static String m(BigDecimal value) {
        return Json.money(value);
    }

    /** Customers with their balance, optionally only those who owe money. */
    public static String listJson(long shopId, String search, boolean duesOnly, int limit, int offset) {

        boolean hasSearch = search != null && !search.trim().isEmpty();

        String sql = "SELECT * FROM (SELECT c.id, c.name, c.phone, c.gstin, c.credit_limit, "
                + "COALESCE((SELECT SUM(l.amount) FROM customer_ledger l WHERE l.customer_id = c.id), 0) AS balance, "
                + "(SELECT MAX(l.entry_date) FROM customer_ledger l WHERE l.customer_id = c.id) AS last_entry "
                + "FROM customers c WHERE c.shop_id = ? "
                + (hasSearch ? "AND (LOWER(c.name) LIKE ? OR c.phone LIKE ?) " : "")
                + ") " + (duesOnly ? "WHERE balance > 0.004 " : "")
                + "ORDER BY balance DESC, name COLLATE NOCASE LIMIT ? OFFSET ?";

        try (Connection c = DatabaseHelper.connect()) {

            StringBuilder json = new StringBuilder("{\"success\":true");

            // Whole-shop numbers (not affected by search or paging)
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT (SELECT COUNT(*) FROM customers WHERE shop_id = ?), "
                            + "(SELECT COUNT(*) FROM (SELECT customer_id FROM customer_ledger WHERE shop_id = ? "
                            + "GROUP BY customer_id HAVING SUM(amount) > 0.004)), "
                            + "COALESCE((SELECT SUM(b) FROM (SELECT SUM(amount) AS b FROM customer_ledger WHERE shop_id = ? "
                            + "GROUP BY customer_id HAVING SUM(amount) > 0.004)), 0)")) {

                ps.setLong(1, shopId);
                ps.setLong(2, shopId);
                ps.setLong(3, shopId);

                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    json.append(",\"summary\":{\"customers\":").append(rs.getInt(1))
                            .append(",\"withDues\":").append(rs.getInt(2))
                            .append(",\"totalDues\":").append(m(money(rs.getDouble(3)))).append("}");
                }
            }

            json.append(",\"customers\":[");

            try (PreparedStatement ps = c.prepareStatement(sql)) {

                int i = 1;
                ps.setLong(i++, shopId);

                if (hasSearch) {
                    ps.setString(i++, "%" + search.trim().toLowerCase() + "%");
                    ps.setString(i++, "%" + normalizePhone(search.trim()) + "%");
                }

                ps.setInt(i++, Math.max(1, Math.min(limit, 500)));
                ps.setInt(i, Math.max(0, offset));

                try (ResultSet rs = ps.executeQuery()) {

                    boolean first = true;

                    while (rs.next()) {

                        if (!first) json.append(',');
                        first = false;

                        String last = rs.getString("last_entry");

                        json.append("{\"id\":").append(rs.getLong("id"))
                                .append(",\"name\":").append(Json.quote(rs.getString("name")))
                                .append(",\"phone\":").append(Json.quote(rs.getString("phone")))
                                .append(",\"gstin\":").append(Json.quote(rs.getString("gstin")))
                                .append(",\"creditLimit\":").append(m(money(rs.getDouble("credit_limit"))))
                                .append(",\"balance\":").append(m(money(rs.getDouble("balance"))))
                                .append(",\"lastActivity\":").append(last == null ? "null" : Json.quote(last))
                                .append("}");
                    }
                }
            }

            return json.append("]}").toString();

        } catch (SQLException e) {
            System.out.println("List customers error: " + e.getMessage());
            return "{\"success\":false,\"error\":\"Could not load customers\"}";
        }
    }

    /** One customer with the account history (newest first, with the balance after each line). */
    public static String detailJson(long shopId, long id) {

        try (Connection c = DatabaseHelper.connect()) {

            StringBuilder json = new StringBuilder();

            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT id, name, phone, gstin, address, notes, credit_limit, created_at "
                            + "FROM customers WHERE id = ? AND shop_id = ?")) {

                ps.setLong(1, id);
                ps.setLong(2, shopId);

                try (ResultSet rs = ps.executeQuery()) {

                    if (!rs.next()) {
                        return null;
                    }

                    json.append("{\"success\":true,\"customer\":{\"id\":").append(rs.getLong(1))
                            .append(",\"name\":").append(Json.quote(rs.getString(2)))
                            .append(",\"phone\":").append(Json.quote(rs.getString(3)))
                            .append(",\"gstin\":").append(Json.quote(rs.getString(4)))
                            .append(",\"address\":").append(Json.quote(rs.getString(5)))
                            .append(",\"notes\":").append(Json.quote(rs.getString(6)))
                            .append(",\"creditLimit\":").append(m(money(rs.getDouble(7))))
                            .append(",\"createdAt\":").append(Json.quote(rs.getString(8)))
                            .append(",\"balance\":").append(m(balance(c, id)))
                            .append("}");
                }
            }

            // Ledger: oldest first to build the running balance, then shown newest first.
            List<String> lines = new ArrayList<>();
            BigDecimal running = BigDecimal.ZERO;

            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT l.id, l.entry_date, l.kind, l.amount, l.mode, l.note, i.invoice_no, n.note_no, l.created_by "
                            + "FROM customer_ledger l LEFT JOIN invoices i ON i.id = l.invoice_id "
                            + "LEFT JOIN credit_notes n ON n.id = l.credit_note_id "
                            + "WHERE l.customer_id = ? AND l.shop_id = ? ORDER BY l.id ASC")) {

                ps.setLong(1, id);
                ps.setLong(2, shopId);

                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        BigDecimal amount = money(rs.getDouble(4));
                        running = running.add(amount);
                        String invoiceNo = rs.getString(7);
                        String noteNo = rs.getString(8);

                        lines.add("{\"id\":" + rs.getLong(1)
                                + ",\"date\":" + Json.quote(rs.getString(2))
                                + ",\"kind\":" + Json.quote(rs.getString(3))
                                + ",\"amount\":" + m(amount)
                                + ",\"balanceAfter\":" + m(running)
                                + ",\"mode\":" + Json.quote(rs.getString(5))
                                + ",\"note\":" + Json.quote(rs.getString(6))
                                + ",\"invoiceNo\":" + (invoiceNo == null ? "null" : Json.quote(invoiceNo))
                                + ",\"creditNoteNo\":" + (noteNo == null ? "null" : Json.quote(noteNo))
                                + ",\"by\":" + Json.quote(rs.getString(9)) + "}");
                    }
                }
            }

            json.append(",\"ledger\":[");
            int shown = 0;
            for (int i = lines.size() - 1; i >= 0 && shown < 300; i--, shown++) {
                if (shown > 0) json.append(',');
                json.append(lines.get(i));
            }

            // Recent bills of this customer
            json.append("],\"invoices\":[");

            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT id, invoice_no, invoice_date, total, payment_mode, status, amount_paid FROM invoices "
                            + "WHERE customer_id = ? AND shop_id = ? ORDER BY id DESC LIMIT 30")) {

                ps.setLong(1, id);
                ps.setLong(2, shopId);

                try (ResultSet rs = ps.executeQuery()) {
                    boolean first = true;
                    while (rs.next()) {
                        if (!first) json.append(',');
                        first = false;
                        json.append("{\"id\":").append(rs.getLong(1))
                                .append(",\"invoiceNo\":").append(Json.quote(rs.getString(2)))
                                .append(",\"date\":").append(Json.quote(rs.getString(3)))
                                .append(",\"total\":").append(m(money(rs.getDouble(4))))
                                .append(",\"paymentMode\":").append(Json.quote(rs.getString(5)))
                                .append(",\"status\":").append(Json.quote(rs.getString(6)))
                                .append(",\"amountPaid\":").append(m(money(rs.getDouble(7))))
                                .append("}");
                    }
                }
            }

            return json.append("]}").toString();

        } catch (SQLException e) {
            System.out.println("Customer detail error: " + e.getMessage());
            return null;
        }
    }
}
