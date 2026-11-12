import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Sales, GST and stock reports, calculated on the server.
 *
 * Rules used everywhere:
 *  - Cancelled invoices are left out of every total.
 *  - Returns (credit notes) are subtracted on the date the credit note was made.
 *  - "Net" = sales minus returns. All amounts are in rupees, 2 decimals.
 */
public final class ReportService {

    private ReportService() {}

    private static final BigDecimal ZERO = BigDecimal.ZERO;

    /** Running totals for one group (a day, a GST rate, an HSN code...). */
    private static final class Agg {
        BigDecimal taxable = ZERO, cgst = ZERO, sgst = ZERO, total = ZERO, discount = ZERO;
        long qty;
        long count;

        void add(Agg other, int sign) {
            BigDecimal s = BigDecimal.valueOf(sign);
            taxable = taxable.add(other.taxable.multiply(s));
            cgst = cgst.add(other.cgst.multiply(s));
            sgst = sgst.add(other.sgst.multiply(s));
            total = total.add(other.total.multiply(s));
            discount = discount.add(other.discount.multiply(s));
            qty += sign * other.qty;
            count += sign * other.count;
        }
    }

    private static BigDecimal bd(ResultSet rs, int index) throws SQLException {
        return BigDecimal.valueOf(rs.getDouble(index));
    }

    private static String m(BigDecimal value) {
        return Json.money(value);
    }

    private static String rate(double value) {
        return BigDecimal.valueOf(value).stripTrailingZeros().toPlainString();
    }

    // ---------------------------------------------------------
    // Date helpers
    // ---------------------------------------------------------

    public static boolean validDate(String text) {
        if (text == null || !text.matches("\\d{4}-\\d{2}-\\d{2}")) return false;
        try {
            LocalDate.parse(text);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    // ---------------------------------------------------------
    // Building blocks
    // ---------------------------------------------------------

    private static Agg documentTotals(Connection c, String table, String dateColumn,
                                      String extraWhere, long shopId, String from, String to)
            throws SQLException {

        String sql = "SELECT COUNT(*), COALESCE(SUM(total),0), COALESCE(SUM(taxable),0), "
                + "COALESCE(SUM(cgst),0), COALESCE(SUM(sgst),0), COALESCE(SUM(discount),0) "
                + "FROM " + table + " WHERE shop_id = ? AND substr(" + dateColumn + ",1,10) BETWEEN ? AND ? "
                + extraWhere;

        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setLong(1, shopId);
            ps.setString(2, from);
            ps.setString(3, to);

            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                Agg a = new Agg();
                a.count = rs.getLong(1);
                a.total = bd(rs, 2);
                a.taxable = bd(rs, 3);
                a.cgst = bd(rs, 4);
                a.sgst = bd(rs, 5);
                a.discount = bd(rs, 6);
                return a;
            }
        }
    }

    /** Net sales by GST rate (key = rate), or by HSN + rate (key = "hsn|rate"). */
    private static Map<String, Agg> netLines(Connection c, long shopId, String from, String to, boolean byHsn)
            throws SQLException {

        Map<String, Agg> result = new TreeMap<>();

        String group = byHsn ? "x.hsn, x.gst_rate" : "x.gst_rate";
        String select = byHsn ? "x.hsn, x.gst_rate" : "'' , x.gst_rate";

        String invoiceSql = "SELECT " + select + ", COALESCE(SUM(x.quantity),0), COALESCE(SUM(x.taxable_value),0), "
                + "COALESCE(SUM(x.cgst),0), COALESCE(SUM(x.sgst),0), COALESCE(SUM(x.line_total),0) "
                + "FROM invoice_items x JOIN invoices d ON d.id = x.invoice_id "
                + "WHERE d.shop_id = ? AND d.status = 'active' AND substr(d.invoice_date,1,10) BETWEEN ? AND ? "
                + "GROUP BY " + group;

        String creditSql = "SELECT " + select + ", COALESCE(SUM(x.quantity),0), COALESCE(SUM(x.taxable_value),0), "
                + "COALESCE(SUM(x.cgst),0), COALESCE(SUM(x.sgst),0), COALESCE(SUM(x.line_total),0) "
                + "FROM credit_note_items x JOIN credit_notes d ON d.id = x.credit_note_id "
                + "WHERE d.shop_id = ? AND substr(d.note_date,1,10) BETWEEN ? AND ? "
                + "GROUP BY " + group;

        collectLines(c, invoiceSql, shopId, from, to, byHsn, result, 1);
        collectLines(c, creditSql, shopId, from, to, byHsn, result, -1);

        return result;
    }

