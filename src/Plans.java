import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Free launch, free trial, paid period and the platform admin.
 *
 * Until PAID_START is set (and that day has come) MedTrack is free for everyone.
 * Setting PAID_START to a date is the only step needed to begin charging: every
 * existing shop keeps working until that date, so nobody is locked out suddenly.
 *
 * A shop whose time has run out keeps full read access and can still download
 * its data. Only changes are blocked. The owner of the platform extends a shop
 * from the admin page after receiving payment.
 */
public final class Plans {

    private Plans() {}

    public static final int TRIAL_DAYS = Math.max(1, Config.getInt("TRIAL_DAYS", 14));
    public static final int GRACE_DAYS = Math.max(0, Config.getInt("GRACE_DAYS", 3));

    /** The day paid plans begin. null = free for everyone, with no end date. */
    public static final LocalDate PAID_START = date(Config.get("PAID_START", ""));

    private static final Set<String> ADMIN_EMAILS = Arrays.stream(Config.get("ADMIN_EMAILS", "").split(","))
            .map(value -> value.trim().toLowerCase(Locale.ROOT))
            .filter(value -> !value.isEmpty())
            .collect(Collectors.toSet());

    public static final class State {
        public String plan = "trial";
        public String state = "trial"; // free | trial | active | grace | expired | disabled
        public String endsOn = "";
        public long daysLeft;
        public boolean canWrite = true;
        public boolean disabled;
    }

    /** Pure rule, kept apart from the database so it is easy to test. */
    static State evaluate(String plan, String trialEnds, String paidUntil, boolean disabled,
                          LocalDate today, LocalDate paidStart) {

        State s = new State();
        s.plan = plan == null ? "trial" : plan;
        s.disabled = disabled;

        LocalDate paid = date(paidUntil);
        LocalDate trial = date(trialEnds);
        boolean paidNow = paid != null && !paid.isBefore(today);

        if (!disabled && !paidNow && (paidStart == null || today.isBefore(paidStart))) {
            s.state = "free";
            if (paidStart != null) {
                s.endsOn = paidStart.toString();
                s.daysLeft = ChronoUnit.DAYS.between(today, paidStart);
            }
            return s;
        }

        // A shop from the free period keeps working up to the day charging starts.
        if (paidStart != null && (trial == null || trial.isBefore(paidStart))) {
            trial = paidStart;
        }

        LocalDate end = paid != null && (trial == null || !paid.isBefore(trial)) ? paid : trial;

        if (end != null) {
            s.endsOn = end.toString();
            s.daysLeft = ChronoUnit.DAYS.between(today, end);
        }

        if (disabled) {
            s.state = "disabled";
            s.canWrite = false;
        } else if (end == null || end.plusDays(GRACE_DAYS).isBefore(today)) {
            s.state = "expired";
            s.canWrite = false;
        } else if (end.isBefore(today)) {
            s.state = "grace";
        } else {
            s.state = end.equals(paid) ? "active" : "trial";
        }

        return s;
    }

    private static LocalDate date(String text) {
        try {
            return text == null || text.isEmpty() ? null : LocalDate.parse(text);
        } catch (Exception e) {
            return null;
        }
    }

