import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * "Download all my data": one ZIP of CSV files holding everything that belongs
 * to the caller's shop. Passwords and login sessions are never included.
 */
public final class Export {

    private Export() {}

    private static Map<String, String> queries() {

        Map<String, String> q = new LinkedHashMap<>();

        q.put("shop.csv", "SELECT id, name, address, phone, gstin, drug_license, state, invoice_prefix, prices_include_gst "
                + "FROM shops WHERE id = ?");
        q.put("medicines.csv", "SELECT * FROM medicines WHERE shop_id = ? ORDER BY name");
        q.put("customers.csv", "SELECT * FROM customers WHERE shop_id = ? ORDER BY id");
        q.put("customer_ledger.csv", "SELECT * FROM customer_ledger WHERE shop_id = ? ORDER BY id");
        q.put("invoices.csv", "SELECT * FROM invoices WHERE shop_id = ? ORDER BY id");
        q.put("invoice_items.csv", "SELECT x.* FROM invoice_items x JOIN invoices d ON d.id = x.invoice_id "
                + "WHERE d.shop_id = ? ORDER BY x.id");
        q.put("credit_notes.csv", "SELECT * FROM credit_notes WHERE shop_id = ? ORDER BY id");
        q.put("credit_note_items.csv", "SELECT x.* FROM credit_note_items x JOIN credit_notes d ON d.id = x.credit_note_id "
                + "WHERE d.shop_id = ? ORDER BY x.id");
        q.put("suppliers.csv", "SELECT * FROM suppliers WHERE shop_id = ? ORDER BY id");
        q.put("supplier_ledger.csv", "SELECT * FROM supplier_ledger WHERE shop_id = ? ORDER BY id");
        q.put("purchases.csv", "SELECT * FROM purchases WHERE shop_id = ? ORDER BY id");
        q.put("purchase_items.csv", "SELECT x.* FROM purchase_items x JOIN purchases d ON d.id = x.purchase_id "
                + "WHERE d.shop_id = ? ORDER BY x.id");
        q.put("activity_log.csv", "SELECT at, username, role, action, target, detail, ip FROM audit_log "
                + "WHERE shop_id = ? ORDER BY id");

        return q;
    }

    public static byte[] shopZip(long shopId) throws Exception {

        ByteArrayOutputStream bytes = new ByteArrayOutputStream();

        try (ZipOutputStream zip = new ZipOutputStream(bytes);
             Connection c = DatabaseHelper.connect()) {

            for (Map.Entry<String, String> entry : queries().entrySet()) {
                zip.putNextEntry(new ZipEntry(entry.getKey()));
                zip.write(tableCsv(c, entry.getValue(), shopId).getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
        }

        return bytes.toByteArray();
    }

    private static String tableCsv(Connection c, String sql, long shopId) throws SQLException {

        StringBuilder out = new StringBuilder("﻿");

        try (PreparedStatement ps = c.prepareStatement(sql)) {

            ps.setLong(1, shopId);

            try (ResultSet rs = ps.executeQuery()) {

                ResultSetMetaData meta = rs.getMetaData();
                int columns = meta.getColumnCount();

                for (int i = 1; i <= columns; i++) {
                    if (i > 1) out.append(',');
                    out.append(ReportService.cell(meta.getColumnLabel(i)));
                }

                out.append("\r\n");

                while (rs.next()) {
                    for (int i = 1; i <= columns; i++) {
                        if (i > 1) out.append(',');
                        out.append(ReportService.cell(rs.getObject(i)));
                    }
                    out.append("\r\n");
                }
            }
        }

        return out.toString();
    }
}
