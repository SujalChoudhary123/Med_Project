import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Sale returns and GST credit notes.
 *
 * A return is made against one invoice. The customer gets back exactly what
 * they paid for the returned units (same price, same discount, same GST), and
 * the total refunded for an invoice can never be more than the invoice itself.
 *
 * Like billing, a return is all-or-nothing and is calculated on the server.
 */
public final class Returns {

    private Returns() {}

    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    public static final class LineRequest {
        public long itemId;
        public int quantity;
    }

    public static final class Outcome {
        public final boolean ok;
        public final int status;
        public final String message;
        public final long creditNoteId;

        Outcome(boolean ok, int status, String message, long creditNoteId) {
            this.ok = ok;
            this.status = status;
            this.message = message;
            this.creditNoteId = creditNoteId;
        }
    }

    private static Outcome fail(int status, String message) {
        return new Outcome(false, status, message, 0);
    }

    private static BigDecimal money(double value) {
        return BigDecimal.valueOf(value).setScale(2, RoundingMode.HALF_UP);
    }

    private static final class Line {
        long itemId;
        String medicineId, name, batch, hsn;
        BigDecimal gstRate, unitPrice, discountPercent;
        int quantity;
        Billing.Amounts amounts;
    }

    // ---------------------------------------------------------
    // Create a return
    // ---------------------------------------------------------

