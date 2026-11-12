import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * GST invoices with several medicines on one bill.
 *
 * Everything money-related is calculated here on the server with BigDecimal.
 * The browser only says WHAT was sold (medicine, quantity, discount %); it can
 * never decide a price, a tax amount or a total.
 *
 * A bill is all-or-nothing: if any single item fails (not enough stock,
 * expired, unknown medicine) nothing is saved and no stock is touched.
 *
 * Tax is split into CGST + SGST (sale inside the shop's own state).
 */
public final class Billing {

    private Billing() {}

    public static final Set<Integer> GST_RATES = new HashSet<>(Arrays.asList(0, 5, 12, 18, 28));
    public static final Set<String> PAYMENT_MODES =
            new HashSet<>(Arrays.asList("cash", "upi", "card", "credit"));

    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    // ---------------------------------------------------------
    // Requests and results
    // ---------------------------------------------------------

    public static final class ItemRequest {
        public String medicineId = "";
        public int quantity;
        public BigDecimal discountPercent = BigDecimal.ZERO;
    }

    public static final class Outcome {
        public final boolean ok;
        public final int status;
        public final String message;
        public final long invoiceId;
        public final String invoiceNo;

        Outcome(boolean ok, int status, String message, long invoiceId, String invoiceNo) {
            this.ok = ok;
            this.status = status;
            this.message = message;
            this.invoiceId = invoiceId;
            this.invoiceNo = invoiceNo;
        }
    }

    private static Outcome fail(int status, String message) {
        return new Outcome(false, status, message, 0, null);
    }

    // ---------------------------------------------------------
    // GST maths (public so it can be tested on its own)
    // ---------------------------------------------------------

    public static final class Amounts {
        public BigDecimal gross;      // unit price x quantity
        public BigDecimal discount;   // discount given on this line
        public BigDecimal taxable;    // value on which GST is charged
        public BigDecimal cgst;
        public BigDecimal sgst;
        public BigDecimal lineTotal;  // what the customer pays for this line
    }

    private static BigDecimal money(BigDecimal value) {
        return value.setScale(2, RoundingMode.HALF_UP);
    }

    /**
     * @param inclusive true when the printed price (MRP) already contains GST
     *                  (the normal case for medicines); false when GST is added on top.
     */
    public static Amounts compute(
            BigDecimal unitPrice, int quantity, BigDecimal discountPercent,
            BigDecimal gstRate, boolean inclusive) {

        Amounts a = new Amounts();

        a.gross = money(unitPrice.multiply(BigDecimal.valueOf(quantity)));
        a.discount = money(a.gross.multiply(discountPercent).divide(HUNDRED, 10, RoundingMode.HALF_UP));

        BigDecimal base = a.gross.subtract(a.discount);
        BigDecimal gst;

        if (inclusive) {
            a.lineTotal = base;
            a.taxable = money(base.multiply(HUNDRED)
                    .divide(HUNDRED.add(gstRate), 10, RoundingMode.HALF_UP));
            gst = a.lineTotal.subtract(a.taxable);
        } else {
            a.taxable = base;
            gst = money(base.multiply(gstRate).divide(HUNDRED, 10, RoundingMode.HALF_UP));
            a.lineTotal = a.taxable.add(gst);
        }

        // Split so that cgst + sgst always equals the exact GST amount.
        a.cgst = gst.divide(BigDecimal.valueOf(2), 2, RoundingMode.HALF_UP);
        a.sgst = gst.subtract(a.cgst);

        return a;
    }

    // ---------------------------------------------------------
    // Create an invoice
    // ---------------------------------------------------------

    private static final class Line {
        String medicineId, name, batch, hsn, expiry;
        int quantity;
        BigDecimal unitPrice, gstRate, discountPercent;
        double costPrice;
        Amounts amounts;
    }

    /** Older callers (no customer record): a normal paid bill. */
    public static Outcome createInvoice(
            long shopId, String username, String customerName, String customerPhone,
            String customerGstin, String paymentMode, List<ItemRequest> items) {
        return createInvoice(shopId, username, customerName, customerPhone, customerGstin,
                paymentMode, null, null, null, null, items);
    }

    public static Outcome createInvoice(
            long shopId,
            String username,
            String customerName,
            String customerPhone,
            String customerGstin,
            String paymentMode,
            Long customerId,
            BigDecimal amountPaid,
            String paidMode,
            String clientRef,
            List<ItemRequest> items) {

        if (items == null || items.isEmpty()) {
            return fail(400, "Add at least one medicine to the bill");
        }

        // Udhaar: a credit bill needs a known customer, and may be part-paid on the spot.
        boolean onCredit = "credit".equals(paymentMode);

        if (onCredit && customerId == null) {
            return fail(400, "Choose a customer to sell on credit");
        }

        if (amountPaid != null && (amountPaid.signum() < 0 || amountPaid.scale() > 2)) {
            return fail(400, "The amount received must be zero or more, with at most 2 decimals");
        }

        if (paidMode == null || paidMode.isEmpty()) {
            paidMode = "cash";
        }

        if (!"cash".equals(paidMode) && !"upi".equals(paidMode) && !"card".equals(paidMode)) {
            return fail(400, "The amount received must be by cash, upi or card");
        }

        if (items.size() > 100) {
            return fail(400, "A bill can have at most 100 items");
        }

        if (!PAYMENT_MODES.contains(paymentMode)) {
            return fail(400, "Payment mode must be cash, upi, card or credit");
        }

        Connection conn = null;

        try {

            conn = DatabaseHelper.connect();

            if (conn == null) {
                return fail(500, "Database is not available");
            }

            conn.setAutoCommit(false);

            // ---- the same bill sent twice (retry after a dropped connection) ----
            if (clientRef != null && !clientRef.isEmpty()) {

                try (PreparedStatement ps = conn.prepareStatement(
                        "SELECT id, invoice_no FROM invoices WHERE shop_id = ? AND client_ref = ?")) {

                    ps.setLong(1, shopId);
                    ps.setString(2, clientRef);

                    try (ResultSet rs = ps.executeQuery()) {
                        if (rs.next()) {
                            long existingId = rs.getLong(1);
                            String existingNo = rs.getString(2);
                            conn.rollback();
                            return new Outcome(true, 200, "Invoice already saved", existingId, existingNo);
                        }
                    }
                }
            }

            // ---- shop (seller details, tax mode, next number) ----
            String shopName, address, phone, gstin, license, state, prefix;
            boolean inclusive;
            long nextNo;

            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT name, address, phone, gstin, drug_license, state, invoice_prefix, "
                            + "prices_include_gst, next_invoice_no FROM shops WHERE id = ?")) {

                ps.setLong(1, shopId);

                try (ResultSet rs = ps.executeQuery()) {

                    if (!rs.next()) {
                        conn.rollback();
                        return fail(404, "Shop not found");
                    }

                    shopName = rs.getString("name");
                    address = rs.getString("address");
                    phone = rs.getString("phone");
                    gstin = rs.getString("gstin");
                    license = rs.getString("drug_license");
                    state = rs.getString("state");
                    prefix = rs.getString("invoice_prefix");
                    inclusive = rs.getInt("prices_include_gst") == 1;
                    nextNo = rs.getLong("next_invoice_no");
                }
            }

            // ---- customer (the shop's own record is trusted, not the text the browser sent) ----
            Customers.Row customerRow = null;

            if (customerId != null) {

                customerRow = Customers.load(conn, shopId, customerId);

                if (customerRow == null) {
                    conn.rollback();
                    return fail(404, "Customer not found");
                }

                customerName = customerRow.name;
                customerPhone = customerRow.phone;
                customerGstin = customerRow.gstin;
            }

            // ---- items: validate, take stock, calculate ----
            List<Line> lines = new ArrayList<>();
            LocalDate today = LocalDate.now();

            for (ItemRequest request : items) {

                if (request.medicineId == null || request.medicineId.trim().isEmpty()) {
                    conn.rollback();
                    return fail(400, "Every item needs a medicine");
                }

                if (request.quantity <= 0 || request.quantity > 100000) {
                    conn.rollback();
                    return fail(400, "Quantity must be between 1 and 100000");
                }

                if (request.discountPercent.compareTo(BigDecimal.ZERO) < 0
                        || request.discountPercent.compareTo(HUNDRED) > 0) {
                    conn.rollback();
                    return fail(400, "Discount must be between 0 and 100 percent");
                }

                Line line = new Line();
                line.medicineId = request.medicineId.trim();
                line.quantity = request.quantity;
                line.discountPercent = request.discountPercent;

                int stock;

                try (PreparedStatement ps = conn.prepareStatement(
                        "SELECT name, batchNumber, quantity, price, expiryDate, gst_rate, hsn, cost_price "
                                + "FROM medicines WHERE id = ? AND shop_id = ?")) {

                    ps.setString(1, line.medicineId);
                    ps.setLong(2, shopId);

                    try (ResultSet rs = ps.executeQuery()) {

                        if (!rs.next()) {
                            conn.rollback();
                            return fail(404, "Medicine " + line.medicineId + " was not found");
                        }

                        line.name = rs.getString("name");
                        line.batch = rs.getString("batchNumber");
                        stock = rs.getInt("quantity");
                        line.unitPrice = BigDecimal.valueOf(rs.getDouble("price"));
                        line.expiry = rs.getString("expiryDate");
                        line.gstRate = BigDecimal.valueOf(rs.getDouble("gst_rate"));
                        line.hsn = rs.getString("hsn");
                        line.costPrice = rs.getDouble("cost_price");
                    }
                }

                try {
                    if (LocalDate.parse(line.expiry).isBefore(today)) {
                        conn.rollback();
                        return fail(409, line.name + " has expired and cannot be sold");
                    }
                } catch (Exception e) {
                    // Unreadable expiry date: do not block the sale.
                }

                // Guarded update: never lets stock go below zero, even when
                // several bills are made at the same moment.
                try (PreparedStatement ps = conn.prepareStatement(
                        "UPDATE medicines SET quantity = quantity - ? "
                                + "WHERE id = ? AND shop_id = ? AND quantity >= ?")) {

                    ps.setInt(1, line.quantity);
                    ps.setString(2, line.medicineId);
                    ps.setLong(3, shopId);
                    ps.setInt(4, line.quantity);

                    if (ps.executeUpdate() != 1) {
                        conn.rollback();
                        return fail(409, "Not enough stock for " + line.name
                                + " (available: " + Math.max(stock, 0) + ")");
                    }
                }

                line.amounts = compute(line.unitPrice, line.quantity, line.discountPercent,
                        line.gstRate, inclusive);

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
            BigDecimal roundOff = payable.subtract(grand);

            // ---- credit: how much was paid now, how much is left owing ----
            BigDecimal paidNow = payable;

            if (onCredit) {

                paidNow = amountPaid == null ? BigDecimal.ZERO : amountPaid;

                if (paidNow.compareTo(payable) > 0) {
                    conn.rollback();
                    return fail(400, "The amount received is more than the bill total");
                }

                BigDecimal owedNow = Customers.balance(conn, customerRow.id);
                BigDecimal newDue = payable.subtract(paidNow);

                if (customerRow.creditLimit.signum() > 0
                        && owedNow.add(newDue).compareTo(customerRow.creditLimit) > 0) {
                    conn.rollback();
                    return fail(409, customerRow.name + " is over the credit limit of "
                            + Json.money(customerRow.creditLimit) + " (already owes "
                            + Json.money(owedNow.max(BigDecimal.ZERO)) + ")");
                }
            }

            // ---- invoice number (inside the same transaction, so no gaps or duplicates) ----
            String invoiceNo = prefix + String.format("%05d", nextNo);

            try (PreparedStatement ps = conn.prepareStatement(
                    "UPDATE shops SET next_invoice_no = ? WHERE id = ?")) {
                ps.setLong(1, nextNo + 1);
                ps.setLong(2, shopId);
                ps.executeUpdate();
            }

            String stamp = LocalDateTime.now().format(STAMP);
            String customer = customerName == null || customerName.trim().isEmpty()
                    ? "Walk-in Customer" : customerName.trim();

            long invoiceId;

            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO invoices(shop_id, invoice_no, invoice_date, customer_name, "
                            + "customer_phone, customer_gstin, payment_mode, prices_include_gst, "
                            + "discount, taxable, cgst, sgst, round_off, total, status, created_by, "
                            + "seller_name, seller_address, seller_phone, seller_gstin, seller_license, "
                            + "seller_state, customer_id, amount_paid, client_ref) "
                            + "VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,'active',?,?,?,?,?,?,?,?,?,?)",
                    Statement.RETURN_GENERATED_KEYS)) {

                int i = 1;
                ps.setLong(i++, shopId);
                ps.setString(i++, invoiceNo);
                ps.setString(i++, stamp);
                ps.setString(i++, customer);
                ps.setString(i++, customerPhone == null ? "" : customerPhone);
                ps.setString(i++, customerGstin == null ? "" : customerGstin);
                ps.setString(i++, paymentMode);
                ps.setInt(i++, inclusive ? 1 : 0);
                ps.setDouble(i++, discount.doubleValue());
                ps.setDouble(i++, taxable.doubleValue());
                ps.setDouble(i++, cgst.doubleValue());
                ps.setDouble(i++, sgst.doubleValue());
                ps.setDouble(i++, roundOff.doubleValue());
                ps.setDouble(i++, payable.doubleValue());
                ps.setString(i++, username);
                ps.setString(i++, shopName);
                ps.setString(i++, address);
                ps.setString(i++, phone);
                ps.setString(i++, gstin);
                ps.setString(i++, license);
                ps.setString(i++, state);

                if (customerRow == null) ps.setNull(i++, java.sql.Types.INTEGER);
                else ps.setLong(i++, customerRow.id);

                ps.setDouble(i++, paidNow.doubleValue());

                if (clientRef == null || clientRef.isEmpty()) ps.setNull(i, java.sql.Types.VARCHAR);
                else ps.setString(i, clientRef);

                ps.executeUpdate();

                try (ResultSet keys = ps.getGeneratedKeys()) {
                    keys.next();
                    invoiceId = keys.getLong(1);
                }
            }

            // ---- the udhaar ledger, in the same transaction as the bill ----
            if (onCredit) {

                Customers.addEntry(conn, shopId, customerRow.id, "sale", payable, "credit",
                        "Bill " + invoiceNo, invoiceId, null, username, stamp);

                if (paidNow.signum() > 0) {
                    Customers.addEntry(conn, shopId, customerRow.id, "payment", paidNow.negate(), paidMode,
                            "Paid at the time of billing", invoiceId, null, username, stamp);
                }
            }

            for (Line line : lines) {

                try (PreparedStatement ps = conn.prepareStatement(
                        "INSERT INTO invoice_items(invoice_id, medicine_id, medicine_name, batch, hsn, "
                                + "expiry, gst_rate, quantity, unit_price, discount_percent, discount_amount, "
                                + "taxable_value, cgst, sgst, line_total, cost_price) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)")) {

                    int i = 1;
                    ps.setLong(i++, invoiceId);
                    ps.setString(i++, line.medicineId);
                    ps.setString(i++, line.name);
                    ps.setString(i++, line.batch);
                    ps.setString(i++, line.hsn);
                    ps.setString(i++, line.expiry);
                    ps.setDouble(i++, line.gstRate.doubleValue());
                    ps.setInt(i++, line.quantity);
                    ps.setDouble(i++, line.unitPrice.doubleValue());
                    ps.setDouble(i++, line.discountPercent.doubleValue());
                    ps.setDouble(i++, line.amounts.discount.doubleValue());
                    ps.setDouble(i++, line.amounts.taxable.doubleValue());
                    ps.setDouble(i++, line.amounts.cgst.doubleValue());
                    ps.setDouble(i++, line.amounts.sgst.doubleValue());
                    ps.setDouble(i++, line.amounts.lineTotal.doubleValue());
                    ps.setDouble(i, line.costPrice);
                    ps.executeUpdate();
                }

                // Keep the existing sales table in step: dashboard and reports read it.
                try (PreparedStatement ps = conn.prepareStatement(
                        "INSERT INTO sales(medicineId, medicineName, customerName, quantity, unitPrice, "
                                + "totalAmount, saleDate, shop_id, invoice_id) VALUES(?,?,?,?,?,?,?,?,?)")) {

                    int i = 1;
                    ps.setString(i++, line.medicineId);
                    ps.setString(i++, line.name);
                    ps.setString(i++, customer);
                    ps.setInt(i++, line.quantity);
                    ps.setDouble(i++, line.unitPrice.doubleValue());
                    ps.setDouble(i++, line.amounts.lineTotal.doubleValue());
                    ps.setString(i++, stamp);
                    ps.setLong(i++, shopId);
                    ps.setLong(i, invoiceId);
                    ps.executeUpdate();
                }
            }

            conn.commit();

            return new Outcome(true, 201, "Invoice created", invoiceId, invoiceNo);

        } catch (Exception e) {

            try {
                if (conn != null) conn.rollback();
            } catch (Exception ignored) {
                // nothing more to do
            }

            System.out.println("Create invoice error: " + e.getMessage());
            return fail(500, "The bill could not be saved. Nothing was changed.");

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
    // Cancel an invoice (puts the stock back)
    // ---------------------------------------------------------

    public static Outcome cancelInvoice(long shopId, String username, long invoiceId) {

        Connection conn = null;

        try {

            conn = DatabaseHelper.connect();

            if (conn == null) {
                return fail(500, "Database is not available");
            }

            conn.setAutoCommit(false);

            String invoiceNo, status, mode;
            long custId;
            double invTotal, invPaid;

            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT invoice_no, status, payment_mode, COALESCE(customer_id,0), total, amount_paid "
                            + "FROM invoices WHERE id = ? AND shop_id = ?")) {

                ps.setLong(1, invoiceId);
                ps.setLong(2, shopId);

                try (ResultSet rs = ps.executeQuery()) {

                    if (!rs.next()) {
                        conn.rollback();
                        return fail(404, "Invoice not found");
                    }

                    invoiceNo = rs.getString("invoice_no");
                    status = rs.getString("status");
                    mode = rs.getString("payment_mode");
                    custId = rs.getLong(4);
                    invTotal = rs.getDouble("total");
                    invPaid = rs.getDouble("amount_paid");
                }
            }

            if (!"active".equals(status)) {
                conn.rollback();
                return fail(409, "This invoice is already cancelled");
            }

            // Once something was returned, the invoice is part of the credit note trail.
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT COUNT(*) FROM credit_notes WHERE invoice_id = ? AND shop_id = ?")) {

                ps.setLong(1, invoiceId);
                ps.setLong(2, shopId);

                try (ResultSet rs = ps.executeQuery()) {

                    if (rs.next() && rs.getInt(1) > 0) {
                        conn.rollback();
                        return fail(409, "This invoice already has returns, so it cannot be cancelled");
                    }
                }
            }

            try (PreparedStatement items = conn.prepareStatement(
                    "SELECT medicine_id, quantity FROM invoice_items WHERE invoice_id = ?")) {

                items.setLong(1, invoiceId);

                try (ResultSet rs = items.executeQuery();
                     PreparedStatement restock = conn.prepareStatement(
                             "UPDATE medicines SET quantity = quantity + ? WHERE id = ? AND shop_id = ?")) {

                    while (rs.next()) {
                        restock.setInt(1, rs.getInt("quantity"));
                        restock.setString(2, rs.getString("medicine_id"));
                        restock.setLong(3, shopId);
                        restock.executeUpdate(); // a medicine deleted since is simply skipped
                    }
                }
            }

            try (PreparedStatement ps = conn.prepareStatement(
                    "DELETE FROM sales WHERE invoice_id = ? AND shop_id = ?")) {
                ps.setLong(1, invoiceId);
                ps.setLong(2, shopId);
                ps.executeUpdate();
            }

            try (PreparedStatement ps = conn.prepareStatement(
                    "UPDATE invoices SET status = 'cancelled', cancelled_at = ?, cancelled_by = ? "
                            + "WHERE id = ? AND shop_id = ?")) {
                ps.setString(1, LocalDateTime.now().format(STAMP));
                ps.setString(2, username);
                ps.setLong(3, invoiceId);
                ps.setLong(4, shopId);
                ps.executeUpdate();
            }

            // A cancelled udhaar bill no longer counts as owed (what was paid on the spot is handed back).
            if ("credit".equals(mode) && custId > 0) {
                BigDecimal undo = Customers.money(invPaid).subtract(Customers.money(invTotal));
                if (undo.signum() != 0) {
                    Customers.addEntry(conn, shopId, custId, "cancel", undo, "credit",
                            "Bill " + invoiceNo + " cancelled", invoiceId, null, username,
                            LocalDateTime.now().format(STAMP));
                }
            }

            conn.commit();

            return new Outcome(true, 200, "Invoice cancelled and stock restored", invoiceId, invoiceNo);

        } catch (Exception e) {

            try {
                if (conn != null) conn.rollback();
            } catch (Exception ignored) {
                // nothing more to do
            }

            System.out.println("Cancel invoice error: " + e.getMessage());
            return fail(500, "The invoice could not be cancelled. Nothing was changed.");

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
    // Reading invoices (JSON text)
    // ---------------------------------------------------------

    private static String d(ResultSet rs, String column) throws Exception {
        return Json.money(BigDecimal.valueOf(rs.getDouble(column)));
    }

    /** Full invoice for printing, or null when it does not belong to this shop. */
    public static String invoiceJson(long shopId, long invoiceId) {

        try (Connection conn = DatabaseHelper.connect();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT * FROM invoices WHERE id = ? AND shop_id = ?")) {

            ps.setLong(1, invoiceId);
            ps.setLong(2, shopId);

            StringBuilder json = new StringBuilder();

            try (ResultSet rs = ps.executeQuery()) {

                if (!rs.next()) {
                    return null;
                }

                json.append("{\"id\":").append(rs.getLong("id"))
                        .append(",\"invoiceNo\":").append(Json.quote(rs.getString("invoice_no")))
                        .append(",\"date\":").append(Json.quote(rs.getString("invoice_date")))
                        .append(",\"status\":").append(Json.quote(rs.getString("status")))
                        .append(",\"paymentMode\":").append(Json.quote(rs.getString("payment_mode")))
                        .append(",\"pricesIncludeGst\":").append(rs.getInt("prices_include_gst") == 1)
                        .append(",\"createdBy\":").append(Json.quote(rs.getString("created_by")))
                        .append(",\"customerId\":").append(rs.getLong("customer_id") == 0 ? "null" : Long.toString(rs.getLong("customer_id")))
                        .append(",\"amountPaid\":").append(d(rs, "amount_paid"))
                        .append(",\"balanceDue\":").append("credit".equals(rs.getString("payment_mode")) && "active".equals(rs.getString("status"))
                                ? Json.money(BigDecimal.valueOf(rs.getDouble("total")).subtract(BigDecimal.valueOf(rs.getDouble("amount_paid"))).max(BigDecimal.ZERO))
                                : "0.00")
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
                    "SELECT * FROM invoice_items WHERE invoice_id = ? ORDER BY id")) {

                items.setLong(1, invoiceId);

                try (ResultSet rs = items.executeQuery()) {

                    boolean first = true;

                    while (rs.next()) {

                        if (!first) json.append(',');
                        first = false;

                        json.append("{\"itemId\":").append(rs.getLong("id"))
                                .append(",\"returnedQuantity\":").append(rs.getInt("returned_qty"))
                                .append(",\"medicineId\":").append(Json.quote(rs.getString("medicine_id")))
                                .append(",\"name\":").append(Json.quote(rs.getString("medicine_name")))
                                .append(",\"batch\":").append(Json.quote(rs.getString("batch")))
                                .append(",\"hsn\":").append(Json.quote(rs.getString("hsn")))
                                .append(",\"expiry\":").append(Json.quote(rs.getString("expiry")))
                                .append(",\"gstRate\":").append(BigDecimal.valueOf(rs.getDouble("gst_rate")).stripTrailingZeros().toPlainString())
                                .append(",\"quantity\":").append(rs.getInt("quantity"))
                                .append(",\"unitPrice\":").append(d(rs, "unit_price"))
                                .append(",\"discountPercent\":").append(BigDecimal.valueOf(rs.getDouble("discount_percent")).stripTrailingZeros().toPlainString())
                                .append(",\"discount\":").append(d(rs, "discount_amount"))
                                .append(",\"taxable\":").append(d(rs, "taxable_value"))
                                .append(",\"cgst\":").append(d(rs, "cgst"))
                                .append(",\"sgst\":").append(d(rs, "sgst"))
                                .append(",\"lineTotal\":").append(d(rs, "line_total"))
                                .append("}");
                    }
                }
            }

            json.append("]");

            // Returns (credit notes) already made against this invoice.
            json.append(",\"creditNotes\":[");
            BigDecimal returnedTotal = BigDecimal.ZERO;

            try (PreparedStatement notes = conn.prepareStatement(
                    "SELECT id, note_no, note_date, total FROM credit_notes "
                            + "WHERE invoice_id = ? AND shop_id = ? ORDER BY id")) {

                notes.setLong(1, invoiceId);
                notes.setLong(2, shopId);

                try (ResultSet rs = notes.executeQuery()) {

                    boolean first = true;

                    while (rs.next()) {

                        if (!first) json.append(',');
                        first = false;

                        returnedTotal = returnedTotal.add(BigDecimal.valueOf(rs.getDouble("total")));

                        json.append("{\"id\":").append(rs.getLong("id"))
                                .append(",\"noteNo\":").append(Json.quote(rs.getString("note_no")))
                                .append(",\"date\":").append(Json.quote(rs.getString("note_date")))
                                .append(",\"total\":").append(d(rs, "total"))
                                .append("}");
                    }
                }
            }

            json.append("],\"returnedTotal\":").append(Json.money(returnedTotal));

            return json.append("}").toString();

        } catch (Exception e) {
            System.out.println("Read invoice error: " + e.getMessage());
            return null;
        }
    }

    /** Recent invoices of the shop (newest first), optionally filtered by number or customer. */
    public static String listJson(long shopId, String search, int limit, int offset) {

        boolean hasSearch = search != null && !search.trim().isEmpty();

        String sql = "SELECT i.id, i.invoice_no, i.invoice_date, i.customer_name, i.payment_mode, "
                + "i.total, i.status, i.created_by, i.amount_paid, i.customer_id, "
                + "(SELECT COUNT(*) FROM invoice_items x WHERE x.invoice_id = i.id) AS item_count, "
                + "(SELECT COALESCE(SUM(c.total), 0) FROM credit_notes c WHERE c.invoice_id = i.id) AS returned_total "
                + "FROM invoices i WHERE i.shop_id = ? "
                + (hasSearch ? "AND (LOWER(i.invoice_no) LIKE ? OR LOWER(i.customer_name) LIKE ? "
                        + "OR i.customer_phone LIKE ?) " : "")
                + "ORDER BY i.id DESC LIMIT ? OFFSET ?";

        try (Connection conn = DatabaseHelper.connect();
             PreparedStatement ps = conn.prepareStatement(sql)) {

            int i = 1;
            ps.setLong(i++, shopId);

            if (hasSearch) {
                String like = "%" + search.trim().toLowerCase() + "%";
                ps.setString(i++, like);
                ps.setString(i++, like);
                ps.setString(i++, "%" + search.trim() + "%");
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
                            .append(",\"invoiceNo\":").append(Json.quote(rs.getString("invoice_no")))
                            .append(",\"date\":").append(Json.quote(rs.getString("invoice_date")))
                            .append(",\"customerName\":").append(Json.quote(rs.getString("customer_name")))
                            .append(",\"paymentMode\":").append(Json.quote(rs.getString("payment_mode")))
                            .append(",\"total\":").append(d(rs, "total"))
                            .append(",\"status\":").append(Json.quote(rs.getString("status")))
                            .append(",\"createdBy\":").append(Json.quote(rs.getString("created_by")))
                            .append(",\"itemCount\":").append(rs.getInt("item_count"))
                            .append(",\"returnedTotal\":").append(d(rs, "returned_total"))
                            .append(",\"amountPaid\":").append(d(rs, "amount_paid"))
                            .append(",\"customerId\":").append(rs.getLong("customer_id") == 0 ? "null" : Long.toString(rs.getLong("customer_id")))
                            .append("}");
                }
            }

            return json.append("]").toString();

        } catch (Exception e) {
            System.out.println("List invoices error: " + e.getMessage());
            return "[]";
        }
    }
}
