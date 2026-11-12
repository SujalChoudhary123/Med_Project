import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.*;

public class BillLoad {
    static HttpClient client = HttpClient.newBuilder().executor(Executors.newFixedThreadPool(64)).build();
    static String base, token;

    static HttpResponse<String> call(String method, String path, String body) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(base + path)).timeout(Duration.ofSeconds(60))
                .header("Authorization", "Bearer " + token).header("Content-Type", "application/json");
        b.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        return client.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    public static void main(String[] a) throws Exception {
        base = a[0]; token = a[1];
        var add = call("POST", "/api/medicines",
            "{\"id\":\"BL1\",\"name\":\"Bill Load\",\"batchNumber\":\"B1\",\"category\":\"Tablet\",\"quantity\":\"10\",\"price\":\"21\",\"manufacturer\":\"X\",\"disease\":\"Y\",\"mfgDate\":\"2026-01-01\",\"expiryDate\":\"2029-01-01\",\"gstRate\":\"12\",\"hsn\":\"3004\"}");
        System.out.println("add medicine BL1 (10 units): HTTP " + add.statusCode());

        int N = 30;
        ExecutorService pool = Executors.newFixedThreadPool(N);
        CountDownLatch go = new CountDownLatch(1);
        AtomicInteger ok = new AtomicInteger(), refused = new AtomicInteger(), other = new AtomicInteger();
        List<String> numbers = Collections.synchronizedList(new ArrayList<>());
        List<Future<?>> fs = new ArrayList<>();
        long t0 = System.currentTimeMillis();
        for (int i = 0; i < N; i++) {
            fs.add(pool.submit(() -> {
                try {
                    go.await();
                    var r = call("POST", "/api/invoices", "{\"paymentMode\":\"cash\",\"items\":[{\"medicineId\":\"BL1\",\"quantity\":1}]}");
                    if (r.statusCode() == 201) {
                        ok.incrementAndGet();
                        Matcher m = Pattern.compile("\"invoiceNo\":\"([^\"]+)\"").matcher(r.body());
                        if (m.find()) numbers.add(m.group(1));
                    } else if (r.statusCode() == 409) refused.incrementAndGet();
                    else { other.incrementAndGet(); System.out.println("unexpected " + r.statusCode() + " " + r.body()); }
                } catch (Exception e) { other.incrementAndGet(); System.out.println("error " + e); }
            }));
        }
        go.countDown();
        for (Future<?> f : fs) f.get();
        long ms = System.currentTimeMillis() - t0;

        Collections.sort(numbers);
        Set<String> unique = new HashSet<>(numbers);
        System.out.println(N + " simultaneous bills for 1 unit each, stock 10 -> created=" + ok + " refused(out of stock)=" + refused + " errors=" + other + " in " + ms + " ms");
        System.out.println("invoice numbers unique: " + (unique.size() == numbers.size()) + "  range: " + numbers.get(0) + " .. " + numbers.get(numbers.size() - 1));

        // consecutive?
        boolean consecutive = true;
        int prev = Integer.parseInt(numbers.get(0).replaceAll("\\D", ""));
        for (int i = 1; i < numbers.size(); i++) {
            int cur = Integer.parseInt(numbers.get(i).replaceAll("\\D", ""));
            if (cur != prev + 1) consecutive = false;
            prev = cur;
        }
        System.out.println("invoice numbers consecutive (no gaps): " + consecutive);
        var med = call("GET", "/api/medicines/BL1", null);
        Matcher q = Pattern.compile("\"quantity\":(\\d+)").matcher(med.body());
        q.find();
        System.out.println("BL1 stock afterwards: " + q.group(1));
        pool.shutdown();
        System.exit(0);
    }
}
