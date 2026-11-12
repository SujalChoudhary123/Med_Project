import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * Who changed what. Rows are only added, there is no API to edit or remove them.
 * A failure to write here never stops the work itself.
 */
public final class Audit {

    private Audit() {}

    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    public static void record(long shopId, String username, String role, String action,
                              String target, String detail, String ip) {

        try (Connection c = DatabaseHelper.connect();
             PreparedStatement ps = c.prepareStatement(
                     "INSERT INTO audit_log(shop_id, at, username, role, action, target, detail, ip) "
                             + "VALUES(?,?,?,?,?,?,?,?)")) {

            ps.setLong(1, shopId);
            ps.setString(2, LocalDateTime.now().format(STAMP));
            ps.setString(3, username == null ? "" : username);
            ps.setString(4, role == null ? "" : role);
            ps.setString(5, action);
            ps.setString(6, target == null ? "" : cut(target, 120));
            ps.setString(7, detail == null ? "" : cut(detail, 300));
            ps.setString(8, ip == null ? "" : cut(ip, 60));
            ps.executeUpdate();

        } catch (Exception e) {
            System.out.println("Audit write failed (" + action + "): " + e.getMessage());
        }
    }

    private static String cut(String text, int max) {
        return text.length() > max ? text.substring(0, max) : text;
    }

    /**
     * Turns "POST /api/invoices/12/cancel" into action "invoice.cancel" and target "12".
     * Returns null for requests that change nothing worth recording.
     */
    public static String[] describe(String method, String path) {

        String[] p = path.replaceAll("^/api/", "").split("/");
        String area = p[0];
        String id = p.length > 1 ? p[1] : "";
        String sub = p.length > 2 ? p[2] : "";
        String m = method.toUpperCase(Locale.ROOT);

        switch (area) {
            case "medicines":
                if ("bulk".equals(id)) return new String[]{"medicine.import", ""};
                return new String[]{"medicine." + verb(m), id};
            case "sales":
                return new String[]{"sale.create", ""};
            case "invoices":
                if ("return".equals(sub)) return new String[]{"invoice.return", id};
                if ("cancel".equals(sub)) return new String[]{"invoice.cancel", id};
                return new String[]{"invoice.create", id};
            case "customers":
                if ("payments".equals(sub)) return new String[]{"customer.payment", id};
                return new String[]{"customer." + verb(m), id};
            case "suppliers":
                if ("payments".equals(sub)) return new String[]{"supplier.payment", id};
                return new String[]{"supplier." + verb(m), id};
            case "purchases":
                if ("cancel".equals(sub)) return new String[]{"purchase.cancel", id};
                return new String[]{"purchase.create", id};
            case "team":
                return new String[]{"team." + verb(m), id};
            case "shop":
                return new String[]{"shop.update", ""};
            case "profile":
                return new String[]{"account.update", ""};
            case "change-password":
                return new String[]{"account.password", ""};
            default:
                return null;
        }
    }

    private static String verb(String method) {
        switch (method) {
            case "POST": return "add";
            case "PUT": return "update";
            case "DELETE": return "delete";
            default: return method.toLowerCase(Locale.ROOT);
        }
    }

    /** Newest first. Owners only (checked by the route). */
    public static String listJson(long shopId, String user, String action, int limit, int offset) {

        boolean hasUser = user != null && !user.trim().isEmpty();
        boolean hasAction = action != null && !action.trim().isEmpty();

        String sql = "SELECT id, at, username, role, action, target, detail, ip FROM audit_log WHERE shop_id = ? "
                + (hasUser ? "AND LOWER(username) = LOWER(?) " : "")
                + (hasAction ? "AND action LIKE ? " : "")
                + "ORDER BY id DESC LIMIT ? OFFSET ?";

        try (Connection c = DatabaseHelper.connect();
             PreparedStatement ps = c.prepareStatement(sql)) {

            int i = 1;
            ps.setLong(i++, shopId);
            if (hasUser) ps.setString(i++, user.trim());
            if (hasAction) ps.setString(i++, action.trim() + "%");
            ps.setInt(i++, Math.max(1, Math.min(limit, 500)));
            ps.setInt(i, Math.max(0, offset));

            StringBuilder json = new StringBuilder("{\"success\":true,\"entries\":[");

            try (ResultSet rs = ps.executeQuery()) {
                boolean first = true;
                while (rs.next()) {
                    if (!first) json.append(',');
                    first = false;
                    json.append("{\"id\":").append(rs.getLong(1))
                            .append(",\"at\":").append(Json.quote(rs.getString(2)))
                            .append(",\"user\":").append(Json.quote(rs.getString(3)))
                            .append(",\"role\":").append(Json.quote(rs.getString(4)))
                            .append(",\"action\":").append(Json.quote(rs.getString(5)))
                            .append(",\"target\":").append(Json.quote(rs.getString(6)))
                            .append(",\"detail\":").append(Json.quote(rs.getString(7)))
                            .append(",\"ip\":").append(Json.quote(rs.getString(8)))
                            .append("}");
                }
            }

            return json.append("]}").toString();

        } catch (SQLException e) {
            System.out.println("Audit list error: " + e.getMessage());
            return "{\"success\":false,\"error\":\"Could not load the activity log\"}";
        }
    }
}