    private static void collectLines(Connection c, String sql, long shopId, String from, String to,
                                     boolean byHsn, Map<String, Agg> into, int sign) throws SQLException {

        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setLong(1, shopId);
            ps.setString(2, from);
            ps.setString(3, to);

            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String hsn = byHsn ? rs.getString(1) : "";
                    String key = hsn + "|" + String.format(java.util.Locale.ROOT, "%08.2f", rs.getDouble(2));
                    Agg row = new Agg();
                    row.qty = rs.getLong(3);
                    row.taxable = bd(rs, 4);
                    row.cgst = bd(rs, 5);
                    row.sgst = bd(rs, 6);
                    row.total = bd(rs, 7);
                    into.computeIfAbsent(key, k -> new Agg()).add(row, sign);
                }
            }
        }
    }

    // Group keys look like "3004|0005.00" (HSN, then the GST rate).
    private static String keyHsn(String key) {
        return key.substring(0, key.lastIndexOf('|'));
    }

    private static double keyRate(String key) {
        return Double.parseDouble(key.substring(key.lastIndexOf('|') + 1));
    }

    // ---------------------------------------------------------
    // Summary (sales, GST, top medicines)
    // ---------------------------------------------------------

    public static String summaryJson(long shopId, String from, String to) throws SQLException {

        try (Connection c = DatabaseHelper.connect()) {

            Agg invoices = documentTotals(c, "invoices", "invoice_date", "AND status = 'active'", shopId, from, to);
            Agg returns = documentTotals(c, "credit_notes", "note_date", "", shopId, from, to);

            Agg net = new Agg();
            net.add(invoices, 1);
            net.add(returns, -1);

            // ---- payment modes ----
            Map<String, BigDecimal[]> modes = new LinkedHashMap<>();

            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT payment_mode, COALESCE(SUM(total),0) FROM invoices WHERE shop_id = ? "
                            + "AND status = 'active' AND substr(invoice_date,1,10) BETWEEN ? AND ? GROUP BY payment_mode")) {
                ps.setLong(1, shopId);
                ps.setString(2, from);
                ps.setString(3, to);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        modes.computeIfAbsent(rs.getString(1), k -> new BigDecimal[]{ZERO, ZERO})[0] = bd(rs, 2);
                    }
                }
            }

            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT refund_mode, COALESCE(SUM(total),0) FROM credit_notes WHERE shop_id = ? "
                            + "AND substr(note_date,1,10) BETWEEN ? AND ? GROUP BY refund_mode")) {
                ps.setLong(1, shopId);
                ps.setString(2, from);
                ps.setString(3, to);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        modes.computeIfAbsent(rs.getString(1), k -> new BigDecimal[]{ZERO, ZERO})[1] = bd(rs, 2);
                    }
                }
            }

            // ---- day by day ----
            Map<String, Object[]> days = new TreeMap<>(); // date -> {sales, refunds, bills}

            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT substr(invoice_date,1,10), COALESCE(SUM(total),0), COUNT(*) FROM invoices "
                            + "WHERE shop_id = ? AND status = 'active' AND substr(invoice_date,1,10) BETWEEN ? AND ? "
                            + "GROUP BY substr(invoice_date,1,10)")) {
                ps.setLong(1, shopId);
                ps.setString(2, from);
                ps.setString(3, to);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        Object[] row = days.computeIfAbsent(rs.getString(1), k -> new Object[]{ZERO, ZERO, 0L});
                        row[0] = bd(rs, 2);
                        row[2] = rs.getLong(3);
                    }
                }
            }

            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT substr(note_date,1,10), COALESCE(SUM(total),0) FROM credit_notes "
                            + "WHERE shop_id = ? AND substr(note_date,1,10) BETWEEN ? AND ? "
                            + "GROUP BY substr(note_date,1,10)")) {
                ps.setLong(1, shopId);
                ps.setString(2, from);
                ps.setString(3, to);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        Object[] row = days.computeIfAbsent(rs.getString(1), k -> new Object[]{ZERO, ZERO, 0L});
                        row[1] = bd(rs, 2);
                    }
                }
            }

            // ---- GST by rate and by HSN ----
            Map<String, Agg> byRate = netLines(c, shopId, from, to, false);
            Map<String, Agg> byHsn = netLines(c, shopId, from, to, true);

            // ---- top medicines (net of returns) ----
            List<String> top = new ArrayList<>();

            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT x.medicine_id, x.medicine_name, SUM(x.quantity - x.returned_qty) AS units, "
                            + "SUM(x.line_total - x.returned_total) AS revenue "
                            + "FROM invoice_items x JOIN invoices d ON d.id = x.invoice_id "
                            + "WHERE d.shop_id = ? AND d.status = 'active' AND substr(d.invoice_date,1,10) BETWEEN ? AND ? "
                            + "GROUP BY x.medicine_id, x.medicine_name HAVING units > 0 "
                            + "ORDER BY revenue DESC LIMIT 10")) {
                ps.setLong(1, shopId);
                ps.setString(2, from);
                ps.setString(3, to);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        top.add("{\"medicineId\":" + Json.quote(rs.getString(1))
                                + ",\"name\":" + Json.quote(rs.getString(2))
                                + ",\"units\":" + rs.getLong(3)
                                + ",\"revenue\":" + m(bd(rs, 4)) + "}");
                    }
                }
            }

            // ---- profit: selling value before GST minus what the goods cost ----
            BigDecimal soldTaxable, soldCost, returnedTaxable, returnedCost;
            long unitsSold, unitsNoCost;

            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT COALESCE(SUM(x.taxable_value),0), COALESCE(SUM(x.quantity * x.cost_price),0), "
                            + "COALESCE(SUM(x.quantity),0), "
                            + "COALESCE(SUM(CASE WHEN x.cost_price <= 0 THEN x.quantity ELSE 0 END),0) "
                            + "FROM invoice_items x JOIN invoices d ON d.id = x.invoice_id "
                            + "WHERE d.shop_id = ? AND d.status = 'active' AND substr(d.invoice_date,1,10) BETWEEN ? AND ?")) {
                ps.setLong(1, shopId);
                ps.setString(2, from);
                ps.setString(3, to);
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    soldTaxable = bd(rs, 1);
                    soldCost = bd(rs, 2);
                    unitsSold = rs.getLong(3);
                    unitsNoCost = rs.getLong(4);
                }
            }

            // Goods that came back and were not restocked are a loss: their cost stays counted.
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT COALESCE(SUM(n.taxable_value),0), "
                            + "COALESCE(SUM(CASE WHEN d.restocked = 1 THEN n.quantity * i.cost_price ELSE 0 END),0) "
                            + "FROM credit_note_items n JOIN credit_notes d ON d.id = n.credit_note_id "
                            + "JOIN invoice_items i ON i.id = n.invoice_item_id "
                            + "WHERE d.shop_id = ? AND substr(d.note_date,1,10) BETWEEN ? AND ?")) {
                ps.setLong(1, shopId);
                ps.setString(2, from);
                ps.setString(3, to);
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    returnedTaxable = bd(rs, 1);
                    returnedCost = bd(rs, 2);
                }
            }

            BigDecimal netRevenue = soldTaxable.subtract(returnedTaxable);
            BigDecimal netCost = soldCost.subtract(returnedCost);
            BigDecimal gross = netRevenue.subtract(netCost);

            // ---- purchases and input GST ----
            Agg bought;

            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT COUNT(*), COALESCE(SUM(total),0), COALESCE(SUM(taxable),0), COALESCE(SUM(cgst),0), "
                            + "COALESCE(SUM(sgst),0) FROM purchases WHERE shop_id = ? AND status = 'active' "
                            + "AND bill_date BETWEEN ? AND ?")) {
                ps.setLong(1, shopId);
                ps.setString(2, from);
                ps.setString(3, to);
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    bought = new Agg();
                    bought.count = rs.getLong(1);
                    bought.total = bd(rs, 2);
                    bought.taxable = bd(rs, 3);
                    bought.cgst = bd(rs, 4);
                    bought.sgst = bd(rs, 5);
                }
            }

            // ---- assemble ----
            StringBuilder json = new StringBuilder("{\"success\":true");
            json.append(",\"from\":").append(Json.quote(from)).append(",\"to\":").append(Json.quote(to));
            json.append(",\"profit\":{\"revenue\":").append(m(netRevenue))
                    .append(",\"cost\":").append(m(netCost))
                    .append(",\"gross\":").append(m(gross))
                    .append(",\"marginPercent\":").append(netRevenue.signum() > 0
                            ? gross.multiply(BigDecimal.valueOf(100)).divide(netRevenue, 1, RoundingMode.HALF_UP).toPlainString()
                            : "0")
                    .append(",\"unitsSold\":").append(unitsSold)
                    .append(",\"unitsWithoutCost\":").append(unitsNoCost).append("}");
            json.append(",\"purchases\":").append(totalsJson(bought));
            json.append(",\"sales\":").append(totalsJson(invoices));
            json.append(",\"returns\":").append(totalsJson(returns));
            json.append(",\"net\":").append(totalsJson(net));

            json.append(",\"paymentModes\":[");
            boolean first = true;
            for (Map.Entry<String, BigDecimal[]> e : modes.entrySet()) {
                if (!first) json.append(',');
                first = false;
                json.append("{\"mode\":").append(Json.quote(e.getKey()))
                        .append(",\"sales\":").append(m(e.getValue()[0]))
                        .append(",\"refunds\":").append(m(e.getValue()[1]))
                        .append(",\"net\":").append(m(e.getValue()[0].subtract(e.getValue()[1]))).append("}");
            }

            json.append("],\"daily\":[");
            first = true;
            for (Map.Entry<String, Object[]> e : days.entrySet()) {
                if (!first) json.append(',');
                first = false;
                BigDecimal sales = (BigDecimal) e.getValue()[0], refunds = (BigDecimal) e.getValue()[1];
                json.append("{\"date\":").append(Json.quote(e.getKey()))
                        .append(",\"sales\":").append(m(sales))
                        .append(",\"refunds\":").append(m(refunds))
                        .append(",\"net\":").append(m(sales.subtract(refunds)))
                        .append(",\"bills\":").append(e.getValue()[2]).append("}");
            }

            json.append("],\"byRate\":[");
            first = true;
            for (Map.Entry<String, Agg> e : byRate.entrySet()) {
                if (!first) json.append(',');
                first = false;
                json.append("{\"rate\":").append(rate(keyRate(e.getKey())))
                        .append(",\"taxable\":").append(m(e.getValue().taxable))
                        .append(",\"cgst\":").append(m(e.getValue().cgst))
                        .append(",\"sgst\":").append(m(e.getValue().sgst))
                        .append(",\"total\":").append(m(e.getValue().total)).append("}");
            }

            json.append("],\"hsn\":[");
            first = true;
            for (Map.Entry<String, Agg> e : byHsn.entrySet()) {
                if (!first) json.append(',');
                first = false;
                json.append("{\"hsn\":").append(Json.quote(keyHsn(e.getKey())))
                        .append(",\"rate\":").append(rate(keyRate(e.getKey())))
                        .append(",\"quantity\":").append(e.getValue().qty)
                        .append(",\"taxable\":").append(m(e.getValue().taxable))
                        .append(",\"cgst\":").append(m(e.getValue().cgst))
                        .append(",\"sgst\":").append(m(e.getValue().sgst))
                        .append(",\"total\":").append(m(e.getValue().total)).append("}");
            }

            json.append("],\"top\":[").append(String.join(",", top)).append("]}");
            return json.toString();
        }
    }

    private static String totalsJson(Agg a) {
        return "{\"count\":" + a.count
                + ",\"total\":" + m(a.total)
                + ",\"taxable\":" + m(a.taxable)
                + ",\"cgst\":" + m(a.cgst)
                + ",\"sgst\":" + m(a.sgst)
                + ",\"discount\":" + m(a.discount) + "}";
    }

    // ---------------------------------------------------------
    // Stock + expiry
    // ---------------------------------------------------------

    public static String stockJson(long shopId, int expiryDays, int lowLimit) throws SQLException {

        String today = LocalDate.now().toString();
        String until = LocalDate.now().plusDays(expiryDays).toString();

        try (Connection c = DatabaseHelper.connect()) {

            long medicines, units;
            BigDecimal value;

            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT COUNT(*), COALESCE(SUM(quantity),0), COALESCE(SUM(quantity * price),0) "
                            + "FROM medicines WHERE shop_id = ?")) {
                ps.setLong(1, shopId);
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    medicines = rs.getLong(1);
                    units = rs.getLong(2);
                    value = bd(rs, 3);
                }
            }

            StringBuilder json = new StringBuilder("{\"success\":true");
            json.append(",\"medicines\":").append(medicines)
                    .append(",\"units\":").append(units)
                    .append(",\"value\":").append(m(value))
                    .append(",\"expiryDays\":").append(expiryDays)
                    .append(",\"lowLimit\":").append(lowLimit);

            json.append(",\"categories\":[");
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT COALESCE(NULLIF(TRIM(category),''),'Uncategorized'), COALESCE(SUM(quantity),0), "
                            + "COALESCE(SUM(quantity * price),0) FROM medicines WHERE shop_id = ? "
                            + "GROUP BY 1 ORDER BY 3 DESC")) {
                ps.setLong(1, shopId);
                try (ResultSet rs = ps.executeQuery()) {
                    boolean first = true;
                    while (rs.next()) {
                        if (!first) json.append(',');
                        first = false;
                        json.append("{\"name\":").append(Json.quote(rs.getString(1)))
                                .append(",\"units\":").append(rs.getLong(2))
                                .append(",\"value\":").append(m(bd(rs, 3))).append("}");
                    }
                }
            }

            json.append("],\"expired\":").append(medicineList(c, shopId,
                    "quantity > 0 AND expiryDate < ?", new String[]{today}, "expiryDate ASC"));
            json.append(",\"expiring\":").append(medicineList(c, shopId,
                    "quantity > 0 AND expiryDate >= ? AND expiryDate <= ?", new String[]{today, until}, "expiryDate ASC"));
            json.append(",\"low\":").append(medicineList(c, shopId,
                    "quantity <= " + Math.max(0, lowLimit), new String[]{}, "quantity ASC, name ASC"));

            return json.append("}").toString();
        }
    }

    private static String medicineList(Connection c, long shopId, String where, String[] args, String order)
            throws SQLException {

        String sql = "SELECT id, name, batchNumber, category, quantity, price, expiryDate, gst_rate "
                + "FROM medicines WHERE shop_id = ? AND " + where + " ORDER BY " + order + " LIMIT 300";

        try (PreparedStatement ps = c.prepareStatement(sql)) {
            int i = 1;
            ps.setLong(i++, shopId);
            for (String arg : args) ps.setString(i++, arg);

            StringBuilder json = new StringBuilder("[");

            try (ResultSet rs = ps.executeQuery()) {
                boolean first = true;
                while (rs.next()) {
                    if (!first) json.append(',');
                    first = false;
                    json.append("{\"id\":").append(Json.quote(rs.getString(1)))
                            .append(",\"name\":").append(Json.quote(rs.getString(2)))
                            .append(",\"batch\":").append(Json.quote(rs.getString(3)))
                            .append(",\"category\":").append(Json.quote(rs.getString(4)))
                            .append(",\"quantity\":").append(rs.getInt(5))
                            .append(",\"price\":").append(m(bd(rs, 6)))
                            .append(",\"expiry\":").append(Json.quote(rs.getString(7)))
                            .append(",\"gstRate\":").append(rate(rs.getDouble(8)))
                            .append(",\"value\":").append(m(bd(rs, 6).multiply(BigDecimal.valueOf(rs.getInt(5)))))
                            .append("}");
                }
            }

            return json.append("]").toString();
        }
    }

    // ---------------------------------------------------------
    // CSV exports (open directly in Excel)
    // ---------------------------------------------------------

    public static final class Csv {
        public final String filename;
        public final String body;

        Csv(String filename, String body) {
            this.filename = filename;
            this.body = body;
        }
    }

    /** Quotes a value; stops spreadsheet formulas such as =CMD(...) by prefixing an apostrophe. */
    static String cell(Object value) {
        String text = value == null ? "" : String.valueOf(value);
        if (!text.isEmpty() && "=+-@\t\r".indexOf(text.charAt(0)) >= 0 && !text.matches("-?\\d+(\\.\\d+)?")) {
            text = "'" + text;
        }
        return "\"" + text.replace("\"", "\"\"") + "\"";
    }

    private static void line(StringBuilder out, Object... cells) {
        for (int i = 0; i < cells.length; i++) {
            if (i > 0) out.append(',');
            out.append(cell(cells[i]));
        }
        out.append("\r\n");
    }

    private static String money(double value) {
        return Json.money(BigDecimal.valueOf(value));
    }

    private static String neg(double value) {
        return Json.money(BigDecimal.valueOf(value).negate());
    }

    /** type: register | items | hsn | rate | inventory | expiry */
    public static Csv csv(long shopId, String type, String from, String to, int expiryDays) throws SQLException {

        StringBuilder out = new StringBuilder("﻿"); // BOM: Excel shows rupee signs and other languages correctly
        String name;

        try (Connection c = DatabaseHelper.connect()) {

            switch (type) {

                case "register": {
                    name = "gst-sales-register-" + from + "-to-" + to;
                    line(out, "Type", "Number", "Date", "Customer", "Customer GSTIN", "Payment / refund mode",
                            "Taxable", "CGST", "SGST", "Discount", "Round off", "Total", "Status", "Against invoice");

                    List<String[]> rows = new ArrayList<>(); // sortKey, ... filled below

                    try (PreparedStatement ps = c.prepareStatement(
                            "SELECT invoice_no, invoice_date, customer_name, customer_gstin, payment_mode, taxable, cgst, "
                                    + "sgst, discount, round_off, total, status FROM invoices WHERE shop_id = ? "
                                    + "AND substr(invoice_date,1,10) BETWEEN ? AND ? ORDER BY invoice_date, id")) {
                        ps.setLong(1, shopId);
                        ps.setString(2, from);
                        ps.setString(3, to);
                        try (ResultSet rs = ps.executeQuery()) {
                            while (rs.next()) {
                                boolean cancelled = !"active".equals(rs.getString(12));
                                rows.add(new String[]{rs.getString(2), "Invoice", rs.getString(1), rs.getString(2),
                                        rs.getString(3), rs.getString(4), rs.getString(5),
                                        cancelled ? "0.00" : money(rs.getDouble(6)), cancelled ? "0.00" : money(rs.getDouble(7)),
                                        cancelled ? "0.00" : money(rs.getDouble(8)), cancelled ? "0.00" : money(rs.getDouble(9)),
                                        cancelled ? "0.00" : money(rs.getDouble(10)), cancelled ? "0.00" : money(rs.getDouble(11)),
                                        cancelled ? "Cancelled" : "Active", ""});
                            }
                        }
                    }

                    try (PreparedStatement ps = c.prepareStatement(
                            "SELECT note_no, note_date, customer_name, customer_gstin, refund_mode, taxable, cgst, sgst, "
                                    + "discount, round_off, total, invoice_no FROM credit_notes WHERE shop_id = ? "
                                    + "AND substr(note_date,1,10) BETWEEN ? AND ? ORDER BY note_date, id")) {
                        ps.setLong(1, shopId);
                        ps.setString(2, from);
                        ps.setString(3, to);
                        try (ResultSet rs = ps.executeQuery()) {
                            while (rs.next()) {
                                rows.add(new String[]{rs.getString(2), "Credit note", rs.getString(1), rs.getString(2),
                                        rs.getString(3), rs.getString(4), rs.getString(5),
                                        neg(rs.getDouble(6)), neg(rs.getDouble(7)), neg(rs.getDouble(8)),
                                        neg(rs.getDouble(9)), neg(rs.getDouble(10)), neg(rs.getDouble(11)),
                                        "Active", rs.getString(12)});
                            }
                        }
                    }

                    rows.sort((a, b) -> a[0].compareTo(b[0]));
                    for (String[] r : rows) {
                        Object[] cells = new Object[14];
                        System.arraycopy(r, 1, cells, 0, 14);
                        line(out, cells);
                    }
                    break;
                }

                case "items": {
                    name = "gst-item-wise-sales-" + from + "-to-" + to;
                    line(out, "Date", "Document", "Number", "Medicine ID", "Medicine", "Batch", "HSN", "GST %",
                            "Quantity", "MRP", "Discount %", "Taxable", "CGST", "SGST", "Total");

                    try (PreparedStatement ps = c.prepareStatement(
                            "SELECT d.invoice_date, d.invoice_no, x.medicine_id, x.medicine_name, x.batch, x.hsn, x.gst_rate, "
                                    + "x.quantity, x.unit_price, x.discount_percent, x.taxable_value, x.cgst, x.sgst, x.line_total "
                                    + "FROM invoice_items x JOIN invoices d ON d.id = x.invoice_id WHERE d.shop_id = ? "
                                    + "AND d.status = 'active' AND substr(d.invoice_date,1,10) BETWEEN ? AND ? "
                                    + "ORDER BY d.invoice_date, d.id, x.id")) {
                        ps.setLong(1, shopId);
                        ps.setString(2, from);
                        ps.setString(3, to);
                        try (ResultSet rs = ps.executeQuery()) {
                            while (rs.next()) {
                                line(out, rs.getString(1), "Invoice", rs.getString(2), rs.getString(3), rs.getString(4),
                                        rs.getString(5), rs.getString(6), rate(rs.getDouble(7)), rs.getInt(8),
                                        money(rs.getDouble(9)), rate(rs.getDouble(10)), money(rs.getDouble(11)),
                                        money(rs.getDouble(12)), money(rs.getDouble(13)), money(rs.getDouble(14)));
                            }
                        }
                    }

                    try (PreparedStatement ps = c.prepareStatement(
                            "SELECT d.note_date, d.note_no, x.medicine_id, x.medicine_name, x.batch, x.hsn, x.gst_rate, "
                                    + "x.quantity, x.unit_price, x.discount_percent, x.taxable_value, x.cgst, x.sgst, x.line_total "
                                    + "FROM credit_note_items x JOIN credit_notes d ON d.id = x.credit_note_id WHERE d.shop_id = ? "
                                    + "AND substr(d.note_date,1,10) BETWEEN ? AND ? ORDER BY d.note_date, d.id, x.id")) {
                        ps.setLong(1, shopId);
                        ps.setString(2, from);
                        ps.setString(3, to);
                        try (ResultSet rs = ps.executeQuery()) {
                            while (rs.next()) {
                                line(out, rs.getString(1), "Credit note", rs.getString(2), rs.getString(3), rs.getString(4),
                                        rs.getString(5), rs.getString(6), rate(rs.getDouble(7)), -rs.getInt(8),
                                        money(rs.getDouble(9)), rate(rs.getDouble(10)), neg(rs.getDouble(11)),
                                        neg(rs.getDouble(12)), neg(rs.getDouble(13)), neg(rs.getDouble(14)));
                            }
                        }
                    }
                    break;
                }

                case "hsn": {
                    name = "hsn-summary-" + from + "-to-" + to;
                    line(out, "HSN", "GST %", "Net quantity", "Taxable", "CGST", "SGST", "Total");
                    for (Map.Entry<String, Agg> e : netLines(c, shopId, from, to, true).entrySet()) {
                        Agg a = e.getValue();
                        line(out, keyHsn(e.getKey()), rate(keyRate(e.getKey())), a.qty,
                                m(a.taxable), m(a.cgst), m(a.sgst), m(a.total));
                    }
                    break;
                }

                case "rate": {
                    name = "gst-rate-wise-summary-" + from + "-to-" + to;
                    line(out, "GST %", "Taxable", "CGST", "SGST", "Total");
                    for (Map.Entry<String, Agg> e : netLines(c, shopId, from, to, false).entrySet()) {
                        Agg a = e.getValue();
                        line(out, rate(keyRate(e.getKey())), m(a.taxable), m(a.cgst), m(a.sgst), m(a.total));
                    }
                    break;
                }

                case "inventory": {
                    name = "inventory-" + LocalDate.now();
                    line(out, "ID", "Name", "Batch", "Category", "Manufacturer", "Quantity", "MRP", "GST %", "HSN",
                            "Expiry", "Stock value (MRP)");
                    try (PreparedStatement ps = c.prepareStatement(
                            "SELECT id, name, batchNumber, category, manufacturer, quantity, price, gst_rate, hsn, expiryDate "
                                    + "FROM medicines WHERE shop_id = ? ORDER BY name COLLATE NOCASE")) {
                        ps.setLong(1, shopId);
                        try (ResultSet rs = ps.executeQuery()) {
                            while (rs.next()) {
                                line(out, rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5),
                                        rs.getInt(6), money(rs.getDouble(7)), rate(rs.getDouble(8)), rs.getString(9),
                                        rs.getString(10), Json.money(BigDecimal.valueOf(rs.getDouble(7))
                                                .multiply(BigDecimal.valueOf(rs.getInt(6)))));
                            }
                        }
                    }
                    break;
                }

                case "expiry": {
                    name = "expiry-report-" + LocalDate.now();
                    line(out, "ID", "Name", "Batch", "Quantity", "Expiry", "Status", "Stock value (MRP)");
                    String today = LocalDate.now().toString();
                    String until = LocalDate.now().plusDays(expiryDays).toString();
                    try (PreparedStatement ps = c.prepareStatement(
                            "SELECT id, name, batchNumber, quantity, expiryDate, price FROM medicines WHERE shop_id = ? "
                                    + "AND quantity > 0 AND expiryDate <= ? ORDER BY expiryDate, name")) {
                        ps.setLong(1, shopId);
                        ps.setString(2, until);
                        try (ResultSet rs = ps.executeQuery()) {
                            while (rs.next()) {
                                String expiry = rs.getString(5);
                                line(out, rs.getString(1), rs.getString(2), rs.getString(3), rs.getInt(4), expiry,
                                        expiry.compareTo(today) < 0 ? "Expired" : "Expiring within " + expiryDays + " days",
                                        Json.money(BigDecimal.valueOf(rs.getDouble(6))
                                                .multiply(BigDecimal.valueOf(rs.getInt(4)))));
                            }
                        }
                    }
                    break;
                }

                case "purchases": {
                    name = "purchase-register-" + from + "-to-" + to;
                    line(out, "Purchase no.", "Supplier bill no.", "Bill date", "Supplier", "Supplier GSTIN", "Taxable",
                            "CGST", "SGST", "Total", "Paid at entry", "Status");
                    try (PreparedStatement ps = c.prepareStatement(
                            "SELECT purchase_no, bill_no, bill_date, supplier_name, supplier_gstin, taxable, cgst, sgst, "
                                    + "total, amount_paid, status FROM purchases WHERE shop_id = ? AND bill_date BETWEEN ? AND ? "
                                    + "ORDER BY bill_date, id")) {
                        ps.setLong(1, shopId);
                        ps.setString(2, from);
                        ps.setString(3, to);
                        try (ResultSet rs = ps.executeQuery()) {
                            while (rs.next()) {
                                boolean live = "active".equals(rs.getString(11));
                                line(out, rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5),
                                        live ? money(rs.getDouble(6)) : "0.00", live ? money(rs.getDouble(7)) : "0.00",
                                        live ? money(rs.getDouble(8)) : "0.00", live ? money(rs.getDouble(9)) : "0.00",
                                        live ? money(rs.getDouble(10)) : "0.00", live ? "Active" : "Cancelled");
                            }
                        }
                    }
                    break;
                }

                case "profit": {
                    name = "profit-by-medicine-" + from + "-to-" + to;
                    line(out, "Medicine", "Units sold", "Units returned", "Sales before GST", "Cost", "Gross profit", "Cost known?");
                    try (PreparedStatement ps = c.prepareStatement(
                            "SELECT x.medicine_name, SUM(x.quantity), SUM(x.returned_qty), SUM(x.taxable_value - x.returned_taxable), "
                                    + "SUM((x.quantity - x.returned_qty) * x.cost_price), MIN(x.cost_price) "
                                    + "FROM invoice_items x JOIN invoices d ON d.id = x.invoice_id "
                                    + "WHERE d.shop_id = ? AND d.status = 'active' AND substr(d.invoice_date,1,10) BETWEEN ? AND ? "
                                    + "GROUP BY x.medicine_name ORDER BY 4 DESC")) {
                        ps.setLong(1, shopId);
                        ps.setString(2, from);
                        ps.setString(3, to);
                        try (ResultSet rs = ps.executeQuery()) {
                            while (rs.next()) {
                                line(out, rs.getString(1), rs.getLong(2), rs.getLong(3), money(rs.getDouble(4)),
                                        money(rs.getDouble(5)), money(rs.getDouble(4) - rs.getDouble(5)),
                                        rs.getDouble(6) > 0 ? "Yes" : "Partly / No");
                            }
                        }
                    }
                    break;
                }

                case "supplier-dues": {
                    name = "supplier-dues-" + LocalDate.now();
                    line(out, "Supplier", "Phone", "GSTIN", "Amount payable", "Last activity");
                    try (PreparedStatement ps = c.prepareStatement(
                            "SELECT s.name, s.phone, s.gstin, SUM(l.amount) AS bal, MAX(l.entry_date) "
                                    + "FROM suppliers s JOIN supplier_ledger l ON l.supplier_id = s.id "
                                    + "WHERE s.shop_id = ? GROUP BY s.id HAVING bal > 0.004 ORDER BY bal DESC")) {
                        ps.setLong(1, shopId);
                        try (ResultSet rs = ps.executeQuery()) {
                            while (rs.next()) {
                                line(out, rs.getString(1), rs.getString(2), rs.getString(3),
                                        money(rs.getDouble(4)), rs.getString(5));
                            }
                        }
                    }
                    break;
                }

                case "dues": {
                    name = "udhaar-dues-" + LocalDate.now();
                    line(out, "Customer", "Phone", "Credit limit", "Amount due", "Last activity");
                    try (PreparedStatement ps = c.prepareStatement(
                            "SELECT c.name, c.phone, c.credit_limit, SUM(l.amount) AS bal, MAX(l.entry_date) "
                                    + "FROM customers c JOIN customer_ledger l ON l.customer_id = c.id "
                                    + "WHERE c.shop_id = ? GROUP BY c.id HAVING bal > 0.004 ORDER BY bal DESC")) {
                        ps.setLong(1, shopId);
                        try (ResultSet rs = ps.executeQuery()) {
                            while (rs.next()) {
                                line(out, rs.getString(1), rs.getString(2), money(rs.getDouble(3)),
                                        money(rs.getDouble(4)), rs.getString(5));
                            }
                        }
                    }
                    break;
                }

                default:
                    return null;
            }
        }

        return new Csv(name + ".csv", out.toString());
    }
}