    public static Outcome createReturn(
            long shopId,
            String username,
            long invoiceId,
            String reason,
            String refundMode,
            boolean restock,
            List<LineRequest> requested) {

        if (requested == null || requested.isEmpty()) {
            return fail(400, "Choose at least one medicine to return");
        }

        if (!Billing.PAYMENT_MODES.contains(refundMode)) {
            return fail(400, "Refund mode must be cash, upi, card or credit");
        }

        if (requested.size() > 100) {
            return fail(400, "A return can have at most 100 items");
        }

        Set<Long> seen = new HashSet<>();

        for (LineRequest line : requested) {

            if (line.quantity <= 0 || line.quantity > 100000) {
                return fail(400, "Return quantity must be between 1 and 100000");
            }

            if (!seen.add(line.itemId)) {
                return fail(400, "The same item is listed twice");
            }
        }

        Connection conn = null;

        try {

            conn = DatabaseHelper.connect();

            if (conn == null) {
                return fail(500, "Database is not available");
            }

            conn.setAutoCommit(false);

            // ---- the invoice being returned against ----
            String invoiceNo, invoiceDate, status, customer, phone, gstin;
            String sName, sAddress, sPhone, sGstin, sLicense, sState;
            boolean inclusive;
            BigDecimal invoiceTotal;
            String invoiceMode;
            long custId;

            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT * FROM invoices WHERE id = ? AND shop_id = ?")) {

                ps.setLong(1, invoiceId);
                ps.setLong(2, shopId);

                try (ResultSet rs = ps.executeQuery()) {

                    if (!rs.next()) {
                        conn.rollback();
                        return fail(404, "Invoice not found");
                    }

                    invoiceNo = rs.getString("invoice_no");
                    invoiceDate = rs.getString("invoice_date");
                    status = rs.getString("status");
                    customer = rs.getString("customer_name");
                    phone = rs.getString("customer_phone");
                    gstin = rs.getString("customer_gstin");
                    inclusive = rs.getInt("prices_include_gst") == 1;
                    invoiceTotal = money(rs.getDouble("total"));
                    invoiceMode = rs.getString("payment_mode");
                    custId = rs.getLong("customer_id");
                    sName = rs.getString("seller_name");
                    sAddress = rs.getString("seller_address");
                    sPhone = rs.getString("seller_phone");
                    sGstin = rs.getString("seller_gstin");
                    sLicense = rs.getString("seller_license");
                    sState = rs.getString("seller_state");
                }
            }

            if (!"active".equals(status)) {
                conn.rollback();
                return fail(409, "A cancelled invoice cannot have returns");
            }

            // ---- what was already refunded on this invoice ----
            BigDecimal refundedBefore;

            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT COALESCE(SUM(total), 0) FROM credit_notes WHERE invoice_id = ? AND shop_id = ?")) {

                ps.setLong(1, invoiceId);
                ps.setLong(2, shopId);

                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    refundedBefore = money(rs.getDouble(1));
                }
            }

            String stamp = LocalDateTime.now().format(STAMP);

            // An udhaar bill is refunded against what the customer owes, never paid out in cash.
            if ("credit".equals(invoiceMode)) {
                refundMode = "credit";
            }

            // ---- each returned line ----
            List<Line> lines = new ArrayList<>();

            for (LineRequest request : requested) {

                Line line = new Line();
                line.itemId = request.itemId;
                line.quantity = request.quantity;

                int soldQty, returnedQty;
                BigDecimal itemTaxable, itemCgst, itemSgst, itemTotal;
                BigDecimal doneTaxable, doneCgst, doneSgst, doneTotal;

                try (PreparedStatement ps = conn.prepareStatement(
                        "SELECT * FROM invoice_items WHERE id = ? AND invoice_id = ?")) {

                    ps.setLong(1, request.itemId);
                    ps.setLong(2, invoiceId);

                    try (ResultSet rs = ps.executeQuery()) {

                        if (!rs.next()) {
                            conn.rollback();
                            return fail(404, "That item is not on this invoice");
                        }

                        line.medicineId = rs.getString("medicine_id");
                        line.name = rs.getString("medicine_name");
                        line.batch = rs.getString("batch");
                        line.hsn = rs.getString("hsn");
                        line.gstRate = BigDecimal.valueOf(rs.getDouble("gst_rate"));
                        line.unitPrice = BigDecimal.valueOf(rs.getDouble("unit_price"));
                        line.discountPercent = BigDecimal.valueOf(rs.getDouble("discount_percent"));
                        soldQty = rs.getInt("quantity");
                        returnedQty = rs.getInt("returned_qty");
                        itemTaxable = money(rs.getDouble("taxable_value"));
                        itemCgst = money(rs.getDouble("cgst"));
                        itemSgst = money(rs.getDouble("sgst"));
                        itemTotal = money(rs.getDouble("line_total"));
                        doneTaxable = money(rs.getDouble("returned_taxable"));
                        doneCgst = money(rs.getDouble("returned_cgst"));
                        doneSgst = money(rs.getDouble("returned_sgst"));
                        doneTotal = money(rs.getDouble("returned_total"));
                    }
                }

                int remaining = soldQty - returnedQty;

                if (request.quantity > remaining) {
                    conn.rollback();
                    return fail(409, remaining <= 0
                            ? line.name + " has already been returned in full"
                            : "Only " + remaining + " of " + line.name + " can still be returned");
                }

                Billing.Amounts amounts;

                if (request.quantity == remaining) {

                    // Last units: refund exactly what is left, so the pieces add up to the original line.
                    amounts = new Billing.Amounts();
                    amounts.lineTotal = itemTotal.subtract(doneTotal);
                    amounts.taxable = itemTaxable.subtract(doneTaxable);
                    amounts.cgst = itemCgst.subtract(doneCgst);
                    amounts.sgst = itemSgst.subtract(doneSgst);
                    amounts.gross = line.unitPrice.multiply(BigDecimal.valueOf(request.quantity))
                            .setScale(2, RoundingMode.HALF_UP);
                    amounts.discount = amounts.gross.multiply(line.discountPercent)
                            .divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);

                } else {

                    amounts = Billing.compute(line.unitPrice, request.quantity,
                            line.discountPercent, line.gstRate, inclusive);

                    // Rounding must never refund more than is left on the line.
                    BigDecimal left = itemTotal.subtract(doneTotal);

                    if (amounts.lineTotal.compareTo(left) > 0) {
                        amounts.lineTotal = left;
                        amounts.taxable = itemTaxable.subtract(doneTaxable);
                        amounts.cgst = itemCgst.subtract(doneCgst);
                        amounts.sgst = itemSgst.subtract(doneSgst);
                    }
                }

                line.amounts = amounts;

                try (PreparedStatement ps = conn.prepareStatement(
                        "UPDATE invoice_items SET returned_qty = returned_qty + ?, "
                                + "returned_taxable = returned_taxable + ?, returned_cgst = returned_cgst + ?, "
                                + "returned_sgst = returned_sgst + ?, returned_total = returned_total + ? "
                                + "WHERE id = ? AND invoice_id = ?")) {

                    ps.setInt(1, request.quantity);
                    ps.setDouble(2, amounts.taxable.doubleValue());
                    ps.setDouble(3, amounts.cgst.doubleValue());
                    ps.setDouble(4, amounts.sgst.doubleValue());
                    ps.setDouble(5, amounts.lineTotal.doubleValue());
                    ps.setLong(6, request.itemId);
                    ps.setLong(7, invoiceId);
                    ps.executeUpdate();
                }

                if (restock) {

                    // A medicine deleted in the meantime is simply skipped.
                    try (PreparedStatement ps = conn.prepareStatement(
                            "UPDATE medicines SET quantity = quantity + ? WHERE id = ? AND shop_id = ?")) {

                        ps.setInt(1, request.quantity);
                        ps.setString(2, line.medicineId);
                        ps.setLong(3, shopId);
                        ps.executeUpdate();
                    }
                }

                // Negative sale row, so dashboard and reports show revenue after returns.
                try (PreparedStatement ps = conn.prepareStatement(
                        "INSERT INTO sales(medicineId, medicineName, customerName, quantity, unitPrice, "
                                + "totalAmount, saleDate, shop_id, invoice_id) VALUES(?,?,?,?,?,?,?,?,?)")) {

                    ps.setString(1, line.medicineId);
                    ps.setString(2, line.name);
                    ps.setString(3, customer);
                    ps.setInt(4, -request.quantity);
                    ps.setDouble(5, line.unitPrice.doubleValue());
                    ps.setDouble(6, amounts.lineTotal.negate().doubleValue());
                    ps.setString(7, stamp);
                    ps.setLong(8, shopId);
                    ps.setLong(9, invoiceId);
                    ps.executeUpdate();
                }

                lines.add(line);
            }

            // ---- totals ----
            BigDecimal discount = BigDecimal.ZERO, taxable = BigDecimal.ZERO;
            BigDecimal cgst = BigDecimal.ZERO, sgst = BigDecimal.ZERO, grand = BigDecimal.ZERO;

            for (Line line : lines) {
                discount = discount.add(line.amounts.discount);
                taxable = taxable.add(line.amounts.taxable);
                cgst = cgst.add(line.amounts.cgst);
                sgst = sgst.add(line.amounts.sgst);
                grand = grand.add(line.amounts.lineTotal);
            }

            BigDecimal payable = grand.setScale(0, RoundingMode.HALF_UP).setScale(2);

            // Whole-rupee rounding on several returns must never refund more than was paid.
            BigDecimal room = invoiceTotal.subtract(refundedBefore);

            if (payable.compareTo(room) > 0) {
                payable = room.max(BigDecimal.ZERO);
            }

            BigDecimal roundOff = payable.subtract(grand);

            // ---- credit note number ----
            long nextNo;

            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT next_credit_no FROM shops WHERE id = ?")) {

                ps.setLong(1, shopId);

                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    nextNo = rs.getLong(1);
                }
            }

            try (PreparedStatement ps = conn.prepareStatement(
                    "UPDATE shops SET next_credit_no = ? WHERE id = ?")) {
                ps.setLong(1, nextNo + 1);
                ps.setLong(2, shopId);
                ps.executeUpdate();
            }

            String noteNo = "CN-" + String.format("%05d", nextNo);
            long noteId;

            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO credit_notes(shop_id, note_no, note_date, invoice_id, invoice_no, invoice_date, "
                            + "customer_name, customer_phone, customer_gstin, reason, refund_mode, restocked, "
                            + "prices_include_gst, discount, taxable, cgst, sgst, round_off, total, created_by, "
                            + "seller_name, seller_address, seller_phone, seller_gstin, seller_license, seller_state) "
                            + "VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                    Statement.RETURN_GENERATED_KEYS)) {

                int i = 1;
                ps.setLong(i++, shopId);
                ps.setString(i++, noteNo);
                ps.setString(i++, stamp);
                ps.setLong(i++, invoiceId);
                ps.setString(i++, invoiceNo);
                ps.setString(i++, invoiceDate);
                ps.setString(i++, customer);
                ps.setString(i++, phone);
                ps.setString(i++, gstin);
                ps.setString(i++, reason);
                ps.setString(i++, refundMode);
                ps.setInt(i++, restock ? 1 : 0);
                ps.setInt(i++, inclusive ? 1 : 0);
                ps.setDouble(i++, discount.doubleValue());
                ps.setDouble(i++, taxable.doubleValue());
                ps.setDouble(i++, cgst.doubleValue());
                ps.setDouble(i++, sgst.doubleValue());
                ps.setDouble(i++, roundOff.doubleValue());
                ps.setDouble(i++, payable.doubleValue());
                ps.setString(i++, username);
                ps.setString(i++, sName);
                ps.setString(i++, sAddress);
                ps.setString(i++, sPhone);
                ps.setString(i++, sGstin);
                ps.setString(i++, sLicense);
                ps.setString(i, sState);
                ps.executeUpdate();

                try (ResultSet keys = ps.getGeneratedKeys()) {
                    keys.next();
                    noteId = keys.getLong(1);
                }
            }

            for (Line line : lines) {

                try (PreparedStatement ps = conn.prepareStatement(
                        "INSERT INTO credit_note_items(credit_note_id, invoice_item_id, medicine_id, medicine_name, "
                                + "batch, hsn, gst_rate, quantity, unit_price, discount_percent, discount_amount, "
                                + "taxable_value, cgst, sgst, line_total) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)")) {

                    int i = 1;
                    ps.setLong(i++, noteId);
                    ps.setLong(i++, line.itemId);
                    ps.setString(i++, line.medicineId);
                    ps.setString(i++, line.name);
                    ps.setString(i++, line.batch);
                    ps.setString(i++, line.hsn);
                    ps.setDouble(i++, line.gstRate.doubleValue());
                    ps.setInt(i++, line.quantity);
                    ps.setDouble(i++, line.unitPrice.doubleValue());
                    ps.setDouble(i++, line.discountPercent.doubleValue());
                    ps.setDouble(i++, line.amounts.discount.doubleValue());
                    ps.setDouble(i++, line.amounts.taxable.doubleValue());
                    ps.setDouble(i++, line.amounts.cgst.doubleValue());
                    ps.setDouble(i++, line.amounts.sgst.doubleValue());
                    ps.setDouble(i, line.amounts.lineTotal.doubleValue());
                    ps.executeUpdate();
                }
            }

            if ("credit".equals(refundMode) && custId > 0) {
                Customers.addEntry(conn, shopId, custId, "return", payable.negate(), "credit",
                        "Return " + noteNo + " against " + invoiceNo, invoiceId, noteId, username, stamp);
            }

            conn.commit();

            return new Outcome(true, 201, "Credit note created", noteId);

        } catch (Exception e) {

            try {
                if (conn != null) conn.rollback();
            } catch (Exception ignored) {
                // nothing more to do
            }

            System.out.println("Create return error: " + e.getMessage());
            return fail(500, "The return could not be saved. Nothing was changed.");

        } finally {

            try {
                if (conn != null) {
                    conn.setAutoCommit(true);
                    conn.close();
                }
            } catch (Exception ignored) {
                // nothing more to do
            }
        }
    }

    // ---------------------------------------------------------
    // Reading credit notes (JSON text)
    // ---------------------------------------------------------

    private static String d(ResultSet rs, String column) throws Exception {
        return Json.money(BigDecimal.valueOf(rs.getDouble(column)));
    }

    private static String num(double value) {
        return BigDecimal.valueOf(value).stripTrailingZeros().toPlainString();
    }

    /** One credit note for printing, or null when it does not belong to this shop. */
    public static String creditNoteJson(long shopId, long noteId) {

        try (Connection conn = DatabaseHelper.connect();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT * FROM credit_notes WHERE id = ? AND shop_id = ?")) {

            ps.setLong(1, noteId);
            ps.setLong(2, shopId);

            StringBuilder json = new StringBuilder();

            try (ResultSet rs = ps.executeQuery()) {

                if (!rs.next()) {
                    return null;
                }

                json.append("{\"id\":").append(rs.getLong("id"))
                        .append(",\"noteNo\":").append(Json.quote(rs.getString("note_no")))
                        .append(",\"date\":").append(Json.quote(rs.getString("note_date")))
                        .append(",\"reason\":").append(Json.quote(rs.getString("reason")))
                        .append(",\"refundMode\":").append(Json.quote(rs.getString("refund_mode")))
                        .append(",\"restocked\":").append(rs.getInt("restocked") == 1)
                        .append(",\"pricesIncludeGst\":").append(rs.getInt("prices_include_gst") == 1)
                        .append(",\"createdBy\":").append(Json.quote(rs.getString("created_by")))
                        .append(",\"invoice\":{\"id\":").append(rs.getLong("invoice_id"))
                        .append(",\"invoiceNo\":").append(Json.quote(rs.getString("invoice_no")))
                        .append(",\"date\":").append(Json.quote(rs.getString("invoice_date"))).append("}")
                        .append(",\"customer\":{\"name\":").append(Json.quote(rs.getString("customer_name")))
                        .append(",\"phone\":").append(Json.quote(rs.getString("customer_phone")))
                        .append(",\"gstin\":").append(Json.quote(rs.getString("customer_gstin"))).append("}")
                        .append(",\"seller\":{\"name\":").append(Json.quote(rs.getString("seller_name")))
                        .append(",\"address\":").append(Json.quote(rs.getString("seller_address")))
                        .append(",\"phone\":").append(Json.quote(rs.getString("seller_phone")))
                        .append(",\"gstin\":").append(Json.quote(rs.getString("seller_gstin")))
                        .append(",\"license\":").append(Json.quote(rs.getString("seller_license")))
                        .append(",\"state\":").append(Json.quote(rs.getString("seller_state"))).append("}")
                        .append(",\"totals\":{\"discount\":").append(d(rs, "discount"))
                        .append(",\"taxable\":").append(d(rs, "taxable"))
                        .append(",\"cgst\":").append(d(rs, "cgst"))
                        .append(",\"sgst\":").append(d(rs, "sgst"))
                        .append(",\"roundOff\":").append(d(rs, "round_off"))
                        .append(",\"total\":").append(d(rs, "total")).append("}");
            }

            json.append(",\"items\":[");

            try (PreparedStatement items = conn.prepareStatement(
                    "SELECT * FROM credit_note_items WHERE credit_note_id = ? ORDER BY id")) {

                items.setLong(1, noteId);

                try (ResultSet rs = items.executeQuery()) {

                    boolean first = true;

                    while (rs.next()) {

                        if (!first) json.append(',');
                        first = false;

                        json.append("{\"medicineId\":").append(Json.quote(rs.getString("medicine_id")))
                                .append(",\"name\":").append(Json.quote(rs.getString("medicine_name")))
                                .append(",\"batch\":").append(Json.quote(rs.getString("batch")))
                                .append(",\"hsn\":").append(Json.quote(rs.getString("hsn")))
                                .append(",\"gstRate\":").append(num(rs.getDouble("gst_rate")))
                                .append(",\"quantity\":").append(rs.getInt("quantity"))
                                .append(",\"unitPrice\":").append(d(rs, "unit_price"))
                                .append(",\"discountPercent\":").append(num(rs.getDouble("discount_percent")))
                                .append(",\"discount\":").append(d(rs, "discount_amount"))
                                .append(",\"taxable\":").append(d(rs, "taxable_value"))
                                .append(",\"cgst\":").append(d(rs, "cgst"))
                                .append(",\"sgst\":").append(d(rs, "sgst"))
                                .append(",\"lineTotal\":").append(d(rs, "line_total"))
                                .append("}");
                    }
                }
            }

            return json.append("]}").toString();

        } catch (Exception e) {
            System.out.println("Read credit note error: " + e.getMessage());
            return null;
        }
    }

    /** Recent credit notes of the shop (newest first). */
    public static String listJson(long shopId, String search, int limit, int offset) {

        boolean hasSearch = search != null && !search.trim().isEmpty();

        String sql = "SELECT id, note_no, note_date, invoice_id, invoice_no, customer_name, refund_mode, "
                + "total, reason, created_by FROM credit_notes WHERE shop_id = ? "
                + (hasSearch ? "AND (LOWER(note_no) LIKE ? OR LOWER(invoice_no) LIKE ? "
                        + "OR LOWER(customer_name) LIKE ?) " : "")
                + "ORDER BY id DESC LIMIT ? OFFSET ?";

        try (Connection conn = DatabaseHelper.connect();
             PreparedStatement ps = conn.prepareStatement(sql)) {

            int i = 1;
            ps.setLong(i++, shopId);

            if (hasSearch) {
                String like = "%" + search.trim().toLowerCase() + "%";
                ps.setString(i++, like);
                ps.setString(i++, like);
                ps.setString(i++, like);
            }

            ps.setInt(i++, Math.max(1, Math.min(limit, 200)));
            ps.setInt(i, Math.max(0, offset));

            StringBuilder json = new StringBuilder("[");

            try (ResultSet rs = ps.executeQuery()) {

                boolean first = true;

                while (rs.next()) {

                    if (!first) json.append(',');
                    first = false;

                    json.append("{\"id\":").append(rs.getLong("id"))
                            .append(",\"noteNo\":").append(Json.quote(rs.getString("note_no")))
                            .append(",\"date\":").append(Json.quote(rs.getString("note_date")))
                            .append(",\"invoiceId\":").append(rs.getLong("invoice_id"))
                            .append(",\"invoiceNo\":").append(Json.quote(rs.getString("invoice_no")))
                            .append(",\"customerName\":").append(Json.quote(rs.getString("customer_name")))
                            .append(",\"refundMode\":").append(Json.quote(rs.getString("refund_mode")))
                            .append(",\"total\":").append(d(rs, "total"))
                            .append(",\"reason\":").append(Json.quote(rs.getString("reason")))
                            .append(",\"createdBy\":").append(Json.quote(rs.getString("created_by")))
                            .append("}");
                }
            }

            return json.append("]").toString();

        } catch (Exception e) {
            System.out.println("List credit notes error: " + e.getMessage());
            return "[]";
        }
    }
}
