import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Suppliers, stock coming in, and what the shop owes.
 *
 * Rules:
 *  - The whole purchase is one transaction: stock, ledger and the record succeed or fail together.
 *  - Stock is kept per batch. A new batch of a medicine becomes its own row, so its
 *    expiry date stays correct and it is billed separately.
 *  - cost_price is the rate before GST (input GST is shown apart for the accountant).
 *    Free units lower the average cost, because they were not paid for.
 *  - The supplier ledger is append-only. Balance = sum of the entries. Positive = shop owes.
 */
public final class Purchases {

    private Purchases() {}

    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final BigDecimal MAX_AMOUNT = new BigDecimal("100000000");
    private static final Set<String> PAY_MODES = new HashSet<>(List.of("cash", "upi", "card", "bank"));

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

    private static BigDecimal money(double value) {
        return BigDecimal.valueOf(value).setScale(2, RoundingMode.HALF_UP);
    }

    private static String m(BigDecimal value) {
        return Json.money(value);
    }

    private static String now() {
        return LocalDateTime.now().format(STAMP);
    }

    // ---------------------------------------------------------
    // Suppliers
    // ---------------------------------------------------------

    public static Outcome createSupplier(long shopId, String user, String name, String phone, String gstin,
                                         String license, String address, String notes) {

        try (Connection c = DatabaseHelper.connect();
             PreparedStatement ps = c.prepareStatement(
                     "INSERT INTO suppliers(shop_id, name, phone, gstin, drug_license, address, notes, created_at, created_by) "
                             + "VALUES(?,?,?,?,?,?,?,?,?)", Statement.RETURN_GENERATED_KEYS)) {

            ps.setLong(1, shopId);
            ps.setString(2, name);
            ps.setString(3, Customers.normalizePhone(phone));
            ps.setString(4, gstin);
            ps.setString(5, license);
            ps.setString(6, address);
            ps.setString(7, notes);
            ps.setString(8, now());
            ps.setString(9, user);
            ps.executeUpdate();

            try (ResultSet keys = ps.getGeneratedKeys()) {
                keys.next();
                return new Outcome(true, 201, "Supplier added", keys.getLong(1), BigDecimal.ZERO);
            }

        } catch (SQLException e) {
            if (e.getMessage() != null && e.getMessage().toLowerCase(Locale.ROOT).contains("unique")) {
                return fail(409, "A supplier with this phone number already exists");
            }
            System.out.println("Create supplier error: " + e.getMessage());
            return fail(500, "The supplier could not be saved");
        }
    }

    public static Outcome updateSupplier(long shopId, long id, String name, String phone, String gstin,
                                         String license, String address, String notes) {

        try (Connection c = DatabaseHelper.connect();
             PreparedStatement ps = c.prepareStatement(
                     "UPDATE suppliers SET name = ?, phone = ?, gstin = ?, drug_license = ?, address = ?, notes = ? "
                             + "WHERE id = ? AND shop_id = ?")) {

            ps.setString(1, name);
            ps.setString(2, Customers.normalizePhone(phone));
            ps.setString(3, gstin);
            ps.setString(4, license);
            ps.setString(5, address);
            ps.setString(6, notes);
            ps.setLong(7, id);
            ps.setLong(8, shopId);

            if (ps.executeUpdate() != 1) {
                return fail(404, "Supplier not found");
            }

            return new Outcome(true, 200, "Supplier updated", id, balance(c, id));

        } catch (SQLException e) {
            if (e.getMessage() != null && e.getMessage().toLowerCase(Locale.ROOT).contains("unique")) {
                return fail(409, "A supplier with this phone number already exists");
            }
            System.out.println("Update supplier error: " + e.getMessage());
            return fail(500, "The supplier could not be saved");
        }
    }