    public static State stateOf(long shopId) {

        try (Connection c = DatabaseHelper.connect();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT plan, trial_ends, paid_until, disabled FROM shops WHERE id = ?")) {

            ps.setLong(1, shopId);

            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return evaluate(rs.getString(1), rs.getString(2), rs.getString(3),
                            rs.getInt(4) == 1, LocalDate.now(), PAID_START);
                }
            }

        } catch (Exception e) {
            System.out.println("Plan lookup error: " + e.getMessage());
        }

        // Never lock a shop out because of a temporary database problem.
        return new State();
    }

    private static String stateJson(State s) {
        return "\"plan\":" + Json.quote(s.plan)
                + ",\"state\":" + Json.quote(s.state)
                + ",\"endsOn\":" + Json.quote(s.endsOn)
                + ",\"daysLeft\":" + s.daysLeft
                + ",\"canWrite\":" + s.canWrite;
    }

    /** Prices and contact details, shown on the public landing page too. */
    public static String publicJson() {
        boolean free = PAID_START == null || LocalDate.now().isBefore(PAID_START);
        return "{\"success\":true"
                + ",\"free\":" + free
                + ",\"paidStart\":" + Json.quote(PAID_START == null ? "" : PAID_START.toString())
                + ",\"trialDays\":" + TRIAL_DAYS
                + ",\"priceMonthly\":" + Math.max(0, Config.getInt("PLAN_PRICE_MONTHLY", 499))
                + ",\"priceYearly\":" + Math.max(0, Config.getInt("PLAN_PRICE_YEARLY", 4999))
                + ",\"supportPhone\":" + Json.quote(Config.get("SUPPORT_PHONE", ""))
                + ",\"supportEmail\":" + Json.quote(Config.get("SUPPORT_EMAIL", ""))
                + "}";
    }

    public static String statusJson(long shopId) {
        return "{\"success\":true," + stateJson(stateOf(shopId))
                + ",\"payUpi\":" + Json.quote(Config.get("PAY_UPI_ID", ""))
                + ",\"payNote\":" + Json.quote(Config.get("PAY_NOTE", ""))
                + ",\"pricing\":" + publicJson()
                + "}";
    }

    // ---------------------------------------------------------
    // Platform admin
    // ---------------------------------------------------------

    public static boolean isAdminEmail(String email) {
        return email != null && ADMIN_EMAILS.contains(email.trim().toLowerCase(Locale.ROOT));
    }

    public static boolean isAdmin(String username) {
        String[] profile = DatabaseHelper.getUserProfile(username);
        return profile != null && profile.length > 1 && isAdminEmail(profile[1]);
    }

    public static String adminListJson() {

        String sql = "SELECT s.id, s.name, s.phone, s.plan, s.trial_ends, s.paid_until, s.disabled, s.created_at, "
                + "(SELECT u.username FROM users u WHERE u.shop_id = s.id AND u.role = 'owner' ORDER BY u.id LIMIT 1) AS owner, "
                + "(SELECT u.email FROM users u WHERE u.shop_id = s.id AND u.role = 'owner' ORDER BY u.id LIMIT 1) AS email, "
                + "(SELECT COUNT(*) FROM users u WHERE u.shop_id = s.id) AS staff, "
                + "(SELECT COUNT(*) FROM medicines m WHERE m.shop_id = s.id) AS medicines, "
                + "(SELECT COUNT(*) FROM invoices i WHERE i.shop_id = s.id) AS bills, "
                + "(SELECT MAX(i.invoice_date) FROM invoices i WHERE i.shop_id = s.id) AS last_bill "
                + "FROM shops s ORDER BY s.id DESC";

        try (Connection c = DatabaseHelper.connect();
             PreparedStatement ps = c.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {

            StringBuilder json = new StringBuilder("{\"success\":true,\"shops\":[");
            LocalDate today = LocalDate.now();
            int total = 0, paying = 0, trial = 0, ended = 0;
            boolean first = true;

            while (rs.next()) {

                State s = evaluate(rs.getString("plan"), rs.getString("trial_ends"), rs.getString("paid_until"),
                        rs.getInt("disabled") == 1, today, PAID_START);

                total++;
                if ("active".equals(s.state)) paying++;
                else if ("trial".equals(s.state) || "free".equals(s.state)) trial++;
                else ended++;

                if (!first) json.append(',');
                first = false;

                String lastBill = rs.getString("last_bill");

                json.append("{\"id\":").append(rs.getLong("id"))
                        .append(",\"name\":").append(Json.quote(rs.getString("name")))
                        .append(",\"phone\":").append(Json.quote(rs.getString("phone")))
                        .append(",\"owner\":").append(Json.quote(rs.getString("owner")))
                        .append(",\"email\":").append(Json.quote(rs.getString("email")))
                        .append(",\"staff\":").append(rs.getInt("staff"))
                        .append(",\"medicines\":").append(rs.getInt("medicines"))
                        .append(",\"bills\":").append(rs.getInt("bills"))
                        .append(",\"lastBill\":").append(lastBill == null ? "null" : Json.quote(lastBill))
                        .append(",\"createdAt\":").append(Json.quote(rs.getString("created_at")))
                        .append(",\"trialEnds\":").append(Json.quote(rs.getString("trial_ends")))
                        .append(",\"paidUntil\":").append(Json.quote(rs.getString("paid_until")))
                        .append(",").append(stateJson(s))
                        .append("}");
            }

            return json.append("],\"summary\":{\"shops\":").append(total)
                    .append(",\"paying\":").append(paying)
                    .append(",\"trial\":").append(trial)
                    .append(",\"ended\":").append(ended)
                    .append("}}").toString();

        } catch (SQLException e) {
            System.out.println("Admin list error: " + e.getMessage());
            return "{\"success\":false,\"error\":\"Could not load the shops\"}";
        }
    }

    /** extendDays adds to the later of today and the current paid date. Returns false when the shop is unknown. */
    public static boolean adminUpdate(long shopId, Integer extendDays, String paidUntil, String trialEnds, Boolean disabled) {

        try (Connection c = DatabaseHelper.connect()) {

            String currentPaid;

            try (PreparedStatement ps = c.prepareStatement("SELECT paid_until FROM shops WHERE id = ?")) {
                ps.setLong(1, shopId);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) {
                        return false;
                    }
                    currentPaid = rs.getString(1);
                }
            }

            if (extendDays != null) {
                LocalDate base = date(currentPaid);
                LocalDate today = LocalDate.now();
                if (base == null || base.isBefore(today)) base = today;
                paidUntil = base.plusDays(extendDays).toString();
            }

            if (paidUntil != null) {
                try (PreparedStatement ps = c.prepareStatement(
                        "UPDATE shops SET paid_until = ?, plan = ? WHERE id = ?")) {
                    ps.setString(1, paidUntil);
                    ps.setString(2, paidUntil.isEmpty() ? "trial" : "standard");
                    ps.setLong(3, shopId);
                    ps.executeUpdate();
                }
            }

            if (trialEnds != null) {
                try (PreparedStatement ps = c.prepareStatement("UPDATE shops SET trial_ends = ? WHERE id = ?")) {
                    ps.setString(1, trialEnds);
                    ps.setLong(2, shopId);
                    ps.executeUpdate();
                }
            }

            if (disabled != null) {
                try (PreparedStatement ps = c.prepareStatement("UPDATE shops SET disabled = ? WHERE id = ?")) {
                    ps.setInt(1, disabled ? 1 : 0);
                    ps.setLong(2, shopId);
                    ps.executeUpdate();
                }
            }

            return true;

        } catch (SQLException e) {
            System.out.println("Admin update error: " + e.getMessage());
            return false;
        }
    }
}
