import java.math.BigDecimal;
import java.util.Random;

public class GstMathTest {
    static int fails = 0;

    static void check(String name, Billing.Amounts a, String taxable, String cgst, String sgst, String total, String discount) {
        boolean ok = a.taxable.toPlainString().equals(taxable) && a.cgst.toPlainString().equals(cgst)
                && a.sgst.toPlainString().equals(sgst) && a.lineTotal.toPlainString().equals(total)
                && a.discount.toPlainString().equals(discount);
        if (!ok) fails++;
        System.out.printf("%-62s %s%n", name, ok ? "PASS" : "FAIL  got taxable=" + a.taxable + " cgst=" + a.cgst + " sgst=" + a.sgst + " total=" + a.lineTotal + " disc=" + a.discount);
    }

    static BigDecimal b(String s) { return new BigDecimal(s); }

    public static void main(String[] args) {
        // Hand-calculated expectations.
        check("MRP 105 @5% inclusive, qty 1", Billing.compute(b("105"), 1, b("0"), b("5"), true), "100.00", "2.50", "2.50", "105.00", "0.00");
        check("MRP 112 @12% inclusive, qty 1", Billing.compute(b("112"), 1, b("0"), b("12"), true), "100.00", "6.00", "6.00", "112.00", "0.00");
        check("MRP 59 x3 @18% inclusive, 10% discount", Billing.compute(b("59"), 3, b("10"), b("18"), true), "135.00", "12.15", "12.15", "159.30", "17.70");
        check("MRP 100 x2 @18% EXCLUSIVE (GST added on top)", Billing.compute(b("100"), 2, b("0"), b("18"), false), "200.00", "18.00", "18.00", "236.00", "0.00");
        check("MRP 100 x3 @5% inclusive, 10% discount (rounding case)", Billing.compute(b("100"), 3, b("10"), b("5"), true), "257.14", "6.43", "6.43", "270.00", "30.00");
        check("0% GST item", Billing.compute(b("50"), 2, b("0"), b("0"), true), "100.00", "0.00", "0.00", "100.00", "0.00");
        check("100% discount", Billing.compute(b("80"), 1, b("100"), b("12"), true), "0.00", "0.00", "0.00", "0.00", "80.00");

        // Odd paise: CGST + SGST must still add up to the exact GST amount.
        Billing.Amounts odd = Billing.compute(b("0.05"), 1, b("0"), b("5"), true);
        boolean oddOk = odd.cgst.add(odd.sgst).add(odd.taxable).compareTo(odd.lineTotal) == 0;
        if (!oddOk) fails++;
        System.out.printf("%-62s %s%n", "odd paise: cgst+sgst+taxable == total (0.05 @5%)", oddOk ? "PASS" : "FAIL");

        // Property test: invariants over many random lines.
        Random r = new Random(42);
        int[] rates = {0, 5, 12, 18, 28};
        int bad = 0;
        for (int i = 0; i < 200000; i++) {
            BigDecimal price = BigDecimal.valueOf(r.nextInt(500000) + 1, 2);
            int qty = r.nextInt(50) + 1;
            BigDecimal disc = BigDecimal.valueOf(r.nextInt(10001), 2);
            BigDecimal rate = BigDecimal.valueOf(rates[r.nextInt(rates.length)]);
            boolean inclusive = r.nextBoolean();
            Billing.Amounts a = Billing.compute(price, qty, disc, rate, inclusive);
            boolean sumsUp = a.taxable.add(a.cgst).add(a.sgst).compareTo(a.lineTotal) == 0;
            boolean scale2 = a.taxable.scale() == 2 && a.cgst.scale() == 2 && a.sgst.scale() == 2 && a.lineTotal.scale() == 2;
            boolean nonNeg = a.taxable.signum() >= 0 && a.cgst.signum() >= 0 && a.sgst.signum() >= 0 && a.lineTotal.signum() >= 0;
            boolean cgstSgstClose = a.cgst.subtract(a.sgst).abs().compareTo(new BigDecimal("0.01")) <= 0;
            boolean inclusiveKeepsTotal = !inclusive || a.lineTotal.compareTo(a.gross.subtract(a.discount)) == 0;
            if (!(sumsUp && scale2 && nonNeg && cgstSgstClose && inclusiveKeepsTotal)) bad++;
        }
        if (bad > 0) fails++;
        System.out.printf("%-62s %s%n", "200,000 random lines: totals add up, 2 decimals, never negative", bad == 0 ? "PASS" : "FAIL (" + bad + " bad)");

        System.out.println(fails == 0 ? "ALL GST MATH CHECKS PASSED" : fails + " CHECK(S) FAILED");
        System.exit(fails == 0 ? 0 : 1);
    }
}
