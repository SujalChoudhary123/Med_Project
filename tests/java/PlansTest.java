import java.time.LocalDate;

/** The rules for free launch, trial, paid and ended shops, on fixed dates. */
public class PlansTest {

    private static final LocalDate TODAY = LocalDate.of(2027, 6, 15);
    private static int failed = 0;

    private static void check(String label, Plans.State s, String state, boolean canWrite) {
        if (!state.equals(s.state) || s.canWrite != canWrite) {
            System.out.println("WRONG: " + label + " gave " + s.state + " canWrite=" + s.canWrite);
            failed++;
        }
    }

    private static Plans.State at(String trial, String paid, boolean disabled, String paidStart) {
        return Plans.evaluate("trial", trial, paid, disabled, TODAY,
                paidStart == null ? null : LocalDate.parse(paidStart));
    }

    public static void main(String[] args) {

        // Free launch: no charging date at all.
        check("free, trial long over", at("2020-01-01", "", false, null), "free", true);
        check("free, no trial date", at("", "", false, null), "free", true);
        check("free, but suspended", at("2020-01-01", "", true, null), "disabled", false);
        check("free, someone paid anyway", at("2020-01-01", "2027-12-31", false, null), "active", true);

        // Charging announced for a future date.
        Plans.State announced = at("2020-01-01", "", false, "2027-07-15");
        check("charging next month", announced, "free", true);

        if (!"2027-07-15".equals(announced.endsOn) || announced.daysLeft != 30) {
            System.out.println("WRONG: free period end " + announced.endsOn + ", " + announced.daysLeft + " days");
            failed++;
        }

        // Charging has started. Old shops are carried up to that day, then the grace days.
        check("charging started today", at("2020-01-01", "", false, "2027-06-15"), "trial", true);
        check("started 2 days ago", at("2020-01-01", "", false, "2027-06-13"), "grace", true);
        check("started 10 days ago", at("2020-01-01", "", false, "2027-06-05"), "expired", false);
        check("started 10 days ago, has paid", at("2020-01-01", "2027-12-31", false, "2027-06-05"), "active", true);
        check("new shop, trial still running", at("2027-06-20", "", false, "2027-06-05"), "trial", true);
        check("paid period over", at("2020-01-01", "2027-01-01", false, "2026-06-01"), "expired", false);
        check("suspended while charging", at("2027-06-20", "2027-12-31", true, "2026-06-01"), "disabled", false);

        if (failed > 0) {
            System.exit(1);
        }

        System.out.println("Plan rules passed");
    }
}