    public static Outcome deleteSupplier(long shopId, long id) {

        try (Connection c = DatabaseHelper.connect()) {

            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT (SELECT COUNT(*) FROM purchases WHERE supplier_id = ? AND shop_id = ?) "
                            + "+ (SELECT COUNT(*) FROM supplier_ledger WHERE supplier_id = ? AND shop_id = ?)")) {
                ps.setLong(1, id);
                ps.setLong(2, shopId);
                ps.setLong(3, id);
                ps.setLong(4, shopId);
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    if (rs.getInt(1) > 0) {
                        return fail(409, "This supplier has purchase history, so it cannot be deleted");
                    }
                }
            }

            try (PreparedStatement ps = c.prepareStatement("DELETE FROM suppliers WHERE id = ? AND shop_id = ?")) {
                ps.setLong(1, id);
                ps.setLong(2, shopId);
                if (ps.executeUpdate() != 1) {
                    return fail(404, "Supplier not found");
                }
            }

            return new Outcome(true, 200, "Supplier deleted", id, BigDecimal.ZERO);

        } catch (SQLException e) {
            System.out.println("Delete supplier error: " + e.getMessage());
            return fail(500, "The supplier could not be deleted");
        }
    }

    private static BigDecimal balance(Connection c, long supplierId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT COALESCE(SUM(amount), 0) FROM supplier_ledger WHERE supplier_id = ?")) {
            ps.setLong(1, supplierId);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return money(rs.getDouble(1));
            }
        }
    }

    private static void addEntry(Connection c, long shopId, long supplierId, String kind, BigDecimal amount,
                                 String mode, String note, Long purchaseId, String user, String stamp)
            throws SQLException {

        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO supplier_ledger(shop_id, supplier_id, entry_date, kind, amount, mode, note, purchase_id, created_by) "
                        + "VALUES(?,?,?,?,?,?,?,?,?)")) {

            ps.setLong(1, shopId);
            ps.setLong(2, supplierId);
            ps.setString(3, stamp);
            ps.setString(4, kind);
            ps.setDouble(5, amount.doubleValue());
            ps.setString(6, mode == null ? "" : mode);
            ps.setString(7, note == null ? "" : note);

            if (purchaseId == null) ps.setNull(8, java.sql.Types.INTEGER);
            else ps.setLong(8, purchaseId);

            ps.setString(9, user);
            ps.executeUpdate();
        }
    }

    public static Outcome paySupplier(long shopId, String user, long supplierId, BigDecimal amount,
                                      String mode, String note) {

        if (amount == null || amount.compareTo(new BigDecimal("0.01")) < 0 || amount.compareTo(MAX_AMOUNT) > 0) {
            return fail(400, "Enter an amount between 0.01 and 10,00,00,000");
        }

        if (amount.stripTrailingZeros().scale() > 2) {
            return fail(400, "The amount can have at most 2 decimals");
        }

        if (!PAY_MODES.contains(mode)) {
            return fail(400, "Payment mode must be cash, upi, card or bank");
        }

        Connection c = null;

        try {
            c = DatabaseHelper.connect();
            c.setAutoCommit(false);

            String name = supplierName(c, shopId, supplierId);

            if (name == null) {
                c.rollback();
                return fail(404, "Supplier not found");
            }

            addEntry(c, shopId, supplierId, "payment", amount.negate(), mode, note, null, user, now());
            BigDecimal left = balance(c, supplierId);
            c.commit();

            return new Outcome(true, 201, "Payment recorded", supplierId, left);

        } catch (Exception e) {
            rollback(c);
            System.out.println("Supplier payment error: " + e.getMessage());
            return fail(500, "The payment could not be saved. Nothing was changed.");
        } finally {
            close(c);
        }
    }

    private static String supplierName(Connection c, long shopId, long supplierId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT name FROM suppliers WHERE id = ? AND shop_id = ?")) {
            ps.setLong(1, supplierId);
            ps.setLong(2, shopId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        }
    }

    private static void rollback(Connection c) {
        try {
            if (c != null) c.rollback();
        } catch (Exception ignored) {
            // nothing more to do
        }
    }

    private static void close(Connection c) {
        try {
            if (c != null) {
                c.setAutoCommit(true);
                c.close();
            }
        } catch (Exception ignored) {
            // nothing more to do
        }
    }

    public static String suppliersJson(long shopId, String search, boolean duesOnly, int limit, int offset) {

        boolean hasSearch = search != null && !search.trim().isEmpty();

        String sql = "SELECT * FROM (SELECT s.id, s.name, s.phone, s.gstin, "
                + "COALESCE((SELECT SUM(l.amount) FROM supplier_ledger l WHERE l.supplier_id = s.id), 0) AS balance, "
                + "(SELECT MAX(l.entry_date) FROM supplier_ledger l WHERE l.supplier_id = s.id) AS last_entry "
                + "FROM suppliers s WHERE s.shop_id = ? "
                + (hasSearch ? "AND (LOWER(s.name) LIKE ? OR s.phone LIKE ?) " : "")
                + ") " + (duesOnly ? "WHERE balance > 0.004 " : "")
                + "ORDER BY balance DESC, name COLLATE NOCASE LIMIT ? OFFSET ?";

        try (Connection c = DatabaseHelper.connect()) {

            StringBuilder json = new StringBuilder("{\"success\":true");

            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT (SELECT COUNT(*) FROM suppliers WHERE shop_id = ?), "
                            + "(SELECT COUNT(*) FROM (SELECT supplier_id FROM supplier_ledger WHERE shop_id = ? "
                            + "GROUP BY supplier_id HAVING SUM(amount) > 0.004)), "
                            + "COALESCE((SELECT SUM(b) FROM (SELECT SUM(amount) AS b FROM supplier_ledger WHERE shop_id = ? "
                            + "GROUP BY supplier_id HAVING SUM(amount) > 0.004)), 0)")) {
                ps.setLong(1, shopId);
                ps.setLong(2, shopId);
                ps.setLong(3, shopId);
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    json.append(",\"summary\":{\"suppliers\":").append(rs.getInt(1))
                            .append(",\"withDues\":").append(rs.getInt(2))
                            .append(",\"totalDues\":").append(m(money(rs.getDouble(3)))).append("}");
                }
            }

            json.append(",\"suppliers\":[");

            try (PreparedStatement ps = c.prepareStatement(sql)) {

                int i = 1;
                ps.setLong(i++, shopId);

                if (hasSearch) {
                    ps.setString(i++, "%" + search.trim().toLowerCase(Locale.ROOT) + "%");
                    ps.setString(i++, "%" + Customers.normalizePhone(search.trim()) + "%");
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
                                .append(",\"balance\":").append(m(money(rs.getDouble("balance"))))
                                .append(",\"lastActivity\":").append(last == null ? "null" : Json.quote(last))
                                .append("}");
                    }
                }
            }

            return json.append("]}").toString();

        } catch (SQLException e) {
            System.out.println("List suppliers error: " + e.getMessage());
            return "{\"success\":false,\"error\":\"Could not load suppliers\"}";
        }
    }

    public static String supplierDetailJson(long shopId, long id) {

        try (Connection c = DatabaseHelper.connect()) {

            StringBuilder json = new StringBuilder();

            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT id, name, phone, gstin, drug_license, address, notes FROM suppliers WHERE id = ? AND shop_id = ?")) {
                ps.setLong(1, id);
                ps.setLong(2, shopId);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) {
                        return null;
                    }
                    json.append("{\"success\":true,\"supplier\":{\"id\":").append(rs.getLong(1))
                            .append(",\"name\":").append(Json.quote(rs.getString(2)))
                            .append(",\"phone\":").append(Json.quote(rs.getString(3)))
                            .append(",\"gstin\":").append(Json.quote(rs.getString(4)))
                            .append(",\"drugLicense\":").append(Json.quote(rs.getString(5)))
                            .append(",\"address\":").append(Json.quote(rs.getString(6)))
                            .append(",\"notes\":").append(Json.quote(rs.getString(7)))
                            .append(",\"balance\":").append(m(balance(c, id)))
                            .append("}");
                }
            }

            List<String> lines = new ArrayList<>();
            BigDecimal running = BigDecimal.ZERO;

            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT l.id, l.entry_date, l.kind, l.amount, l.mode, l.note, p.purchase_no, p.bill_no, l.created_by "
                            + "FROM supplier_ledger l LEFT JOIN purchases p ON p.id = l.purchase_id "
                            + "WHERE l.supplier_id = ? AND l.shop_id = ? ORDER BY l.id ASC")) {
                ps.setLong(1, id);
                ps.setLong(2, shopId);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        BigDecimal amount = money(rs.getDouble(4));
                        running = running.add(amount);
                        String purchaseNo = rs.getString(7);
                        lines.add("{\"id\":" + rs.getLong(1)
                                + ",\"date\":" + Json.quote(rs.getString(2))
                                + ",\"kind\":" + Json.quote(rs.getString(3))
                                + ",\"amount\":" + m(amount)
                                + ",\"balanceAfter\":" + m(running)
                                + ",\"mode\":" + Json.quote(rs.getString(5))
                                + ",\"note\":" + Json.quote(rs.getString(6))
                                + ",\"purchaseNo\":" + (purchaseNo == null ? "null" : Json.quote(purchaseNo))
                                + ",\"billNo\":" + Json.quote(rs.getString(8))
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

            return json.append("]}").toString();

        } catch (SQLException e) {
            System.out.println("Supplier detail error: " + e.getMessage());
            return null;
        }
    }

    // ---------------------------------------------------------
    // Stock in
    // ---------------------------------------------------------

    public static final class ItemRequest {
        public String medicineId = "", name = "", batch = "", expiry = "", mfgDate = "";
        public String hsn = "", category = "", manufacturer = "";
        public int quantity, freeQty;
        public BigDecimal cost, mrp, gstRate;
    }

    private static final class Line {
        ItemRequest in;
        String medicineId, name;
        BigDecimal taxable, cgst, sgst, total;
    }

    private static boolean validDay(String text) {
        return ReportService.validDate(text);
    }

    private static String sanitize(String text, int max) {
        String clean = text == null ? "" : text.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9-]", "");
        return clean.length() > max ? clean.substring(0, max) : clean;
    }

    private static boolean medicineExists(Connection c, long shopId, String id) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT 1 FROM medicines WHERE id = ? AND shop_id = ?")) {
            ps.setString(1, id);
            ps.setLong(2, shopId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    private static String freeMedicineId(Connection c, long shopId, String base) throws SQLException {
        String candidate = base;
        int n = 2;
        while (medicineExists(c, shopId, candidate)) {
            candidate = base + "-" + (n++);
        }
        return candidate;
    }

    private static String nextGeneratedId(Connection c, long shopId) throws SQLException {
        long start;
        try (PreparedStatement ps = c.prepareStatement("SELECT COUNT(*) FROM medicines WHERE shop_id = ?")) {
            ps.setLong(1, shopId);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                start = rs.getLong(1) + 1;
            }
        }
        String candidate = String.format("MED%05d", start);
        while (medicineExists(c, shopId, candidate)) {
            candidate = String.format("MED%05d", ++start);
        }
        return candidate;
    }

    /** Adds the units to the right batch row, creating it when needed. Returns the row's id. */
    private static String applyStock(Connection c, long shopId, ItemRequest item) throws SQLException {

        String wantedId = item.medicineId == null ? "" : item.medicineId.trim();
        String name = item.name == null ? "" : item.name.trim();
        String batch = item.batch.trim();

        String rowId = null, baseName = name, baseCategory = item.category, baseMaker = item.manufacturer, baseDisease = "";
        String baseIdForBatch = null;
        int oldQty = 0;
        double oldCost = 0;

        if (!wantedId.isEmpty()) {

            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT name, batchNumber, quantity, cost_price, category, manufacturer, disease "
                            + "FROM medicines WHERE id = ? AND shop_id = ?")) {

                ps.setString(1, wantedId);
                ps.setLong(2, shopId);

                try (ResultSet rs = ps.executeQuery()) {

                    if (!rs.next()) {
                        throw new MissingMedicine(wantedId);
                    }

                    baseName = rs.getString(1);
                    baseCategory = rs.getString(5);
                    baseMaker = rs.getString(6);
                    baseDisease = rs.getString(7);

                    if (batch.equalsIgnoreCase(rs.getString(2))) {
                        rowId = wantedId;
                        oldQty = rs.getInt(3);
                        oldCost = rs.getDouble(4);
                    } else {
                        baseIdForBatch = wantedId;
                    }
                }
            }
        }

        // Same medicine + same batch already on the shelf (typed by name, or a sibling batch row).
        if (rowId == null) {

            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT id, quantity, cost_price FROM medicines WHERE shop_id = ? "
                            + "AND LOWER(name) = LOWER(?) AND LOWER(batchNumber) = LOWER(?) LIMIT 1")) {

                ps.setLong(1, shopId);
                ps.setString(2, baseName);
                ps.setString(3, batch);

                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        rowId = rs.getString(1);
                        oldQty = rs.getInt(2);
                        oldCost = rs.getDouble(3);
                    }
                }
            }
        }

        int incoming = item.quantity + item.freeQty;
        BigDecimal paid = item.cost.multiply(BigDecimal.valueOf(item.quantity));

        if (rowId != null) {

            // Average cost of what is on the shelf after this delivery.
            BigDecimal existingValue = BigDecimal.valueOf(oldCost).multiply(BigDecimal.valueOf(Math.max(0, oldQty)));
            BigDecimal newCost = existingValue.add(paid)
                    .divide(BigDecimal.valueOf(Math.max(0, oldQty) + incoming), 2, RoundingMode.HALF_UP);

            try (PreparedStatement ps = c.prepareStatement(
                    "UPDATE medicines SET quantity = quantity + ?, cost_price = ?, price = ?, expiryDate = ?, "
                            + "gst_rate = ?, hsn = ? WHERE id = ? AND shop_id = ?")) {
                ps.setInt(1, incoming);
                ps.setDouble(2, newCost.doubleValue());
                ps.setDouble(3, item.mrp.doubleValue());
                ps.setString(4, item.expiry);
                ps.setDouble(5, item.gstRate.doubleValue());
                ps.setString(6, item.hsn);
                ps.setString(7, rowId);
                ps.setLong(8, shopId);
                ps.executeUpdate();
            }

            return rowId;
        }

        String newId = baseIdForBatch != null
                ? freeMedicineId(c, shopId, sanitize(baseIdForBatch, 20) + "-" + sanitize(batch, 12))
                : nextGeneratedId(c, shopId);

        BigDecimal unitCost = paid.divide(BigDecimal.valueOf(incoming), 2, RoundingMode.HALF_UP);

        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO medicines(id, shop_id, name, batchNumber, category, quantity, price, manufacturer, "
                        + "disease, mfgDate, expiryDate, gst_rate, hsn, cost_price) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?)")) {

            ps.setString(1, newId);
            ps.setLong(2, shopId);
            ps.setString(3, baseName);
            ps.setString(4, batch);
            ps.setString(5, baseCategory == null || baseCategory.isEmpty() ? "General" : baseCategory);
            ps.setInt(6, incoming);
            ps.setDouble(7, item.mrp.doubleValue());
            ps.setString(8, baseMaker == null ? "" : baseMaker);
            ps.setString(9, baseDisease == null ? "" : baseDisease);
            ps.setString(10, item.mfgDate == null ? "" : item.mfgDate);
            ps.setString(11, item.expiry);
            ps.setDouble(12, item.gstRate.doubleValue());
            ps.setString(13, item.hsn);
            ps.setDouble(14, unitCost.doubleValue());
            ps.executeUpdate();
        }

        return newId;
    }

    /** A line pointed at a medicine that is not in this shop. */
    private static final class MissingMedicine extends RuntimeException {
        final String id;

        MissingMedicine(String id) {
            this.id = id;
        }
    }

    public static Outcome createPurchase(long shopId, String user, long supplierId, String billNo, String billDate,
                                         BigDecimal paid, String paidMode, String notes, String clientRef,
                                         List<ItemRequest> items) {

        if (items == null || items.isEmpty()) {
            return fail(400, "Add at least one medicine to the purchase");
        }

        if (items.size() > 300) {
            return fail(400, "A purchase can have at most 300 items");
        }

        if (!validDay(billDate) || LocalDate.parse(billDate).isAfter(LocalDate.now())) {
            return fail(400, "Enter a valid bill date (not in the future)");
        }

        if (paid != null && (paid.signum() < 0 || paid.stripTrailingZeros().scale() > 2)) {
            return fail(400, "The amount paid must be zero or more, with at most 2 decimals");
        }

        BigDecimal paidNow = paid == null ? BigDecimal.ZERO : paid;

        if (paidNow.signum() > 0 && !PAY_MODES.contains(paidMode)) {
            return fail(400, "Payment mode must be cash, upi, card or bank");
        }

        String today = LocalDate.now().toString();
        BigDecimal taxable = BigDecimal.ZERO, cgst = BigDecimal.ZERO, sgst = BigDecimal.ZERO;
        List<Line> lines = new ArrayList<>();

        for (ItemRequest item : items) {

            String label = item.name == null || item.name.trim().isEmpty() ? "an item" : item.name.trim();

            if ((item.medicineId == null || item.medicineId.trim().isEmpty())
                    && (item.name == null || item.name.trim().isEmpty())) {
                return fail(400, "Every line needs a medicine");
            }

            if (item.name != null && item.name.length() > 120) {
                return fail(400, "A medicine name is too long");
            }

            if (item.batch == null || item.batch.trim().isEmpty() || item.batch.length() > 40) {
                return fail(400, "Enter the batch number for " + label);
            }

            if (!validDay(item.expiry) || item.expiry.compareTo(today) < 0) {
                return fail(400, "Enter a valid future expiry date for " + label);
            }

            if (item.mfgDate != null && !item.mfgDate.isEmpty() && !validDay(item.mfgDate)) {
                return fail(400, "The manufacturing date for " + label + " is not valid");
            }

            if (item.quantity <= 0 || item.quantity > 1000000 || item.freeQty < 0 || item.freeQty > 1000000) {
                return fail(400, "Quantity for " + label + " must be between 1 and 10,00,000");
            }

            if (item.cost == null || item.cost.signum() < 0 || item.cost.compareTo(MAX_AMOUNT) > 0) {
                return fail(400, "Enter the purchase rate for " + label);
            }

            if (item.mrp == null || item.mrp.signum() <= 0 || item.mrp.compareTo(MAX_AMOUNT) > 0) {
                return fail(400, "Enter the MRP for " + label);
            }

            if (item.gstRate == null || !Billing.GST_RATES.contains(item.gstRate.intValue())
                    || item.gstRate.stripTrailingZeros().scale() > 0) {
                return fail(400, "GST rate for " + label + " must be 0, 5, 12, 18 or 28");
            }

            if (item.hsn == null || item.hsn.trim().isEmpty()) {
                item.hsn = "3004";
            } else if (!item.hsn.trim().matches("[0-9]{4,8}")) {
                return fail(400, "HSN code for " + label + " must be 4 to 8 digits");
            }

            Line line = new Line();
            line.in = item;
            line.taxable = item.cost.multiply(BigDecimal.valueOf(item.quantity)).setScale(2, RoundingMode.HALF_UP);
            BigDecimal gst = line.taxable.multiply(item.gstRate)
                    .divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);
            line.cgst = gst.divide(BigDecimal.valueOf(2), 2, RoundingMode.HALF_UP);
            line.sgst = gst.subtract(line.cgst);
            line.total = line.taxable.add(gst);

            taxable = taxable.add(line.taxable);
            cgst = cgst.add(line.cgst);
            sgst = sgst.add(line.sgst);
            lines.add(line);
        }

        BigDecimal total = taxable.add(cgst).add(sgst);

        if (paidNow.compareTo(total) > 0) {
            return fail(400, "The amount paid is more than the purchase total");
        }

        Connection c = null;

        try {
            c = DatabaseHelper.connect();

            if (c == null) {
                return fail(500, "Database is not available");
            }

            c.setAutoCommit(false);

            if (clientRef != null && !clientRef.isEmpty()) {
                try (PreparedStatement ps = c.prepareStatement(
                        "SELECT id FROM purchases WHERE shop_id = ? AND client_ref = ?")) {
                    ps.setLong(1, shopId);
                    ps.setString(2, clientRef);
                    try (ResultSet rs = ps.executeQuery()) {
                        if (rs.next()) {
                            long existing = rs.getLong(1);
                            c.rollback();
                            return new Outcome(true, 200, "Purchase already saved", existing, null);
                        }
                    }
                }
            }

            String supplierName, supplierGstin;

            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT name, gstin FROM suppliers WHERE id = ? AND shop_id = ?")) {
                ps.setLong(1, supplierId);
                ps.setLong(2, shopId);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) {
                        c.rollback();
                        return fail(404, "Supplier not found");
                    }
                    supplierName = rs.getString(1);
                    supplierGstin = rs.getString(2);
                }
            }

            // The same supplier bill entered twice would double the stock and the dues.
            if (billNo != null && !billNo.isEmpty()) {
                try (PreparedStatement ps = c.prepareStatement(
                        "SELECT purchase_no FROM purchases WHERE shop_id = ? AND supplier_id = ? "
                                + "AND LOWER(bill_no) = LOWER(?) AND status = 'active'")) {
                    ps.setLong(1, shopId);
                    ps.setLong(2, supplierId);
                    ps.setString(3, billNo);
                    try (ResultSet rs = ps.executeQuery()) {
                        if (rs.next()) {
                            String earlier = rs.getString(1);
                            c.rollback();
                            return fail(409, "Bill " + billNo + " from " + supplierName
                                    + " is already entered as " + earlier);
                        }
                    }
                }
            }

            long nextNo;

            try (PreparedStatement ps = c.prepareStatement("SELECT next_purchase_no FROM shops WHERE id = ?")) {
                ps.setLong(1, shopId);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) {
                        c.rollback();
                        return fail(404, "Shop not found");
                    }
                    nextNo = rs.getLong(1);
                }
            }

            try (PreparedStatement ps = c.prepareStatement("UPDATE shops SET next_purchase_no = ? WHERE id = ?")) {
                ps.setLong(1, nextNo + 1);
                ps.setLong(2, shopId);
                ps.executeUpdate();
            }

            String purchaseNo = "PUR-" + String.format("%05d", nextNo);
            String stamp = now();
            long purchaseId;

            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO purchases(shop_id, purchase_no, supplier_id, supplier_name, supplier_gstin, bill_no, "
                            + "bill_date, entry_date, taxable, cgst, sgst, total, amount_paid, paid_mode, notes, "
                            + "client_ref, created_by) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                    Statement.RETURN_GENERATED_KEYS)) {

                int i = 1;
                ps.setLong(i++, shopId);
                ps.setString(i++, purchaseNo);
                ps.setLong(i++, supplierId);
                ps.setString(i++, supplierName);
                ps.setString(i++, supplierGstin);
                ps.setString(i++, billNo == null ? "" : billNo);
                ps.setString(i++, billDate);
                ps.setString(i++, stamp);
                ps.setDouble(i++, taxable.doubleValue());
                ps.setDouble(i++, cgst.doubleValue());
                ps.setDouble(i++, sgst.doubleValue());
                ps.setDouble(i++, total.doubleValue());
                ps.setDouble(i++, paidNow.doubleValue());
                ps.setString(i++, paidNow.signum() > 0 ? paidMode : "");
                ps.setString(i++, notes == null ? "" : notes);

                if (clientRef == null || clientRef.isEmpty()) ps.setNull(i++, java.sql.Types.VARCHAR);
                else ps.setString(i++, clientRef);

                ps.setString(i, user);
                ps.executeUpdate();

                try (ResultSet keys = ps.getGeneratedKeys()) {
                    keys.next();
                    purchaseId = keys.getLong(1);
                }
            }

            for (Line line : lines) {

                line.medicineId = applyStock(c, shopId, line.in);

                try (PreparedStatement ps = c.prepareStatement("SELECT name FROM medicines WHERE id = ? AND shop_id = ?")) {
                    ps.setString(1, line.medicineId);
                    ps.setLong(2, shopId);
                    try (ResultSet rs = ps.executeQuery()) {
                        rs.next();
                        line.name = rs.getString(1);
                    }
                }

                try (PreparedStatement ps = c.prepareStatement(
                        "INSERT INTO purchase_items(purchase_id, medicine_id, medicine_name, batch, hsn, expiry, gst_rate, "
                                + "quantity, free_qty, cost_price, mrp, taxable_value, cgst, sgst, line_total) "
                                + "VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)")) {

                    int i = 1;
                    ps.setLong(i++, purchaseId);
                    ps.setString(i++, line.medicineId);
                    ps.setString(i++, line.name);
                    ps.setString(i++, line.in.batch.trim());
                    ps.setString(i++, line.in.hsn.trim());
                    ps.setString(i++, line.in.expiry);
                    ps.setDouble(i++, line.in.gstRate.doubleValue());
                    ps.setInt(i++, line.in.quantity);
                    ps.setInt(i++, line.in.freeQty);
                    ps.setDouble(i++, line.in.cost.doubleValue());
                    ps.setDouble(i++, line.in.mrp.doubleValue());
                    ps.setDouble(i++, line.taxable.doubleValue());
                    ps.setDouble(i++, line.cgst.doubleValue());
                    ps.setDouble(i++, line.sgst.doubleValue());
                    ps.setDouble(i, line.total.doubleValue());
                    ps.executeUpdate();
                }
            }

            addEntry(c, shopId, supplierId, "purchase", total, "",
                    "Purchase " + purchaseNo + (billNo == null || billNo.isEmpty() ? "" : " (bill " + billNo + ")"),
                    purchaseId, user, stamp);

            if (paidNow.signum() > 0) {
                addEntry(c, shopId, supplierId, "payment", paidNow.negate(), paidMode,
                        "Paid with the purchase", purchaseId, user, stamp);
            }

            c.commit();

            return new Outcome(true, 201, purchaseNo, purchaseId, total.subtract(paidNow));

        } catch (MissingMedicine e) {
            rollback(c);
            return fail(404, "Medicine " + e.id + " was not found");
        } catch (Exception e) {
            rollback(c);
            System.out.println("Create purchase error: " + e.getMessage());
            return fail(500, "The purchase could not be saved. Nothing was changed.");
        } finally {
            close(c);
        }
    }

    /** Takes the stock back out. Refused when part of it has already been sold. */
    public static Outcome cancelPurchase(long shopId, String user, long purchaseId) {

        Connection c = null;

        try {
            c = DatabaseHelper.connect();
            c.setAutoCommit(false);

            String purchaseNo, status;
            long supplierId;
            double total, paid;

            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT purchase_no, status, supplier_id, total, amount_paid FROM purchases WHERE id = ? AND shop_id = ?")) {
                ps.setLong(1, purchaseId);
                ps.setLong(2, shopId);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) {
                        c.rollback();
                        return fail(404, "Purchase not found");
                    }
                    purchaseNo = rs.getString(1);
                    status = rs.getString(2);
                    supplierId = rs.getLong(3);
                    total = rs.getDouble(4);
                    paid = rs.getDouble(5);
                }
            }

            if (!"active".equals(status)) {
                c.rollback();
                return fail(409, "This purchase is already cancelled");
            }

            try (PreparedStatement items = c.prepareStatement(
                    "SELECT medicine_id, medicine_name, quantity + free_qty FROM purchase_items WHERE purchase_id = ?")) {

                items.setLong(1, purchaseId);

                try (ResultSet rs = items.executeQuery();
                     PreparedStatement take = c.prepareStatement(
                             "UPDATE medicines SET quantity = quantity - ? WHERE id = ? AND shop_id = ? AND quantity >= ?")) {

                    while (rs.next()) {
                        take.setInt(1, rs.getInt(3));
                        take.setString(2, rs.getString(1));
                        take.setLong(3, shopId);
                        take.setInt(4, rs.getInt(3));

                        if (take.executeUpdate() != 1) {
                            String name = rs.getString(2);
                            c.rollback();
                            return fail(409, name + " from this purchase has already been sold, so it cannot be cancelled");
                        }
                    }
                }
            }

            String stamp = now();

            try (PreparedStatement ps = c.prepareStatement(
                    "UPDATE purchases SET status = 'cancelled', cancelled_at = ?, cancelled_by = ? WHERE id = ? AND shop_id = ?")) {
                ps.setString(1, stamp);
                ps.setString(2, user);
                ps.setLong(3, purchaseId);
                ps.setLong(4, shopId);
                ps.executeUpdate();
            }

            BigDecimal undo = money(paid).subtract(money(total));

            if (undo.signum() != 0) {
                addEntry(c, shopId, supplierId, "cancel", undo, "", "Purchase " + purchaseNo + " cancelled",
                        purchaseId, user, stamp);
            }

            c.commit();

            return new Outcome(true, 200, "Purchase cancelled and stock taken back out", purchaseId, null);

        } catch (Exception e) {
            rollback(c);
            System.out.println("Cancel purchase error: " + e.getMessage());
            return fail(500, "The purchase could not be cancelled. Nothing was changed.");
        } finally {
            close(c);
        }
    }

    // ---------------------------------------------------------
    // Reading
    // ---------------------------------------------------------

    public static String listJson(long shopId, String search, int limit, int offset) {

        boolean hasSearch = search != null && !search.trim().isEmpty();

        String sql = "SELECT p.id, p.purchase_no, p.supplier_name, p.bill_no, p.bill_date, p.taxable, p.cgst, p.sgst, "
                + "p.total, p.amount_paid, p.status, p.created_by, "
                + "(SELECT COUNT(*) FROM purchase_items x WHERE x.purchase_id = p.id) AS items "
                + "FROM purchases p WHERE p.shop_id = ? "
                + (hasSearch ? "AND (LOWER(p.purchase_no) LIKE ? OR LOWER(p.bill_no) LIKE ? OR LOWER(p.supplier_name) LIKE ?) " : "")
                + "ORDER BY p.id DESC LIMIT ? OFFSET ?";

        try (Connection c = DatabaseHelper.connect();
             PreparedStatement ps = c.prepareStatement(sql)) {

            int i = 1;
            ps.setLong(i++, shopId);

            if (hasSearch) {
                String like = "%" + search.trim().toLowerCase(Locale.ROOT) + "%";
                ps.setString(i++, like);
                ps.setString(i++, like);
                ps.setString(i++, like);
            }

            ps.setInt(i++, Math.max(1, Math.min(limit, 300)));
            ps.setInt(i, Math.max(0, offset));

            StringBuilder json = new StringBuilder("[");

            try (ResultSet rs = ps.executeQuery()) {
                boolean first = true;
                while (rs.next()) {
                    if (!first) json.append(',');
                    first = false;
                    json.append("{\"id\":").append(rs.getLong("id"))
                            .append(",\"purchaseNo\":").append(Json.quote(rs.getString("purchase_no")))
                            .append(",\"supplierName\":").append(Json.quote(rs.getString("supplier_name")))
                            .append(",\"billNo\":").append(Json.quote(rs.getString("bill_no")))
                            .append(",\"billDate\":").append(Json.quote(rs.getString("bill_date")))
                            .append(",\"taxable\":").append(m(money(rs.getDouble("taxable"))))
                            .append(",\"gst\":").append(m(money(rs.getDouble("cgst") + rs.getDouble("sgst"))))
                            .append(",\"total\":").append(m(money(rs.getDouble("total"))))
                            .append(",\"amountPaid\":").append(m(money(rs.getDouble("amount_paid"))))
                            .append(",\"status\":").append(Json.quote(rs.getString("status")))
                            .append(",\"createdBy\":").append(Json.quote(rs.getString("created_by")))
                            .append(",\"itemCount\":").append(rs.getInt("items"))
                            .append("}");
                }
            }

            return json.append("]").toString();

        } catch (SQLException e) {
            System.out.println("List purchases error: " + e.getMessage());
            return "[]";
        }
    }

    public static String detailJson(long shopId, long id) {

        try (Connection c = DatabaseHelper.connect()) {

            StringBuilder json = new StringBuilder();

            try (PreparedStatement ps = c.prepareStatement("SELECT * FROM purchases WHERE id = ? AND shop_id = ?")) {
                ps.setLong(1, id);
                ps.setLong(2, shopId);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) {
                        return null;
                    }
                    json.append("{\"id\":").append(rs.getLong("id"))
                            .append(",\"purchaseNo\":").append(Json.quote(rs.getString("purchase_no")))
                            .append(",\"supplierId\":").append(rs.getLong("supplier_id"))
                            .append(",\"supplierName\":").append(Json.quote(rs.getString("supplier_name")))
                            .append(",\"supplierGstin\":").append(Json.quote(rs.getString("supplier_gstin")))
                            .append(",\"billNo\":").append(Json.quote(rs.getString("bill_no")))
                            .append(",\"billDate\":").append(Json.quote(rs.getString("bill_date")))
                            .append(",\"entryDate\":").append(Json.quote(rs.getString("entry_date")))
                            .append(",\"taxable\":").append(m(money(rs.getDouble("taxable"))))
                            .append(",\"cgst\":").append(m(money(rs.getDouble("cgst"))))
                            .append(",\"sgst\":").append(m(money(rs.getDouble("sgst"))))
                            .append(",\"total\":").append(m(money(rs.getDouble("total"))))
                            .append(",\"amountPaid\":").append(m(money(rs.getDouble("amount_paid"))))
                            .append(",\"paidMode\":").append(Json.quote(rs.getString("paid_mode")))
                            .append(",\"notes\":").append(Json.quote(rs.getString("notes")))
                            .append(",\"status\":").append(Json.quote(rs.getString("status")))
                            .append(",\"createdBy\":").append(Json.quote(rs.getString("created_by")));
                }
            }

            json.append(",\"items\":[");

            try (PreparedStatement ps = c.prepareStatement("SELECT * FROM purchase_items WHERE purchase_id = ? ORDER BY id")) {
                ps.setLong(1, id);
                try (ResultSet rs = ps.executeQuery()) {
                    boolean first = true;
                    while (rs.next()) {
                        if (!first) json.append(',');
                        first = false;
                        json.append("{\"medicineId\":").append(Json.quote(rs.getString("medicine_id")))
                                .append(",\"name\":").append(Json.quote(rs.getString("medicine_name")))
                                .append(",\"batch\":").append(Json.quote(rs.getString("batch")))
                                .append(",\"hsn\":").append(Json.quote(rs.getString("hsn")))
                                .append(",\"expiry\":").append(Json.quote(rs.getString("expiry")))
                                .append(",\"gstRate\":").append(BigDecimal.valueOf(rs.getDouble("gst_rate")).stripTrailingZeros().toPlainString())
                                .append(",\"quantity\":").append(rs.getInt("quantity"))
                                .append(",\"freeQty\":").append(rs.getInt("free_qty"))
                                .append(",\"cost\":").append(m(money(rs.getDouble("cost_price"))))
                                .append(",\"mrp\":").append(m(money(rs.getDouble("mrp"))))
                                .append(",\"taxable\":").append(m(money(rs.getDouble("taxable_value"))))
                                .append(",\"lineTotal\":").append(m(money(rs.getDouble("line_total"))))
                                .append("}");
                    }
                }
            }

            return json.append("]}").toString();

        } catch (SQLException e) {
            System.out.println("Purchase detail error: " + e.getMessage());
            return null;
        }
    }

    /** Medicines for the purchase form. Includes the cost, so it is for owner and pharmacist only. */
    public static String medicineSearchJson(long shopId, String text) {

        String like = "%" + (text == null ? "" : text.trim().toLowerCase(Locale.ROOT)) + "%";

        try (Connection c = DatabaseHelper.connect();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT id, name, batchNumber, quantity, price, cost_price, gst_rate, hsn, expiryDate "
                             + "FROM medicines WHERE shop_id = ? AND (LOWER(name) LIKE ? OR LOWER(id) LIKE ?) "
                             + "ORDER BY name COLLATE NOCASE, expiryDate DESC LIMIT 12")) {

            ps.setLong(1, shopId);
            ps.setString(2, like);
            ps.setString(3, like);

            StringBuilder json = new StringBuilder("[");

            try (ResultSet rs = ps.executeQuery()) {
                boolean first = true;
                while (rs.next()) {
                    if (!first) json.append(',');
                    first = false;
                    json.append("{\"id\":").append(Json.quote(rs.getString(1)))
                            .append(",\"name\":").append(Json.quote(rs.getString(2)))
                            .append(",\"batch\":").append(Json.quote(rs.getString(3)))
                            .append(",\"quantity\":").append(rs.getInt(4))
                            .append(",\"mrp\":").append(m(money(rs.getDouble(5))))
                            .append(",\"cost\":").append(m(money(rs.getDouble(6))))
                            .append(",\"gstRate\":").append(BigDecimal.valueOf(rs.getDouble(7)).stripTrailingZeros().toPlainString())
                            .append(",\"hsn\":").append(Json.quote(rs.getString(8)))
                            .append(",\"expiry\":").append(Json.quote(rs.getString(9)))
                            .append("}");
                }
            }

            return json.append("]").toString();

        } catch (SQLException e) {
            System.out.println("Purchase medicine search error: " + e.getMessage());
            return "[]";
        }
    }
}
