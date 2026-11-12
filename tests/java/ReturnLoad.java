import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.*;

public class ReturnLoad {
    static HttpClient client = HttpClient.newBuilder().executor(Executors.newFixedThreadPool(64)).build();
    static String base, token;

    static HttpResponse<String> call(String method, String path, String body) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(base + path)).timeout(Duration.ofSeconds(60))
                .header("Authorization", "Bearer " + token).header("Content-Type", "application/json");
        b.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        return client.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    static String find(String text, String regex) {
        Matcher m = Pattern.compile(regex).matcher(text);
        return m.find() ? m.group(1) : null;
    }

    public static void main(String[] a) throws Exception {
        base = a[0]; token = a[1];
        call("POST", "/api/medicines",
            "{\"id\":\"RL1\",\"name\":\"Return Load\",\"batchNumber\":\"B1\",\"category\":\"Tablet\",\"quantity\":\"10\",\"price\":\"21\",\"manufacturer\":\"X\",\"disease\":\"Y\",\"mfgDate\":\"2026-01-01\",\"expiryDate\":\"2029-01-01\",\"gstRate\":\"12\",\"hsn\":\"3004\"}");
        var inv = call("POST", "/api/invoices", "{\"paymentMode\":\"cash\",\"items\":[{\"medicineId\":\"RL1\",\"quantity\":10}]}");
        String invoiceId = find(inv.body(), "\"invoice\":\\{\"id\":(\\d+)");
        String itemId = find(inv.body(), "\"itemId\":(\\d+)");
        String total = find(inv.body(), "\"totals\":\\{[^}]*\"total\":([0-9.]+)");
        System.out.println("invoice " + invoiceId + " for 10 units, total " + total + ", item " + itemId);

        int N = 20;
        ExecutorService pool = Executors.newFixedThreadPool(N);
        CountDownLatch go = new CountDownLatch(1);
        AtomicInteger ok = new AtomicInteger(), refused = new AtomicInteger(), other = new AtomicInteger();
        List<String> notes = Collections.synchronizedList(new ArrayList<>());
        List<Double> refunds = Collections.synchronizedList(new ArrayList<>());
        List<Future<?>> fs = new ArrayList<>();
        long t0 = System.currentTimeMillis();
        for (int i = 0; i < N; i++) {
            fs.add(pool.submit(() -> {
                try {
                    go.await();
                    var r = call("POST", "/api/invoices/" + invoiceId + "/return",
                            "{\"refundMode\":\"cash\",\"items\":[{\"itemId\":" + itemId + ",\"quantity\":1}]}");
                    if (r.statusCode() == 201) {
                        ok.incrementAndGet();
                        notes.add(find(r.body(), "\"noteNo\":\"([^\"]+)\""));
                        refunds.add(Double.parseDouble(find(r.body(), "\"totals\":\\{[^}]*\"total\":([0-9.]+)")));
                    } else if (r.statusCode() == 409) refused.incrementAndGet();
                    else { other.incrementAndGet(); System.out.println("unexpected " + r.statusCode() + " " + r.body()); }
                } catch (Exception e) { other.incrementAndGet(); System.out.println("error " + e); }
            }));
        }
        go.countDown();
        for (Future<?> f : fs) f.get();
        long ms = System.currentTimeMillis() - t0;

        Collections.sort(notes);
        double refunded = 0;
        for (double d : refunds) refunded += d;
        System.out.println(N + " simultaneous returns of 1 unit, only 10 were sold -> accepted=" + ok + " refused=" + refused + " errors=" + other + " in " + ms + " ms");
        System.out.println("credit note numbers unique: " + (new HashSet<>(notes).size() == notes.size()) + "  " + notes.get(0) + " .. " + notes.get(notes.size() - 1));
        System.out.printf("total refunded %.2f vs invoice total %s -> never more: %b%n", refunded, total, refunded <= Double.parseDouble(total) + 0.0001);
        var med = call("GET", "/api/medicines/RL1", null);
        System.out.println("RL1 stock afterwards (should be 10): " + find(med.body(), "\"quantity\":(\\d+)"));
        pool.shutdown();
        System.exit(0);
    }
}
