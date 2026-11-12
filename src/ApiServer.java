import com.sun.net.httpserver.Filter;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import javax.swing.table.DefaultTableModel;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

import java.net.InetSocketAddress;
import java.net.URLDecoder;

import java.nio.charset.StandardCharsets;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;


public class ApiServer {

    // Port comes from the PORT environment variable (see Config.java).
    private static final int PORT = Config.PORT;

    /** Every API path goes through the same guard (see GUARD). */
    private static void route(HttpServer server, String path, HttpHandler handler) {
        server.createContext(path, handler).getFilters().add(GUARD);
    }

    /**
     * Runs around every request: safe response headers, a per-address request
     * limit, a clean error when a handler crashes, and the activity log.
     */
    private static final Filter GUARD = new Filter() {

        @Override
        public void doFilter(HttpExchange exchange, Chain chain) throws IOException {

            Headers headers = exchange.getResponseHeaders();
            headers.set("X-Content-Type-Options", "nosniff");
            headers.set("X-Frame-Options", "DENY");
            headers.set("Referrer-Policy", "no-referrer");
            headers.set("Cache-Control", "no-store");

            String path = exchange.getRequestURI().getPath();

            if (!path.startsWith("/api/health")
                    && !SessionManager.allow("api|" + clientIp(exchange), Config.RATE_PER_MINUTE, 60_000L)) {
                addCorsHeaders(exchange);
                sendResponse(exchange, 429, "{\"success\":false,\"error\":\"Too many requests. Please wait a minute.\"}");
                return;
            }

            try {
                chain.doFilter(exchange);
            } catch (Exception e) {
                System.out.println("Unhandled error on " + exchange.getRequestMethod() + " " + path + ": " + e);
                e.printStackTrace();
                if (exchange.getResponseCode() == -1) {
                    sendResponse(exchange, 500, "{\"success\":false,\"error\":\"Something went wrong\"}");
                }
                return;
            }

            writeAudit(exchange, path);
        }

        @Override
        public String description() {
            return "MedTrack request guard";
        }
    };

    private static void writeAudit(HttpExchange exchange, String path) {

        String method = exchange.getRequestMethod();
        int code = exchange.getResponseCode();
        Object shop = exchange.getAttribute("shopId");

        if (shop == null || code < 200 || code >= 300
                || !("POST".equals(method) || "PUT".equals(method) || "DELETE".equals(method))) {
            return;
        }

        String[] what = Audit.describe(method, path);

        if (what != null) {
            Object detail = exchange.getAttribute("audit");
            Audit.record((Long) shop, (String) exchange.getAttribute("username"),
                    (String) exchange.getAttribute("role"), what[0], what[1],
                    detail == null ? "" : detail.toString(), clientIp(exchange));
        }
    }


    public static void main(String[] args) {

        try {

            // Ensure all required tables and backward-compatible schema migrations exist.
            DatabaseHelper.initializeDatabase();
            Backup.start();
            GoogleAuth.start();

            HttpServer server =
                    HttpServer.create(
                            new InetSocketAddress(PORT),
                            128
                    );

            // Lets uptime monitors and the load balancer check the API is alive.
            route(server, "/api/health", exchange -> {
                addCorsHeaders(exchange);
                boolean databaseOk = DatabaseHelper.isHealthy();
                sendResponse(exchange, databaseOk ? 200 : 503,
                        "{\"status\":\"" + (databaseOk ? "ok" : "unavailable") + "\"}");
            });


            // =====================================================
            // MEDICINES API
            // =====================================================

            route(server, "/api/medicines",
                    exchange -> {

                        addCorsHeaders(exchange);

                        String method =
                                exchange.getRequestMethod();


                        // CORS PREFLIGHT
                        if ("OPTIONS".equalsIgnoreCase(method)) {

                            sendResponse(
                                    exchange,
                                    204,
                                    ""
                            );

                            return;
                        }


                        // AUTH REQUIRED
                        if (requireAuth(exchange) == null) {

                            return;
                        }


                        // Cashiers can look medicines up but not change stock records.
                        if (!"GET".equalsIgnoreCase(method)
                                && !hasRole(exchange, "owner", "pharmacist")) {

                            forbidden(exchange);

                            return;
                        }


                        // GET
                        if ("GET".equalsIgnoreCase(method)) {

                            handleGetMedicines(exchange);

                            return;
                        }


                        // POST
                        if ("POST".equalsIgnoreCase(method)) {

                            handleAddMedicine(exchange);

                            return;
                        }


                        // PUT
                        if ("PUT".equalsIgnoreCase(method)) {

                            handleUpdateMedicine(exchange);

                            return;
                        }


                        // DELETE
                        if ("DELETE".equalsIgnoreCase(method)) {

                            handleDeleteMedicine(exchange);

                            return;
                        }


                        // METHOD NOT ALLOWED
                        sendResponse(
                                exchange,
                                405,
                                "{\"error\":\"Method Not Allowed\"}"
                        );
                    }
            );


            // =====================================================
            // SALES API
            // =====================================================

            route(server, "/api/sales",
                    exchange -> {

                        addCorsHeaders(exchange);

                        String method =
                                exchange.getRequestMethod();


                        // CORS PREFLIGHT
                        if ("OPTIONS".equalsIgnoreCase(method)) {

                            sendResponse(
                                    exchange,
                                    204,
                                    ""
                            );

                            return;
                        }


                        // AUTH REQUIRED
                        if (requireAuth(exchange) == null) {

                            return;
                        }


                        // GET SALES
                        if ("GET".equalsIgnoreCase(method)) {

                            handleGetSales(exchange);

                            return;
                        }


                        // POST SALE
                        if ("POST".equalsIgnoreCase(method)) {

                            handleCreateSale(exchange);

                            return;
                        }


                        // METHOD NOT ALLOWED
                        sendResponse(
                                exchange,
                                405,
                                "{\"error\":\"Method Not Allowed\"}"
                        );
                    }
            );

            // Legacy signup path: routed through the same rate-limited OTP flow.
            route(server, "/api/signup", exchange -> authRoute(exchange, "signup"));

            // OTP and Google authentication APIs
            route(server, "/api/auth/signup", exchange -> authRoute(exchange, "signup"));
            route(server, "/api/auth/login", exchange -> authRoute(exchange, "login"));
            route(server, "/api/auth/google", exchange -> authRoute(exchange, "google"));
            route(server, "/api/auth/verify-otp", exchange -> authRoute(exchange, "verify"));
            route(server, "/api/auth/resend-otp", exchange -> authRoute(exchange, "resend"));
            route(server, "/api/auth/otp-login", exchange -> authRoute(exchange, "otp-login"));
            route(server, "/api/auth/forgot-password", exchange -> authRoute(exchange, "forgot-password"));
            route(server, "/api/auth/reset-password", exchange -> authRoute(exchange, "reset-password"));
            route(server, "/api/auth/logout", ApiServer::logoutRoute);

            // Authenticated account APIs
            route(server, "/api/profile", ApiServer::profileRoute);
            route(server, "/api/change-password", ApiServer::changePasswordRoute);
            route(server, "/api/team", ApiServer::teamRoute);
            route(server, "/api/shop", ApiServer::shopRoute);
            route(server, "/api/invoices", ApiServer::invoiceRoute);
            route(server, "/api/credit-notes", ApiServer::creditNoteRoute);
            route(server, "/api/reports", ApiServer::reportsRoute);
            route(server, "/api/customers", ApiServer::customersRoute);
            route(server, "/api/suppliers", ApiServer::suppliersRoute);
            route(server, "/api/purchases", ApiServer::purchasesRoute);
            route(server, "/api/audit", ApiServer::auditRoute);
            route(server, "/api/backup", ApiServer::backupRoute);
            route(server, "/api/plan", ApiServer::planRoute);
            route(server, "/api/public", ApiServer::publicRoute);
            route(server, "/api/admin", ApiServer::adminRoute);

            // =====================================================
            // START SERVER
            // =====================================================

            // A pool of workers, so one slow request (e.g. sending an OTP
            // email) no longer blocks everybody else.
            server.setExecutor(
                    java.util.concurrent.Executors.newFixedThreadPool(
                            Config.THREADS,
                            new java.util.concurrent.ThreadFactory() {
                                private final java.util.concurrent.atomic.AtomicInteger count =
                                        new java.util.concurrent.atomic.AtomicInteger();

                                @Override
                                public Thread newThread(Runnable task) {
                                    Thread thread = new Thread(task, "api-worker-" + count.incrementAndGet());
                                    thread.setDaemon(false);
                                    return thread;
                                }
                            }));

            server.start();


            System.out.println();
            System.out.println("======================================");
            System.out.println("   Medicine Management API Started");
            System.out.println("======================================");
            System.out.println();


            System.out.println(
                    "API URL: http://localhost:"
                            + PORT
                            + "/api/medicines"
            );


            System.out.println();

            System.out.println("GET Search example:");

            System.out.println(
                    "http://localhost:"
                            + PORT
                            + "/api/medicines?search=para&filter=Name"
            );


            System.out.println();

            System.out.println("POST Add Medicine:");

            System.out.println(
                    "http://localhost:"
                            + PORT
                            + "/api/medicines"
            );


            System.out.println();

            System.out.println("PUT Update Medicine:");

            System.out.println(
                    "http://localhost:"
                            + PORT
                            + "/api/medicines/{id}"
            );


            System.out.println();

            System.out.println("DELETE Medicine:");

            System.out.println(
                    "http://localhost:"
                            + PORT
                            + "/api/medicines/{id}"
            );


            System.out.println();

            System.out.println("GET Sales:");

            System.out.println(
                    "http://localhost:"
                            + PORT
                            + "/api/sales"
            );


            System.out.println();

            System.out.println("POST Sale:");

            System.out.println(
                    "http://localhost:"
                            + PORT
                            + "/api/sales"
            );


            System.out.println();

            System.out.println("Email codes (SMTP): " + (Config.get("SMTP_EMAIL", "").isEmpty() ? "NOT SET" : "set"));
            System.out.println("Google sign-in:     " + (Config.get("GOOGLE_CLIENT_ID", "").isEmpty() ? "NOT SET" : "set"));
            System.out.println("Platform admins:    " + (Config.get("ADMIN_EMAILS", "").isEmpty() ? "NOT SET" : "set"));
            if (!Config.get("PAID_START", "").isEmpty() && Plans.PAID_START == null) {
                System.out.println("PAID_START is not a date (use YYYY-MM-DD). Staying free for everyone.");
            }
            System.out.println("Plans:              "
                    + (Plans.PAID_START == null ? "free for everyone" : "paid from " + Plans.PAID_START));
            System.out.println();
            System.out.println("Server is running...");
            System.out.println("Press Ctrl + C to stop.");
            System.out.println();


        } catch (Exception e) {

            System.out.println(
                    "API Server Error: "
                            + e.getMessage()
            );

            e.printStackTrace();
        }
    }


    // =========================================================
    // GET MEDICINES
    // =========================================================

    private static void handleGetMedicines(
            HttpExchange exchange) {

        try {

            String path =
                    exchange.getRequestURI()
                            .getPath();

            String basePath =
                    "/api/medicines";


            // =================================================
            // GET SINGLE MEDICINE
            // /api/medicines/MED001
            // =================================================

            if (path.length() > basePath.length()
                    && path.startsWith(basePath + "/")) {

                String id =
                        URLDecoder.decode(
                                path.substring(
                                        basePath.length() + 1
                                ),
                                StandardCharsets.UTF_8
                        );


                handleGetMedicineById(
                        exchange,
                        id
                );

                return;
            }


            // =================================================
            // GET MEDICINE LIST
            // =================================================

            Map<String, String> queryParams =
                    parseQueryParameters(
                            exchange.getRequestURI()
                                    .getRawQuery()
                    );


            String searchText =
                    queryParams.getOrDefault(
                            "search",
                            ""
                    );


            String filterBy =
                    queryParams.getOrDefault(
                            "filter",
                            "Name"
                    );


            DefaultTableModel model =
                    DatabaseHelper.getMedicineData(
                            shopId(exchange),
                            searchText,
                            filterBy
                    );


            String json =
                    tableModelToJson(model);


            sendResponse(
                    exchange,
                    200,
                    json
            );


        } catch (Exception e) {

            e.printStackTrace();

            sendResponse(
                    exchange,
                    500,
                    "{\"error\":\"Failed to fetch medicines\"}"
            );
        }
    }


    // =========================================================
    // GET SINGLE MEDICINE
    // =========================================================

    private static void handleGetMedicineById(
            HttpExchange exchange,
            String id) {

        try {

            Object[] data =
                    DatabaseHelper.getMedicineById(shopId(exchange), id);


            if (data == null) {

                sendResponse(
                        exchange,
                        404,
                        "{\"error\":\"Medicine not found\"}"
                );

                return;
            }


            String json =
                    "{"
                            + "\"id\":\""
                            + escapeJson(data[0])
                            + "\","

                            + "\"name\":\""
                            + escapeJson(data[1])
                            + "\","

                            + "\"batchNumber\":\""
                            + escapeJson(data[2])
                            + "\","

                            + "\"category\":\""
                            + escapeJson(data[3])
                            + "\","

                            + "\"quantity\":"
                            + data[4]
                            + ","

                            + "\"price\":"
                            + data[5]
                            + ","

                            + "\"manufacturer\":\""
                            + escapeJson(data[6])
                            + "\","

                            + "\"disease\":\""
                            + escapeJson(data[7])
                            + "\","

                            + "\"mfgDate\":\""
                            + escapeJson(data[8])
                            + "\","

                            + "\"expiryDate\":\""
                            + escapeJson(data[9])
                            + "\","

                            + "\"gstRate\":"
                            + data[10]
                            + ","

                            + "\"hsn\":\""
                            + escapeJson(data[11])
                            + "\""

                            + "}";


            sendResponse(
                    exchange,
                    200,
                    json
            );


        } catch (Exception e) {

            e.printStackTrace();

            sendResponse(
                    exchange,
                    500,
                    "{\"error\":\"Failed to fetch medicine details\"}"
            );
        }
    }


    // =========================================================
    // POST - ADD MEDICINE
    // =========================================================

    private static void handleAddMedicine(
            HttpExchange exchange) {

        try {

            String requestBody =
                    readRequestBody(exchange);


            if (requestBody == null
                    || requestBody.trim().isEmpty()) {

                sendResponse(
                        exchange,
                        400,
                        "{\"error\":\"Request body is empty\"}"
                );

                return;
            }


            Map<String, String> data =
                    parseJsonObject(requestBody);


            String id =
                    getJsonValue(
                            data,
                            "id"
                    );


            String name =
                    getJsonValue(
                            data,
                            "name"
                    );


            String batchNumber =
                    getJsonValue(
                            data,
                            "batchNumber"
                    );


            String category =
                    getJsonValue(
                            data,
                            "category"
                    );


            String quantityText =
                    getJsonValue(
                            data,
                            "quantity"
                    );


            String priceText =
                    getJsonValue(
                            data,
                            "price"
                    );


            String manufacturer =
                    getJsonValue(
                            data,
                            "manufacturer"
                    );


            String disease =
                    getJsonValue(
                            data,
                            "disease"
                    );


            String mfgDateText =
                    getJsonValue(
                            data,
                            "mfgDate"
                    );


            String expiryDateText =
                    getJsonValue(
                            data,
                            "expiryDate"
                    );


            // =================================================
            // REQUIRED FIELDS
            // =================================================

            if (isEmpty(id)
                    || isEmpty(name)
                    || isEmpty(batchNumber)
                    || isEmpty(category)
                    || isEmpty(quantityText)
                    || isEmpty(priceText)
                    || isEmpty(manufacturer)
                    || isEmpty(disease)
                    || isEmpty(mfgDateText)
                    || isEmpty(expiryDateText)) {

                sendResponse(
                        exchange,
                        400,
                        "{\"error\":\"All medicine fields are required\"}"
                );

                return;
            }


            // =================================================
            // QUANTITY
            // =================================================

            int quantity;

            try {

                quantity =
                        Integer.parseInt(
                                quantityText.trim()
                        );

            } catch (NumberFormatException e) {

                sendResponse(
                        exchange,
                        400,
                        "{\"error\":\"Quantity must be a valid number\"}"
                );

                return;
            }


            if (quantity < 0) {

                sendResponse(
                        exchange,
                        400,
                        "{\"error\":\"Quantity cannot be negative\"}"
                );

                return;
            }


            // =================================================
            // PRICE
            // =================================================

            double price;

            try {

                price =
                        Double.parseDouble(
                                priceText.trim()
                        );

            } catch (NumberFormatException e) {

                sendResponse(
                        exchange,
                        400,
                        "{\"error\":\"Price must be a valid number\"}"
                );

                return;
            }


            if (price < 0
                    || Double.isNaN(price)
                    || Double.isInfinite(price)) {

                sendResponse(
                        exchange,
                        400,
                        "{\"error\":\"Price must be a valid non-negative number\"}"
                );

                return;
            }


            // =================================================
            // DATES
            // =================================================

            LocalDate mfgDate;
            LocalDate expiryDate;

            try {

                mfgDate =
                        LocalDate.parse(
                                mfgDateText.trim()
                        );


                expiryDate =
                        LocalDate.parse(
                                expiryDateText.trim()
                        );

            } catch (Exception e) {

                sendResponse(
                        exchange,
                        400,
                        "{\"error\":\"Dates must use YYYY-MM-DD format\"}"
                );

                return;
            }


            if (expiryDate.isBefore(mfgDate)) {

                sendResponse(
                        exchange,
                        400,
                        "{\"error\":\"Expiry date cannot be before manufacturing date\"}"
                );

                return;
            }


            String gstProblem = gstError(data);

            if (gstProblem != null) {

                sendResponse(exchange, 400, "{\"error\":\"" + gstProblem + "\"}");

                return;
            }


            // =================================================
            // CREATE MEDICINE
            // =================================================

            Medicine medicine =
                    new Medicine(
                            id.trim(),
                            name.trim(),
                            batchNumber.trim(),
                            category.trim(),
                            quantity,
                            price,
                            manufacturer.trim(),
                            disease.trim(),
                            mfgDate,
                            expiryDate
                    );


            boolean inserted =
                    DatabaseHelper.insertMedicine(
                            shopId(exchange),
                            medicine
                    );


            if (!inserted) {

                sendResponse(
                        exchange,
                        409,
                        "{\"error\":\"Medicine ID already exists or medicine could not be added\"}"
                );

                return;
            }


            DatabaseHelper.setMedicineGst(
                    shopId(exchange), id.trim(), gstRateOf(data), hsnOf(data));
            DatabaseHelper.setMedicineCost(shopId(exchange), id.trim(), costOf(data));


            sendResponse(
                    exchange,
                    201,
                    "{"
                            + "\"success\":true,"
                            + "\"message\":\"Medicine added successfully\","
                            + "\"id\":\""
                            + escapeJson(id)
                            + "\""
                            + "}"
            );


        } catch (Exception e) {

            e.printStackTrace();

            sendResponse(
                    exchange,
                    500,
                    "{\"error\":\"Failed to add medicine\"}"
            );
        }
    }


    // =========================================================
    // PUT - UPDATE MEDICINE
    // =========================================================

    private static void handleUpdateMedicine(
            HttpExchange exchange) {

        try {

            String path =
                    exchange.getRequestURI()
                            .getPath();

            String basePath =
                    "/api/medicines";


            if (!path.startsWith(basePath + "/")) {

                sendResponse(
                        exchange,
                        400,
                        "{\"error\":\"Medicine ID is required\"}"
                );

                return;
            }


            String id =
                    URLDecoder.decode(
                            path.substring(
                                    basePath.length() + 1
                            ),
                            StandardCharsets.UTF_8
                    );


            if (isEmpty(id)) {

                sendResponse(
                        exchange,
                        400,
                        "{\"error\":\"Medicine ID is required\"}"
                );

                return;
            }


            String requestBody =
                    readRequestBody(exchange);


            Map<String, String> data =
                    parseJsonObject(requestBody);


            String name =
                    getJsonValue(
                            data,
                            "name"
                    );


            String batchNumber =
                    getJsonValue(
                            data,
                            "batchNumber"
                    );


            String category =
                    getJsonValue(
                            data,
                            "category"
                    );


            String quantityText =
                    getJsonValue(
                            data,
                            "quantity"
                    );


            String priceText =
                    getJsonValue(
                            data,
                            "price"
                    );


            String manufacturer =
                    getJsonValue(
                            data,
                            "manufacturer"
                    );


            String disease =
                    getJsonValue(
                            data,
                            "disease"
                    );


            String mfgDateText =
                    getJsonValue(
                            data,
                            "mfgDate"
                    );


            String expiryDateText =
                    getJsonValue(
                            data,
                            "expiryDate"
                    );


            // =================================================
            // REQUIRED FIELDS
            // =================================================

            if (isEmpty(name)
                    || isEmpty(batchNumber)
                    || isEmpty(category)
                    || isEmpty(quantityText)
                    || isEmpty(priceText)
                    || isEmpty(manufacturer)
                    || isEmpty(disease)
                    || isEmpty(mfgDateText)
                    || isEmpty(expiryDateText)) {

                sendResponse(
                        exchange,
                        400,
                        "{\"error\":\"All medicine fields are required\"}"
                );

                return;
            }


            // =================================================
            // QUANTITY
            // =================================================

            int quantity;

            try {

                quantity =
                        Integer.parseInt(
                                quantityText.trim()
                        );

            } catch (NumberFormatException e) {

                sendResponse(
                        exchange,
                        400,
                        "{\"error\":\"Quantity must be a valid number\"}"
                );

                return;
            }


            if (quantity < 0) {

                sendResponse(
                        exchange,
                        400,
                        "{\"error\":\"Quantity cannot be negative\"}"
                );

                return;
            }


            // =================================================
            // PRICE
            // =================================================

            double price;

            try {

                price =
                        Double.parseDouble(
                                priceText.trim()
                        );

            } catch (NumberFormatException e) {

                sendResponse(
                        exchange,
                        400,
                        "{\"error\":\"Price must be a valid number\"}"
                );

                return;
            }


            if (price < 0
                    || Double.isNaN(price)
                    || Double.isInfinite(price)) {

                sendResponse(
                        exchange,
                        400,
                        "{\"error\":\"Price must be a valid non-negative number\"}"
                );

                return;
            }


            // =================================================
            // DATES
            // =================================================

            LocalDate mfgDate;
            LocalDate expiryDate;

            try {

                mfgDate =
                        LocalDate.parse(
                                mfgDateText.trim()
                        );


                expiryDate =
                        LocalDate.parse(
                                expiryDateText.trim()
                        );

            } catch (Exception e) {

                sendResponse(
                        exchange,
                        400,
                        "{\"error\":\"Dates must use YYYY-MM-DD format\"}"
                );

                return;
            }


            if (expiryDate.isBefore(mfgDate)) {

                sendResponse(
                        exchange,
                        400,
                        "{\"error\":\"Expiry date cannot be before manufacturing date\"}"
                );

                return;
            }


            // =================================================
            // CHECK EXISTING MEDICINE
            // =================================================

            Object[] existing =
                    DatabaseHelper.getMedicineById(shopId(exchange), id);


            if (existing == null) {

                sendResponse(
                        exchange,
                        404,
                        "{\"error\":\"Medicine not found\"}"
                );

                return;
            }


            String gstProblem = gstError(data);

            if (gstProblem != null) {

                sendResponse(exchange, 400, "{\"error\":\"" + gstProblem + "\"}");

                return;
            }


            // =================================================
            // UPDATE SQLITE
            // =================================================

            boolean updated =
                    DatabaseHelper.updateMedicine(
                            shopId(exchange),
                            id,
                            name.trim(),
                            batchNumber.trim(),
                            category.trim(),
                            quantity,
                            price,
                            manufacturer.trim(),
                            disease.trim(),
                            mfgDate.toString(),
                            expiryDate.toString()
                    );


            if (!updated) {

                sendResponse(
                        exchange,
                        500,
                        "{\"error\":\"Medicine could not be updated\"}"
                );

                return;
            }


            DatabaseHelper.setMedicineGst(
                    shopId(exchange), id, gstRateOf(data), hsnOf(data));
            DatabaseHelper.setMedicineCost(shopId(exchange), id, costOf(data));


            sendResponse(
                    exchange,
                    200,
                    "{"
                            + "\"success\":true,"
                            + "\"message\":\"Medicine updated successfully\","
                            + "\"id\":\""
                            + escapeJson(id)
                            + "\""
                            + "}"
            );


        } catch (Exception e) {

            e.printStackTrace();

            sendResponse(
                    exchange,
                    500,
                    "{\"error\":\"Failed to update medicine\"}"
            );
        }
    }


    // =========================================================
    // DELETE - DELETE MEDICINE
    // =========================================================

    private static void handleDeleteMedicine(
            HttpExchange exchange) {

        try {

            String path =
                    exchange.getRequestURI()
                            .getPath();

            String basePath =
                    "/api/medicines";


            if (!path.startsWith(basePath + "/")) {

                sendResponse(
                        exchange,
                        400,
                        "{\"error\":\"Medicine ID is required\"}"
                );

                return;
            }


            String id =
                    URLDecoder.decode(
                            path.substring(
                                    basePath.length() + 1
                            ),
                            StandardCharsets.UTF_8
                    );


            if (isEmpty(id)) {

                sendResponse(
                        exchange,
                        400,
                        "{\"error\":\"Medicine ID is required\"}"
                );

                return;
            }


            Object[] existing =
                    DatabaseHelper.getMedicineById(shopId(exchange), id);


            if (existing == null) {

                sendResponse(
                        exchange,
                        404,
                        "{\"error\":\"Medicine not found\"}"
                );

                return;
            }


            boolean deleted =
                    DatabaseHelper.deleteMedicine(shopId(exchange), id);


            if (!deleted) {

                sendResponse(
                        exchange,
                        500,
                        "{\"error\":\"Medicine could not be deleted\"}"
                );

                return;
            }


            sendResponse(
                    exchange,
                    200,
                    "{"
                            + "\"success\":true,"
                            + "\"message\":\"Medicine deleted successfully\","
                            + "\"id\":\""
                            + escapeJson(id)
                            + "\""
                            + "}"
            );


        } catch (Exception e) {

            e.printStackTrace();

            sendResponse(
                    exchange,
                    500,
                    "{\"error\":\"Failed to delete medicine\"}"
            );
        }
    }


    // =========================================================
    // GET SALES
    // =========================================================

    private static void handleGetSales(
            HttpExchange exchange) {

        try {

            Map<String, String> queryParams =
                    parseQueryParameters(
                            exchange.getRequestURI()
                                    .getRawQuery()
                    );


            String searchText =
                    queryParams.getOrDefault(
                            "search",
                            ""
                    );


            String filterBy =
                    queryParams.getOrDefault(
                            "filter",
                            "Medicine ID"
                    );


            DefaultTableModel model =
                    DatabaseHelper.getSalesData(
                            shopId(exchange),
                            searchText,
                            filterBy
                    );


            String json =
                    salesTableModelToJson(model);


            sendResponse(
                    exchange,
                    200,
                    json
            );


        } catch (Exception e) {

            e.printStackTrace();

            sendResponse(
                    exchange,
                    500,
                    "{\"error\":\"Failed to fetch sales\"}"
            );
        }
    }


    // =========================================================
    // POST - CREATE SALE
    // =========================================================

    private static void handleCreateSale(
            HttpExchange exchange) {

        try {

            String requestBody =
                    readRequestBody(exchange);


            if (requestBody == null
                    || requestBody.trim().isEmpty()) {

                sendResponse(
                        exchange,
                        400,
                        "{\"error\":\"Request body is empty\"}"
                );

                return;
            }


            Map<String, String> data =
                    parseJsonObject(requestBody);


            String medicineId =
                    getJsonValue(
                            data,
                            "medicineId"
                    );


            String quantityText =
                    getJsonValue(
                            data,
                            "quantity"
                    );


            String customerName =
                    getJsonValue(
                            data,
                            "customerName"
                    );


            // =================================================
            // REQUIRED FIELDS
            // =================================================

            if (isEmpty(medicineId)
                    || isEmpty(quantityText)) {

                sendResponse(
                        exchange,
                        400,
                        "{\"error\":\"Medicine ID and quantity are required\"}"
                );

                return;
            }


            medicineId =
                    medicineId.trim();


            // =================================================
            // QUANTITY
            // =================================================

            int saleQuantity;

            try {

                saleQuantity =
                        Integer.parseInt(
                                quantityText.trim()
                        );

            } catch (NumberFormatException e) {

                sendResponse(
                        exchange,
                        400,
                        "{\"error\":\"Quantity must be a valid number\"}"
                );

                return;
            }


            if (saleQuantity <= 0) {

                sendResponse(
                        exchange,
                        400,
                        "{\"error\":\"Sale quantity must be greater than zero\"}"
                );

                return;
            }


            // =================================================
            // GET CURRENT MEDICINE STOCK AND PRICE
            // =================================================

            Object[] medicine =
                    DatabaseHelper.getMedicineForSale(
                            shopId(exchange),
                            medicineId
                    );


            if (medicine == null) {

                sendResponse(
                        exchange,
                        404,
                        "{\"error\":\"Medicine not found\"}"
                );

                return;
            }


            String medicineName =
                    String.valueOf(
                            medicine[1]
                    );


            int availableQuantity =
                    ((Number) medicine[2])
                            .intValue();


            double unitPrice =
                    ((Number) medicine[3])
                            .doubleValue();


            // =================================================
            // STOCK CHECK
            // =================================================

            if (saleQuantity > availableQuantity) {

                sendResponse(
                        exchange,
                        400,
                        "{"
                                + "\"error\":\"Insufficient stock\","
                                + "\"availableQuantity\":"
                                + availableQuantity
                                + "}"
                );

                return;
            }


            // =================================================
            // CUSTOMER NAME
            // =================================================

            if (isEmpty(customerName)) {

                customerName =
                        "Walk-in Customer";

            } else {

                customerName =
                        customerName.trim();
            }


            // =================================================
            // SALE DATE
            // =================================================

            String saleDate =
                    LocalDateTime.now()
                            .format(
                                    DateTimeFormatter.ofPattern(
                                            "yyyy-MM-dd HH:mm:ss"
                                    )
                            );


            // =================================================
            // TOTAL
            // =================================================

            double totalAmount =
                    saleQuantity * unitPrice;


            // =================================================
            // SAVE SALE AND REDUCE STOCK
            // =================================================

            boolean sold =
                    DatabaseHelper.sellMedicine(
                            shopId(exchange),
                            medicineId,
                            customerName,
                            saleQuantity,
                            saleDate
                    );


            if (!sold) {

                sendResponse(
                        exchange,
                        400,
                        "{\"error\":\"Sale could not be completed. Stock may have changed.\"}"
                );

                return;
            }


            // =================================================
            // SUCCESS
            // =================================================

            String response =
                    "{"
                            + "\"success\":true,"
                            + "\"message\":\"Sale completed successfully\","

                            + "\"medicineId\":\""
                            + escapeJson(medicineId)
                            + "\","

                            + "\"medicineName\":\""
                            + escapeJson(medicineName)
                            + "\","

                            + "\"customerName\":\""
                            + escapeJson(customerName)
                            + "\","

                            + "\"quantity\":"
                            + saleQuantity
                            + ","

                            + "\"unitPrice\":"
                            + unitPrice
                            + ","

                            + "\"totalAmount\":"
                            + totalAmount
                            + ","

                            + "\"saleDate\":\""
                            + escapeJson(saleDate)
                            + "\""

                            + "}";


            sendResponse(
                    exchange,
                    201,
                    response
            );


        } catch (Exception e) {

            e.printStackTrace();

            sendResponse(
                    exchange,
                    500,
                    "{\"error\":\"Failed to create sale\"}"
            );
        }
    }


    // =========================================================
    // CONVERT SALES TABLE MODEL TO JSON
    // =========================================================

    private static String salesTableModelToJson(
            DefaultTableModel model) {

        StringBuilder json =
                new StringBuilder();


        json.append("[");


        for (
                int row = 0;
                row < model.getRowCount();
                row++
        ) {

            if (row > 0) {
                json.append(",");
            }


            json.append("{");


            json.append("\"saleId\":")
                    .append(
                            model.getValueAt(
                                    row,
                                    0
                            )
                    )
                    .append(",");


            json.append("\"medicineId\":\"")
                    .append(
                            escapeJson(
                                    model.getValueAt(
                                            row,
                                            1
                                    )
                            )
                    )
                    .append("\",");


            json.append("\"medicineName\":\"")
                    .append(
                            escapeJson(
                                    model.getValueAt(
                                            row,
                                            2
                                    )
                            )
                    )
                    .append("\",");


            json.append("\"customerName\":\"")
                    .append(
                            escapeJson(
                                    model.getValueAt(
                                            row,
                                            3
                                    )
                            )
                    )
                    .append("\",");


            json.append("\"quantity\":")
                    .append(
                            model.getValueAt(
                                    row,
                                    4
                            )
                    )
                    .append(",");


            json.append("\"unitPrice\":")
                    .append(
                            model.getValueAt(
                                    row,
                                    5
                            )
                    )
                    .append(",");


            json.append("\"totalAmount\":")
                    .append(
                            model.getValueAt(
                                    row,
                                    6
                            )
                    )
                    .append(",");


            json.append("\"saleDate\":\"")
                    .append(
                            escapeJson(
                                    model.getValueAt(
                                            row,
                                            7
                                    )
                            )
                    )
                    .append("\"");


            json.append("}");
        }


        json.append("]");


        return json.toString();
    }


    // =========================================================
    // CORS
    // =========================================================

    private static void addCorsHeaders(
            HttpExchange exchange) {

        Headers headers =
                exchange.getResponseHeaders();


        // Only origins listed in ALLOWED_ORIGINS may call the API from another site.
        String origin = exchange.getRequestHeaders().getFirst("Origin");
        if (origin != null && Config.ALLOWED_ORIGINS.contains(origin)) {
            headers.set("Access-Control-Allow-Origin", origin);
            headers.set("Vary", "Origin");
        }


        headers.set(
                "Access-Control-Allow-Methods",
                "GET, POST, PUT, DELETE, OPTIONS"
        );


        headers.set(
                "Access-Control-Allow-Headers",
                "Content-Type, Authorization"
        );

        // Lets the browser read the file name of a CSV download across origins.
        headers.set(
                "Access-Control-Expose-Headers",
                "Content-Disposition"
        );


        headers.set(
                "Content-Type",
                "application/json; charset=UTF-8"
        );
    }


    // =========================================================
    // QUERY PARAMETERS
    // =========================================================

    private static Map<String, String> parseQueryParameters(
            String query) {

        Map<String, String> params =
                new HashMap<>();


        if (query == null
                || query.isEmpty()) {

            return params;
        }


        String[] pairs =
                query.split("&");


        for (String pair : pairs) {

            String[] keyValue =
                    pair.split("=", 2);


            if (keyValue.length == 2) {

                String key =
                        URLDecoder.decode(
                                keyValue[0],
                                StandardCharsets.UTF_8
                        );


                String value =
                        URLDecoder.decode(
                                keyValue[1],
                                StandardCharsets.UTF_8
                        );


                params.put(
                        key,
                        value
                );
            }
        }


        return params;
    }


    // =========================================================
    // READ REQUEST BODY
    // =========================================================

    private static String readRequestBody(
            HttpExchange exchange)
            throws IOException {

        try (
                InputStream inputStream =
                        exchange.getRequestBody()
        ) {

            // Cap the body so one huge request cannot exhaust server memory.
            byte[] bytes =
                    inputStream.readNBytes(Config.MAX_BODY_BYTES + 1);

            if (bytes.length > Config.MAX_BODY_BYTES) {
                throw new IOException("Request body is too large");
            }


            return new String(
                    bytes,
                    StandardCharsets.UTF_8
            );
        }
    }


    // =========================================================
    // SIMPLE JSON OBJECT PARSER
    // =========================================================

    private static Map<String, String> parseJsonObject(
            String json) {

        Map<String, String> result =
                new HashMap<>();


        if (json == null) {

            return result;
        }


        String text =
                json.trim();


        if (text.startsWith("{")) {

            text =
                    text.substring(1);
        }


        if (text.endsWith("}")) {

            text =
                    text.substring(
                            0,
                            text.length() - 1
                    );
        }


        int index = 0;


        while (index < text.length()) {

            while (
                    index < text.length()
                            && (
                            Character.isWhitespace(
                                    text.charAt(index)
                            )
                                    || text.charAt(index) == ','
                    )
            ) {

                index++;
            }


            if (index >= text.length()) {

                break;
            }


            if (text.charAt(index) != '"') {

                break;
            }


            int keyEnd =
                    findClosingQuote(
                            text,
                            index + 1
                    );


            if (keyEnd == -1) {

                break;
            }


            String key =
                    unescapeJson(
                            text.substring(
                                    index + 1,
                                    keyEnd
                            )
                    );


            index =
                    keyEnd + 1;


            while (
                    index < text.length()
                            && Character.isWhitespace(
                            text.charAt(index)
                    )
            ) {

                index++;
            }


            if (
                    index >= text.length()
                            || text.charAt(index) != ':'
            ) {

                break;
            }


            index++;


            while (
                    index < text.length()
                            && Character.isWhitespace(
                            text.charAt(index)
                    )
            ) {

                index++;
            }


            if (index >= text.length()) {

                break;
            }


            String value;


            if (text.charAt(index) == '"') {

                int valueEnd =
                        findClosingQuote(
                                text,
                                index + 1
                        );


                if (valueEnd == -1) {

                    break;
                }


                value =
                        unescapeJson(
                                text.substring(
                                        index + 1,
                                        valueEnd
                                )
                        );


                index =
                        valueEnd + 1;

            } else {

                int valueEnd =
                        index;


                while (
                        valueEnd < text.length()
                                && text.charAt(valueEnd) != ','
                ) {

                    valueEnd++;
                }


                value =
                        text.substring(
                                index,
                                valueEnd
                        ).trim();


                index =
                        valueEnd;
            }


            result.put(
                    key,
                    value
            );
        }


        return result;
    }


    // =========================================================
    // FIND CLOSING QUOTE
    // =========================================================

    private static int findClosingQuote(
            String text,
            int start) {

        boolean escaped = false;


        for (
                int i = start;
                i < text.length();
                i++
        ) {

            char c =
                    text.charAt(i);


            if (escaped) {

                escaped = false;

                continue;
            }


            if (c == '\\') {

                escaped = true;

                continue;
            }


            if (c == '"') {

                return i;
            }
        }


        return -1;
    }


    // =========================================================
    // GET JSON VALUE
    // =========================================================

    private static String getJsonValue(
            Map<String, String> data,
            String key) {

        String value =
                data.get(key);


        if (value == null) {

            return "";
        }


        return value;
    }


    // =========================================================
    // CHECK EMPTY
    // =========================================================

    private static boolean isEmpty(
            String value) {

        return value == null
                || value.trim().isEmpty();
    }


    // =========================================================
    // SEND RESPONSE
    // =========================================================

    private static void sendResponse(
            HttpExchange exchange,
            int statusCode,
            String response) {

        try {

            byte[] responseBytes =
                    response.getBytes(
                            StandardCharsets.UTF_8
                    );


            exchange.sendResponseHeaders(
                    statusCode,
                    responseBytes.length
            );


            try (
                    OutputStream output =
                            exchange.getResponseBody()
            ) {

                output.write(
                        responseBytes
                );
            }


        } catch (IOException e) {

            System.out.println(
                    "Error sending API response: "
                            + e.getMessage()
            );
        }
    }


    // =========================================================
    // CONVERT MEDICINE TABLE MODEL TO JSON
    // =========================================================

    private static String tableModelToJson(
            DefaultTableModel model) {

        StringBuilder json =
                new StringBuilder();


        json.append("[");


        for (
                int row = 0;
                row < model.getRowCount();
                row++
        ) {

            if (row > 0) {

                json.append(",");
            }


            json.append("{");


            json.append("\"id\":\"")
                    .append(
                            escapeJson(
                                    model.getValueAt(
                                            row,
                                            0
                                    )
                            )
                    )
                    .append("\",");


            json.append("\"name\":\"")
                    .append(
                            escapeJson(
                                    model.getValueAt(
                                            row,
                                            1
                                    )
                            )
                    )
                    .append("\",");


            json.append("\"batch\":\"")
                    .append(
                            escapeJson(
                                    model.getValueAt(
                                            row,
                                            2
                                    )
                            )
                    )
                    .append("\",");


            json.append("\"category\":\"")
                    .append(
                            escapeJson(
                                    model.getValueAt(
                                            row,
                                            3
                                    )
                            )
                    )
                    .append("\",");


            json.append("\"quantity\":")
                    .append(
                            model.getValueAt(
                                    row,
                                    4
                            )
                    )
                    .append(",");


            json.append("\"price\":")
                    .append(
                            model.getValueAt(
                                    row,
                                    5
                            )
                    )
                    .append(",");


            json.append("\"manufacturer\":\"")
                    .append(
                            escapeJson(
                                    model.getValueAt(
                                            row,
                                            6
                                    )
                            )
                    )
                    .append("\",");


            json.append("\"disease\":\"")
                    .append(
                            escapeJson(
                                    model.getValueAt(
                                            row,
                                            7
                                    )
                            )
                    )
                    .append("\",");


            json.append("\"expiry\":\"")
                    .append(
                            escapeJson(
                                    model.getValueAt(
                                            row,
                                            8
                                    )
                            )
                    )
                    .append("\",");


            json.append("\"gstRate\":")
                    .append(model.getValueAt(row, 9))
                    .append(",");


            json.append("\"hsn\":\"")
                    .append(
                            escapeJson(
                                    model.getValueAt(row, 10)
                            )
                    )
                    .append("\"");


            json.append("}");
        }


        json.append("]");


        return json.toString();
    }


    // =========================================================
    // JSON ESCAPING
    // =========================================================

    private static String escapeJson(
            Object value) {

        if (value == null) {

            return "";
        }


        String text =
                String.valueOf(value);


        return text
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\b", "\\b")
                .replace("\f", "\\f")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }


    // =========================================================
    // JSON UNESCAPING
    // =========================================================

    private static String unescapeJson(
            String value) {

        return value
                .replace("\\\"", "\"")
                .replace("\\\\", "\\")
                .replace("\\/", "/")
                .replace("\\b", "\b")
                .replace("\\f", "\f")
                .replace("\\n", "\n")
                .replace("\\r", "\r")
                .replace("\\t", "\t");
    }
    // =========================================================
// EMAIL OTP + GOOGLE AUTH ROUTES
// =========================================================
private static void authRoute(HttpExchange exchange, String route) throws IOException {
    addCorsHeaders(exchange);
    String method = exchange.getRequestMethod();

    if ("OPTIONS".equalsIgnoreCase(method)) {
        sendResponse(exchange, 204, "");
        return;
    }
    if (!"POST".equalsIgnoreCase(method)) {
        sendResponse(exchange, 405, "{\"success\":false,\"error\":\"Method Not Allowed\"}");
        return;
    }

    String ip = clientIp(exchange);
    int ipLimit = "verify".equals(route) ? 20 : 10;
    if (!SessionManager.allow(route + "|" + ip, ipLimit, 15 * 60 * 1000L)) {
        sendResponse(exchange, 429,
            "{\"success\":false,\"error\":\"Too many attempts. Please try again in a few minutes.\"}");
        return;
    }

    try {
        Map<String, String> data = parseJsonObject(readRequestBody(exchange));

        if ("login".equals(route) || "otp-login".equals(route)) {
            String who = data.getOrDefault("username", "").trim().toLowerCase();
            if (!SessionManager.allow("acct|" + who, 8, 15 * 60 * 1000L)) {
                sendResponse(exchange, 429,
                    "{\"success\":false,\"error\":\"Too many attempts for this account. Try again later.\"}");
                return;
            }
        }

        // Stops someone from flooding a victim's inbox with reset codes.
        if ("forgot-password".equals(route)) {
            String mail = data.getOrDefault("email", "").trim().toLowerCase();
            if (!SessionManager.allow("mail|" + mail, 5, 60 * 60 * 1000L)) {
                sendResponse(exchange, 429,
                    "{\"success\":false,\"error\":\"Too many requests for this email. Try again in an hour.\"}");
                return;
            }
        }

        AuthService.Result result;
        switch (route) {
            case "signup":
                result = AuthService.beginSignup(data);
                break;
            case "login":
                result = AuthService.beginLogin(data);
                break;
            case "google":
                result = AuthService.beginGoogle(data);
                break;
            case "verify":
                result = AuthService.verifyOtp(data);
                break;
            case "resend":
                result = AuthService.resendOtp(data);
                break;
            case "otp-login":
                result = AuthService.beginOtpLogin(data);
                break;
            case "forgot-password":
                result = AuthService.beginForgotPassword(data);
                break;
            case "reset-password":
                result = AuthService.resetPassword(data);
                break;
            default:
                result = new AuthService.Result(404, "{\"success\":false,\"error\":\"Not found\"}");
        }
        sendResponse(exchange, result.status, result.body);
        recordSignIn(exchange, route, result);
    } catch (IllegalArgumentException e) {
        // e.g. an invalid or expired Google token: the person's problem, not a server fault
        System.out.println("Auth refused (" + route + "): " + e.getMessage());
        sendResponse(exchange, 401, "{\"success\":false,\"error\":" + Json.quote(
                e.getMessage() == null ? "Sign-in was refused" : e.getMessage()) + "}");
    } catch (java.io.IOException e) {
        // Google or the mail server could not be reached (internet, firewall, VPN)
        System.out.println("Auth network error (" + route + "): " + e);
        sendResponse(exchange, 502, "{\"success\":false,\"error\":\"Could not reach Google or the mail server. Check the internet connection and try again.\"}");
    } catch (IllegalStateException e) {
        // A required setting (GOOGLE_CLIENT_ID, SMTP_*) is missing on the server.
        System.out.println("Auth not configured (" + route + "): " + e.getMessage());
        sendResponse(exchange, 503, "{\"success\":false,\"error\":\"This sign-in method is not set up on the server yet.\"}");
    } catch (Exception e) {
        System.out.println("Auth error (" + route + "): " + e);
        e.printStackTrace();
        sendResponse(exchange, 500, "{\"success\":false,\"error\":\"Authentication request failed\"}");
    }
}

// =========================================================
// CLIENT IP (for rate limiting)
// =========================================================

/**
 * Behind our own reverse proxy every connection comes from the proxy, so the
 * real visitor is in X-Forwarded-For. That header is only trusted when
 * TRUST_PROXY=true; otherwise anybody could fake it to dodge the limits.
 */
private static String clientIp(HttpExchange exchange) {
    if (Config.TRUST_PROXY) {
        String forwarded = exchange.getRequestHeaders().getFirst("X-Forwarded-For");
        if (forwarded != null && !forwarded.trim().isEmpty()) {
            return forwarded.split(",")[0].trim();
        }
    }
    return exchange.getRemoteAddress().getAddress().getHostAddress();
}

// =========================================================
// SESSION GUARD + ACCOUNT ROUTES
// =========================================================

/** Returns the signed-in username, or sends 401 and returns null. */
private static String requireAuth(HttpExchange exchange) {
    SessionManager.Principal who = SessionManager.principalFor(exchange);
    if (who == null) {
        sendResponse(exchange, 401,
            "{\"success\":false,\"error\":\"Please sign in to continue\"}");
        return null;
    }
    // Handlers read the caller's shop from here, never from the request body/URL.
    exchange.setAttribute("shopId", who.shopId);
    exchange.setAttribute("role", who.role);
    exchange.setAttribute("username", who.username);

    // Plans: an ended shop can still read and download; a disabled one can do neither.
    String path = exchange.getRequestURI().getPath();
    String method = exchange.getRequestMethod();
    boolean accountPath = path.startsWith("/api/plan") || path.startsWith("/api/profile")
            || path.startsWith("/api/change-password") || path.startsWith("/api/admin");

    if (!accountPath) {
        Plans.State plan = Plans.stateOf(who.shopId);

        if (plan.disabled) {
            sendResponse(exchange, 403,
                "{\"success\":false,\"error\":\"This shop has been suspended. Please contact support.\",\"code\":\"shop_disabled\"}");
            return null;
        }

        if (!plan.canWrite && !"GET".equalsIgnoreCase(method)) {
            sendResponse(exchange, 402,
                "{\"success\":false,\"error\":\"Your plan has ended. Your data is safe: renew to keep billing and updating stock.\",\"code\":\"plan_ended\"}");
            return null;
        }
    }

    return who.username;
}

/** The shop of the signed-in caller. Only valid after requireAuth() succeeded. */
private static long shopId(HttpExchange exchange) {
    return (Long) exchange.getAttribute("shopId");
}

private static void logoutRoute(HttpExchange exchange) {
    addCorsHeaders(exchange);
    if ("OPTIONS".equalsIgnoreCase(exchange.getRequestMethod())) {
        sendResponse(exchange, 204, "");
        return;
    }
    SessionManager.revoke(SessionManager.tokenFrom(exchange));
    sendResponse(exchange, 200, "{\"success\":true}");
}

private static void profileRoute(HttpExchange exchange) {
    addCorsHeaders(exchange);
    String method = exchange.getRequestMethod();
    if ("OPTIONS".equalsIgnoreCase(method)) {
        sendResponse(exchange, 204, "");
        return;
    }
    String username = requireAuth(exchange);
    if (username == null) return;

    try {
        if ("GET".equalsIgnoreCase(method)) {
            String[] p = DatabaseHelper.getUserProfile(username);
            if (p == null) {
                sendResponse(exchange, 404, "{\"success\":false,\"error\":\"Account not found\"}");
                return;
            }
            sendResponse(exchange, 200, "{\"success\":true,\"username\":\"" + escapeJson(p[0])
                    + "\",\"email\":\"" + escapeJson(p[1]) + "\",\"admin\":" + Plans.isAdminEmail(p[1]) + "}");
            return;
        }

        if ("PUT".equalsIgnoreCase(method)) {
            Map<String, String> d = parseJsonObject(readRequestBody(exchange));
            String newUsername = d.getOrDefault("username", "").trim();
            String email = d.getOrDefault("email", "").trim().toLowerCase();

            if (!newUsername.matches("[A-Za-z0-9_.-]{3,30}")) {
                sendResponse(exchange, 400, "{\"success\":false,\"error\":\"Invalid username\"}");
                return;
            }
            if (!email.matches("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$")) {
                sendResponse(exchange, 400, "{\"success\":false,\"error\":\"Invalid email address\"}");
                return;
            }

            // Email changes are not verified by a code, so nobody may move onto a platform-admin address.
            String[] mine = DatabaseHelper.getUserProfile(username);
            if (Plans.isAdminEmail(email) && (mine == null || !email.equalsIgnoreCase(mine[1]))) {
                sendResponse(exchange, 403, "{\"success\":false,\"error\":\"That email cannot be used\"}");
                return;
            }

            if (mine != null && !email.equalsIgnoreCase(mine[1])) {
                try {
                    AuthService.Result started = AuthService.beginEmailChange(username, newUsername, email);
                    sendResponse(exchange, started.status, started.body);
                } catch (IOException | IllegalStateException e) {
                    System.out.println("Email change code not sent: " + e.getMessage());
                    sendResponse(exchange, 503, "{\"success\":false,\"error\":\"The verification code could not be sent. Please try again.\"}");
                }
                return;
            }

            int result = DatabaseHelper.updateUserProfile(username, newUsername, email);
            if (result == -1) {
                sendResponse(exchange, 409, "{\"success\":false,\"error\":\"That username or email is already in use\"}");
                return;
            }
            if (result == 0) {
                sendResponse(exchange, 404, "{\"success\":false,\"error\":\"Account not found\"}");
                return;
            }
            SessionManager.revokeAllFor(username); // user must sign in again
            sendResponse(exchange, 200, "{\"success\":true,\"username\":\"" + escapeJson(newUsername)
                    + "\",\"email\":\"" + escapeJson(email) + "\"}");
            return;
        }

        sendResponse(exchange, 405, "{\"success\":false,\"error\":\"Method Not Allowed\"}");
    } catch (Exception e) {
        e.printStackTrace();
        sendResponse(exchange, 500, "{\"success\":false,\"error\":\"Profile request failed\"}");
    }
}

private static void changePasswordRoute(HttpExchange exchange) {
    addCorsHeaders(exchange);
    String method = exchange.getRequestMethod();
    if ("OPTIONS".equalsIgnoreCase(method)) {
        sendResponse(exchange, 204, "");
        return;
    }
    if (!"POST".equalsIgnoreCase(method)) {
        sendResponse(exchange, 405, "{\"success\":false,\"error\":\"Method Not Allowed\"}");
        return;
    }
    String username = requireAuth(exchange);
    if (username == null) return;

    try {
        if (!SessionManager.allow("pw|" + username.toLowerCase(), 5, 15 * 60 * 1000L)) {
            sendResponse(exchange, 429, "{\"success\":false,\"error\":\"Too many attempts. Try again later.\"}");
            return;
        }
        Map<String, String> d = parseJsonObject(readRequestBody(exchange));
        String current = d.getOrDefault("currentPassword", "");
        String next = d.getOrDefault("newPassword", "");

        String weak = AuthService.passwordProblem(next);
        if (weak != null) {
            sendResponse(exchange, 400, "{\"success\":false,\"error\":" + Json.quote(weak) + "}");
            return;
        }
        if (!DatabaseHelper.changeUserPassword(username, current, next)) {
            sendResponse(exchange, 400, "{\"success\":false,\"error\":\"Current password is incorrect\"}");
            return;
        }
        SessionManager.revokeAllFor(username);
        sendResponse(exchange, 200, "{\"success\":true}");
    } catch (Exception e) {
        e.printStackTrace();
        sendResponse(exchange, 500, "{\"success\":false,\"error\":\"Password change failed\"}");
    }
}

// =========================================================
// ROLES + TEAM MANAGEMENT
// =========================================================

private static boolean hasRole(HttpExchange exchange, String... roles) {
    Object role = exchange.getAttribute("role");
    for (String allowed : roles) {
        if (allowed.equals(role)) return true;
    }
    return false;
}

private static void forbidden(HttpExchange exchange) {
    sendResponse(exchange, 403,
        "{\"success\":false,\"error\":\"You do not have permission to do this\"}");
}

private static boolean isStaffRole(String role) {
    return "pharmacist".equals(role) || "cashier".equals(role);
}

private static void recordSignIn(HttpExchange exchange, String route, AuthService.Result result) {
    if (result.status != 200 || !result.body.contains("\"token\"")) return;
    try {
        String username = Json.str(Json.asObject(Json.parse(result.body)), "username");
        DatabaseHelper.UserInfo who = DatabaseHelper.getUserInfo(username);
        if (who != null) {
            Audit.record(who.shopId, who.username, who.role, "account.signin", "", route, clientIp(exchange));
        }
    } catch (Exception e) {
        System.out.println("Sign-in log failed: " + e.getMessage());
    }
}

private static String memberJson(String username, String email, String role) {
    return "{\"username\":\"" + escapeJson(username)
            + "\",\"email\":\"" + escapeJson(email)
            + "\",\"role\":\"" + escapeJson(role) + "\"}";
}

// GET    /api/team            list everyone in the shop
// POST   /api/team            add a pharmacist or cashier
// PUT    /api/team/{username} change their role
// DELETE /api/team/{username} remove them
// Owner only. The shop always comes from the session, never from the request.
private static void teamRoute(HttpExchange exchange) {
    addCorsHeaders(exchange);
    String method = exchange.getRequestMethod();
    if ("OPTIONS".equalsIgnoreCase(method)) {
        sendResponse(exchange, 204, "");
        return;
    }
    if (requireAuth(exchange) == null) return;
    if (!hasRole(exchange, "owner")) {
        forbidden(exchange);
        return;
    }

    long shopId = shopId(exchange);

    try {
        String path = exchange.getRequestURI().getPath();
        String rest = path.length() > "/api/team".length() ? path.substring("/api/team".length()) : "";
        if (rest.startsWith("/")) rest = rest.substring(1);
        String target = URLDecoder.decode(rest, StandardCharsets.UTF_8).trim();

        if (target.isEmpty() && "GET".equalsIgnoreCase(method)) {
            StringBuilder json = new StringBuilder("{\"success\":true,\"members\":[");
            boolean first = true;
            for (DatabaseHelper.TeamMember m : DatabaseHelper.listTeam(shopId)) {
                if (!first) json.append(',');
                json.append(memberJson(m.username, m.email, m.role));
                first = false;
            }
            json.append("]}");
            sendResponse(exchange, 200, json.toString());
            return;
        }

        if (target.isEmpty() && "POST".equalsIgnoreCase(method)) {
            if (!SessionManager.allow("team|" + shopId, 20, 60 * 60 * 1000L)) {
                sendResponse(exchange, 429, "{\"success\":false,\"error\":\"Too many accounts created. Try again later.\"}");
                return;
            }

            Map<String, String> d = parseJsonObject(readRequestBody(exchange));
            String username = d.getOrDefault("username", "").trim();
            String email = d.getOrDefault("email", "").trim().toLowerCase();
            String password = d.getOrDefault("password", "");
            String role = d.getOrDefault("role", "").trim().toLowerCase();

            if (!username.matches("[A-Za-z0-9_.-]{3,30}")) {
                sendResponse(exchange, 400, "{\"success\":false,\"error\":\"Username must be 3-30 characters (letters, numbers, dots, underscores, hyphens)\"}");
                return;
            }
            if (!email.matches("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$")) {
                sendResponse(exchange, 400, "{\"success\":false,\"error\":\"Enter a valid email address\"}");
                return;
            }
            if (password.length() < 8 || password.length() > 128) {
                sendResponse(exchange, 400, "{\"success\":false,\"error\":\"Password must be 8 to 128 characters\"}");
                return;
            }
            if (!isStaffRole(role)) {
                sendResponse(exchange, 400, "{\"success\":false,\"error\":\"Role must be pharmacist or cashier\"}");
                return;
            }

            int result = DatabaseHelper.createStaffUser(shopId, username, email, password, role);
            if (result == -1) {
                sendResponse(exchange, 409, "{\"success\":false,\"error\":\"That username or email is already registered\"}");
                return;
            }
            if (result == 0) {
                sendResponse(exchange, 500, "{\"success\":false,\"error\":\"Could not create the account\"}");
                return;
            }
            sendResponse(exchange, 200, "{\"success\":true,\"member\":" + memberJson(username, email, role) + "}");
            return;
        }

        if (!target.isEmpty() && "PUT".equalsIgnoreCase(method)) {
            Map<String, String> d = parseJsonObject(readRequestBody(exchange));
            String role = d.getOrDefault("role", "").trim().toLowerCase();

            if (!isStaffRole(role)) {
                sendResponse(exchange, 400, "{\"success\":false,\"error\":\"Role must be pharmacist or cashier\"}");
                return;
            }
            if (!DatabaseHelper.updateTeamRole(shopId, target, role)) {
                sendResponse(exchange, 404, "{\"success\":false,\"error\":\"Team member not found\"}");
                return;
            }
            SessionManager.revokeAllFor(target); // they sign in again with the new role
            sendResponse(exchange, 200, "{\"success\":true}");
            return;
        }

        if (!target.isEmpty() && "DELETE".equalsIgnoreCase(method)) {
            if (!DatabaseHelper.removeTeamMember(shopId, target)) {
                sendResponse(exchange, 404, "{\"success\":false,\"error\":\"Team member not found or cannot be removed\"}");
                return;
            }
            SessionManager.revokeAllFor(target);
            sendResponse(exchange, 200, "{\"success\":true}");
            return;
        }

        sendResponse(exchange, 405, "{\"success\":false,\"error\":\"Method Not Allowed\"}");
    } catch (Exception e) {
        e.printStackTrace();
        sendResponse(exchange, 500, "{\"success\":false,\"error\":\"Team request failed\"}");
    }
}

// =========================================================
// GST FIELDS ON MEDICINES
// =========================================================

/** Returns a message when gstRate/hsn are present but invalid, otherwise null. */
private static String gstError(Map<String, String> data) {
    String rate = data.get("gstRate");
    if (rate != null && !rate.trim().isEmpty()) {
        try {
            java.math.BigDecimal value = new java.math.BigDecimal(rate.trim());
            if (value.stripTrailingZeros().scale() > 0
                    || !Billing.GST_RATES.contains(value.intValueExact())) {
                return "GST rate must be 0, 5, 12, 18 or 28";
            }
        } catch (ArithmeticException | NumberFormatException e) {
            return "GST rate must be 0, 5, 12, 18 or 28";
        }
    }

    String hsn = data.get("hsn");
    if (hsn != null && !hsn.trim().isEmpty() && !hsn.trim().matches("[0-9]{4,8}")) {
        return "HSN code must be 4 to 8 digits";
    }

    return null;
}

private static Double gstRateOf(Map<String, String> data) {
    String rate = data.get("gstRate");
    if (rate == null || rate.trim().isEmpty()) return null;
    return new java.math.BigDecimal(rate.trim()).doubleValue();
}

/** The cost before GST, or null when left empty or not a sensible number. */
private static Double costOf(Map<String, String> data) {
    String text = data.get("costPrice");
    if (text == null || text.trim().isEmpty()) return null;
    try {
        double value = Double.parseDouble(text.trim());
        return value >= 0 && value <= 100000000d ? value : null;
    } catch (NumberFormatException e) {
        return null;
    }
}

private static String hsnOf(Map<String, String> data) {
    String hsn = data.get("hsn");
    return hsn == null || hsn.trim().isEmpty() ? null : hsn.trim();
}

// =========================================================
// SHOP DETAILS (printed on invoices)
// =========================================================

private static final String GSTIN_PATTERN =
    "^[0-9]{2}[A-Z]{5}[0-9]{4}[A-Z][1-9A-Z]Z[0-9A-Z]$";

private static String shopJson(DatabaseHelper.Shop s) {
    return "{\"success\":true,\"name\":" + Json.quote(s.name)
            + ",\"address\":" + Json.quote(s.address)
            + ",\"phone\":" + Json.quote(s.phone)
            + ",\"gstin\":" + Json.quote(s.gstin)
            + ",\"drugLicense\":" + Json.quote(s.drugLicense)
            + ",\"state\":" + Json.quote(s.state)
            + ",\"invoicePrefix\":" + Json.quote(s.invoicePrefix)
            + ",\"pricesIncludeGst\":" + s.pricesIncludeGst + "}";
}

// GET /api/shop  (everyone)   PUT /api/shop  (owner only)
private static void shopRoute(HttpExchange exchange) {
    addCorsHeaders(exchange);
    String method = exchange.getRequestMethod();
    if ("OPTIONS".equalsIgnoreCase(method)) {
        sendResponse(exchange, 204, "");
        return;
    }
    if (requireAuth(exchange) == null) return;

    long shopId = shopId(exchange);

    try {
        if ("GET".equalsIgnoreCase(method)) {
            DatabaseHelper.Shop shop = DatabaseHelper.getShop(shopId);
            if (shop == null) {
                sendResponse(exchange, 404, "{\"success\":false,\"error\":\"Shop not found\"}");
                return;
            }
            sendResponse(exchange, 200, shopJson(shop));
            return;
        }

        if ("PUT".equalsIgnoreCase(method)) {
            if (!hasRole(exchange, "owner")) {
                forbidden(exchange);
                return;
            }

            Map<String, Object> d = Json.asObject(Json.parse(readRequestBody(exchange)));
            DatabaseHelper.Shop s = new DatabaseHelper.Shop();
            s.name = Json.str(d, "name");
            s.address = Json.str(d, "address");
            s.phone = Json.str(d, "phone");
            s.gstin = Json.str(d, "gstin").toUpperCase();
            s.drugLicense = Json.str(d, "drugLicense");
            s.state = Json.str(d, "state");
            s.invoicePrefix = Json.str(d, "invoicePrefix");
            s.pricesIncludeGst = !Boolean.FALSE.equals(d.get("pricesIncludeGst"));

            String problem = null;
            if (s.name.length() < 2 || s.name.length() > 80) problem = "Shop name must be 2 to 80 characters";
            else if (s.address.length() > 200) problem = "Address must be 200 characters or fewer";
            else if (!s.phone.matches("[0-9+()\\- ]{0,20}")) problem = "Phone can only contain digits, spaces, + ( ) -";
            else if (!s.gstin.isEmpty() && !s.gstin.matches(GSTIN_PATTERN)) problem = "GSTIN is not valid (15 characters, e.g. 27ABCDE1234F1Z5)";
            else if (s.drugLicense.length() > 60) problem = "Drug licence number is too long";
            else if (s.state.length() > 40) problem = "State name is too long";
            else if (!s.invoicePrefix.matches("[A-Za-z0-9/-]{0,10}")) problem = "Invoice prefix can have up to 10 letters, digits, - or /";

            if (problem != null) {
                sendResponse(exchange, 400, "{\"success\":false,\"error\":" + Json.quote(problem) + "}");
                return;
            }

            if (!DatabaseHelper.updateShop(shopId, s)) {
                sendResponse(exchange, 500, "{\"success\":false,\"error\":\"Could not save shop details\"}");
                return;
            }
            sendResponse(exchange, 200, shopJson(DatabaseHelper.getShop(shopId)));
            return;
        }

        sendResponse(exchange, 405, "{\"success\":false,\"error\":\"Method Not Allowed\"}");
    } catch (Json.JsonException e) {
        sendResponse(exchange, 400, "{\"success\":false,\"error\":" + Json.quote("Invalid request: " + e.getMessage()) + "}");
    } catch (Exception e) {
        e.printStackTrace();
        sendResponse(exchange, 500, "{\"success\":false,\"error\":\"Shop request failed\"}");
    }
}

// =========================================================
// INVOICES (multi-item GST bills)
// =========================================================
// POST /api/invoices              create a bill (every role)
// GET  /api/invoices?search=&limit=&offset=   recent bills
// GET  /api/invoices/{id}         one bill, ready to print
// POST /api/invoices/{id}/cancel  cancel + restock (owner, pharmacist)

private static void invoiceRoute(HttpExchange exchange) {
    addCorsHeaders(exchange);
    String method = exchange.getRequestMethod();
    if ("OPTIONS".equalsIgnoreCase(method)) {
        sendResponse(exchange, 204, "");
        return;
    }
    String username = requireAuth(exchange);
    if (username == null) return;

    long shopId = shopId(exchange);

    try {
        String path = exchange.getRequestURI().getPath();
        String rest = path.length() > "/api/invoices".length() ? path.substring("/api/invoices".length()) : "";
        if (rest.startsWith("/")) rest = rest.substring(1);
        String[] parts = rest.isEmpty() ? new String[0] : rest.split("/");

        // ---- list ----
        if (parts.length == 0 && "GET".equalsIgnoreCase(method)) {
            Map<String, String> query = parseQueryParameters(exchange.getRequestURI().getRawQuery());
            int limit = 50, offset = 0;
            try { limit = Integer.parseInt(query.getOrDefault("limit", "50")); } catch (NumberFormatException ignored) { }
            try { offset = Integer.parseInt(query.getOrDefault("offset", "0")); } catch (NumberFormatException ignored) { }
            sendResponse(exchange, 200, Billing.listJson(shopId, query.getOrDefault("search", ""), limit, offset));
            return;
        }

        // ---- create ----
        if (parts.length == 0 && "POST".equalsIgnoreCase(method)) {
            Map<String, Object> d = Json.asObject(Json.parse(readRequestBody(exchange)));

            String phone = Json.str(d, "customerPhone");
            String gstin = Json.str(d, "customerGstin").toUpperCase();
            String mode = Json.str(d, "paymentMode").toLowerCase();
            if (mode.isEmpty()) mode = "cash";

            if (Json.str(d, "customerName").length() > 80) {
                sendResponse(exchange, 400, "{\"success\":false,\"error\":\"Customer name is too long\"}");
                return;
            }
            if (!phone.matches("[0-9+()\\- ]{0,20}")) {
                sendResponse(exchange, 400, "{\"success\":false,\"error\":\"Customer phone is not valid\"}");
                return;
            }
            if (!gstin.isEmpty() && !gstin.matches(GSTIN_PATTERN)) {
                sendResponse(exchange, 400, "{\"success\":false,\"error\":\"Customer GSTIN is not valid\"}");
                return;
            }

            Object rawItems = d.get("items");
            if (!(rawItems instanceof java.util.List)) {
                sendResponse(exchange, 400, "{\"success\":false,\"error\":\"Add at least one medicine to the bill\"}");
                return;
            }

            java.util.List<Billing.ItemRequest> items = new java.util.ArrayList<>();
            for (Object raw : (java.util.List<?>) rawItems) {
                Map<String, Object> row = Json.asObject(raw);
                java.math.BigDecimal quantity = Json.num(row, "quantity");
                java.math.BigDecimal discount = Json.num(row, "discountPercent");

                if (quantity == null || quantity.stripTrailingZeros().scale() > 0
                        || quantity.compareTo(java.math.BigDecimal.ZERO) <= 0
                        || quantity.compareTo(java.math.BigDecimal.valueOf(100000)) > 0) {
                    sendResponse(exchange, 400, "{\"success\":false,\"error\":\"Quantity must be a whole number between 1 and 100000\"}");
                    return;
                }

                Billing.ItemRequest item = new Billing.ItemRequest();
                item.medicineId = Json.str(row, "medicineId");
                item.quantity = quantity.intValueExact();
                item.discountPercent = discount == null ? java.math.BigDecimal.ZERO : discount;
                items.add(item);
            }

            Long customerId = null;
            java.math.BigDecimal customerIdNum = Json.num(d, "customerId");
            if (customerIdNum != null) {
                try {
                    customerId = customerIdNum.longValueExact();
                } catch (ArithmeticException e) {
                    sendResponse(exchange, 400, "{\"success\":false,\"error\":\"Customer is not valid\"}");
                    return;
                }
            }

            java.math.BigDecimal amountPaid = Json.num(d, "amountPaid");
            String paidMode = Json.str(d, "paidMode").toLowerCase();
            String clientRef = Json.str(d, "clientRef");

            if (clientRef.length() > 64) {
                sendResponse(exchange, 400, "{\"success\":false,\"error\":\"The request is not valid\"}");
                return;
            }

            Billing.Outcome outcome = Billing.createInvoice(
                    shopId, username, Json.str(d, "customerName"), phone, gstin, mode,
                    customerId, amountPaid, paidMode, clientRef, items);

            if (!outcome.ok) {
                sendResponse(exchange, outcome.status, "{\"success\":false,\"error\":" + Json.quote(outcome.message) + "}");
                return;
            }

            exchange.setAttribute("audit", "Bill " + outcome.invoiceNo + " (" + items.size() + " items, " + mode + ")");
            sendResponse(exchange, outcome.status, "{\"success\":true,\"invoice\":" + Billing.invoiceJson(shopId, outcome.invoiceId) + "}");
            return;
        }

        // ---- one invoice ----
        if (parts.length >= 1) {
            long invoiceId;
            try {
                invoiceId = Long.parseLong(parts[0]);
            } catch (NumberFormatException e) {
                sendResponse(exchange, 404, "{\"success\":false,\"error\":\"Invoice not found\"}");
                return;
            }

            if (parts.length == 1 && "GET".equalsIgnoreCase(method)) {
                String json = Billing.invoiceJson(shopId, invoiceId);
                if (json == null) {
                    sendResponse(exchange, 404, "{\"success\":false,\"error\":\"Invoice not found\"}");
                    return;
                }
                sendResponse(exchange, 200, "{\"success\":true,\"invoice\":" + json + "}");
                return;
            }

            if (parts.length == 2 && "cancel".equals(parts[1]) && "POST".equalsIgnoreCase(method)) {
                if (!hasRole(exchange, "owner", "pharmacist")) {
                    forbidden(exchange);
                    return;
                }
                Billing.Outcome outcome = Billing.cancelInvoice(shopId, username, invoiceId);
                if (!outcome.ok) {
                    sendResponse(exchange, outcome.status, "{\"success\":false,\"error\":" + Json.quote(outcome.message) + "}");
                    return;
                }
                sendResponse(exchange, 200, "{\"success\":true,\"message\":" + Json.quote(outcome.message) + "}");
                return;
            }

            // POST /api/invoices/{id}/return  -> credit note (owner, pharmacist)
            if (parts.length == 2 && "return".equals(parts[1]) && "POST".equalsIgnoreCase(method)) {
                if (!hasRole(exchange, "owner", "pharmacist")) {
                    forbidden(exchange);
                    return;
                }

                Map<String, Object> d = Json.asObject(Json.parse(readRequestBody(exchange)));

                String reason = Json.str(d, "reason");
                if (reason.isEmpty()) reason = "Customer return";
                if (reason.length() > 200) {
                    sendResponse(exchange, 400, "{\"success\":false,\"error\":\"Reason must be 200 characters or fewer\"}");
                    return;
                }

                String refundMode = Json.str(d, "refundMode").toLowerCase();
                if (refundMode.isEmpty()) refundMode = "cash";

                boolean restock = !Boolean.FALSE.equals(d.get("restock"));

                Object rawItems = d.get("items");
                if (!(rawItems instanceof java.util.List)) {
                    sendResponse(exchange, 400, "{\"success\":false,\"error\":\"Choose at least one medicine to return\"}");
                    return;
                }

                java.util.List<Returns.LineRequest> lines = new java.util.ArrayList<>();
                for (Object raw : (java.util.List<?>) rawItems) {
                    Map<String, Object> row = Json.asObject(raw);
                    java.math.BigDecimal itemId = Json.num(row, "itemId");
                    java.math.BigDecimal quantity = Json.num(row, "quantity");

                    if (itemId == null || itemId.stripTrailingZeros().scale() > 0
                            || quantity == null || quantity.stripTrailingZeros().scale() > 0
                            || quantity.compareTo(java.math.BigDecimal.ZERO) <= 0
                            || quantity.compareTo(java.math.BigDecimal.valueOf(100000)) > 0) {
                        sendResponse(exchange, 400, "{\"success\":false,\"error\":\"Each returned item needs a valid item and a whole-number quantity\"}");
                        return;
                    }

                    Returns.LineRequest line = new Returns.LineRequest();
                    line.itemId = itemId.longValueExact();
                    line.quantity = quantity.intValueExact();
                    lines.add(line);
                }

                Returns.Outcome outcome = Returns.createReturn(
                        shopId, username, invoiceId, reason, refundMode, restock, lines);

                if (!outcome.ok) {
                    sendResponse(exchange, outcome.status, "{\"success\":false,\"error\":" + Json.quote(outcome.message) + "}");
                    return;
                }

                sendResponse(exchange, 201, "{\"success\":true,\"creditNote\":"
                        + Returns.creditNoteJson(shopId, outcome.creditNoteId) + "}");
                return;
            }
        }

        sendResponse(exchange, 405, "{\"success\":false,\"error\":\"Method Not Allowed\"}");
    } catch (Json.JsonException e) {
        sendResponse(exchange, 400, "{\"success\":false,\"error\":" + Json.quote("Invalid request: " + e.getMessage()) + "}");
    } catch (Exception e) {
        e.printStackTrace();
        sendResponse(exchange, 500, "{\"success\":false,\"error\":\"Invoice request failed\"}");
    }
}

// =========================================================
// CREDIT NOTES (read only; they are created by returning items on an invoice)
// =========================================================
// GET /api/credit-notes?search=&limit=&offset=
// GET /api/credit-notes/{id}

private static void creditNoteRoute(HttpExchange exchange) {
    addCorsHeaders(exchange);
    String method = exchange.getRequestMethod();
    if ("OPTIONS".equalsIgnoreCase(method)) {
        sendResponse(exchange, 204, "");
        return;
    }
    if (requireAuth(exchange) == null) return;

    long shopId = shopId(exchange);

    try {
        if (!"GET".equalsIgnoreCase(method)) {
            sendResponse(exchange, 405, "{\"success\":false,\"error\":\"Method Not Allowed\"}");
            return;
        }

        String path = exchange.getRequestURI().getPath();
        String rest = path.length() > "/api/credit-notes".length() ? path.substring("/api/credit-notes".length()) : "";
        if (rest.startsWith("/")) rest = rest.substring(1);

        if (rest.isEmpty()) {
            Map<String, String> query = parseQueryParameters(exchange.getRequestURI().getRawQuery());
            int limit = 50, offset = 0;
            try { limit = Integer.parseInt(query.getOrDefault("limit", "50")); } catch (NumberFormatException ignored) { }
            try { offset = Integer.parseInt(query.getOrDefault("offset", "0")); } catch (NumberFormatException ignored) { }
            sendResponse(exchange, 200, Returns.listJson(shopId, query.getOrDefault("search", ""), limit, offset));
            return;
        }

        long noteId;
        try {
            noteId = Long.parseLong(rest);
        } catch (NumberFormatException e) {
            sendResponse(exchange, 404, "{\"success\":false,\"error\":\"Credit note not found\"}");
            return;
        }

        String json = Returns.creditNoteJson(shopId, noteId);
        if (json == null) {
            sendResponse(exchange, 404, "{\"success\":false,\"error\":\"Credit note not found\"}");
            return;
        }
        sendResponse(exchange, 200, "{\"success\":true,\"creditNote\":" + json + "}");
    } catch (Exception e) {
        e.printStackTrace();
        sendResponse(exchange, 500, "{\"success\":false,\"error\":\"Credit note request failed\"}");
    }
}

// =========================================================
// REPORTS (owner and pharmacist)
// =========================================================
// GET /api/reports/summary?from=YYYY-MM-DD&to=YYYY-MM-DD
// GET /api/reports/stock?days=90&low=10
// GET /api/reports/export?type=register|items|hsn|rate|inventory|expiry&from=&to=&days=

// ---------------------------------------------------------
// Customers and udhaar (credit)
// ---------------------------------------------------------
// GET    /api/customers?search=&dues=1       list with balances (all roles)
// POST   /api/customers                      add (all roles; only the owner sets a credit limit)
// GET    /api/customers/{id}                 profile + ledger + recent bills
// PUT    /api/customers/{id}                 edit
// DELETE /api/customers/{id}                 delete when it has no history (owner)
// POST   /api/customers/{id}/payments        money received (all roles)

private static void customersRoute(HttpExchange exchange) {
    addCorsHeaders(exchange);
    String method = exchange.getRequestMethod();
    if ("OPTIONS".equalsIgnoreCase(method)) {
        sendResponse(exchange, 204, "");
        return;
    }
    String username = requireAuth(exchange);
    if (username == null) return;

    long shopId = shopId(exchange);
    boolean isOwner = hasRole(exchange, "owner");

    try {
        String path = exchange.getRequestURI().getPath();
        String rest = path.length() > "/api/customers".length() ? path.substring("/api/customers".length()) : "";
        if (rest.startsWith("/")) rest = rest.substring(1);
        String[] parts = rest.isEmpty() ? new String[0] : rest.split("/");

        if (parts.length == 0 && "GET".equalsIgnoreCase(method)) {
            Map<String, String> query = parseQueryParameters(exchange.getRequestURI().getRawQuery());
            sendResponse(exchange, 200, Customers.listJson(shopId, query.getOrDefault("search", ""),
                    "1".equals(query.get("dues")),
                    intParam(query, "limit", 200, 1, 500), intParam(query, "offset", 0, 0, 1000000)));
            return;
        }

        if (parts.length == 0 && "POST".equalsIgnoreCase(method)) {
            Map<String, Object> d = Json.asObject(Json.parse(readRequestBody(exchange)));
            String error = validateCustomer(d);
            if (error != null) {
                sendResponse(exchange, 400, "{\"success\":false,\"error\":" + Json.quote(error) + "}");
                return;
            }
            java.math.BigDecimal limit = isOwner ? limitOf(d) : java.math.BigDecimal.ZERO;
            Customers.Outcome outcome = Customers.create(shopId, username, Json.str(d, "name").trim(),
                    Json.str(d, "phone"), Json.str(d, "gstin").toUpperCase(), Json.str(d, "address").trim(),
                    Json.str(d, "notes").trim(), limit == null ? java.math.BigDecimal.ZERO : limit);
            sendCustomerOutcome(exchange, outcome);
            return;
        }

        if (parts.length >= 1) {
            long id;
            try {
                id = Long.parseLong(parts[0]);
            } catch (NumberFormatException e) {
                sendResponse(exchange, 404, "{\"success\":false,\"error\":\"Customer not found\"}");
                return;
            }

            if (parts.length == 1 && "GET".equalsIgnoreCase(method)) {
                String json = Customers.detailJson(shopId, id);
                if (json == null) {
                    sendResponse(exchange, 404, "{\"success\":false,\"error\":\"Customer not found\"}");
                    return;
                }
                sendResponse(exchange, 200, json);
                return;
            }

            if (parts.length == 1 && "PUT".equalsIgnoreCase(method)) {
                Map<String, Object> d = Json.asObject(Json.parse(readRequestBody(exchange)));
                String error = validateCustomer(d);
                if (error != null) {
                    sendResponse(exchange, 400, "{\"success\":false,\"error\":" + Json.quote(error) + "}");
                    return;
                }
                java.math.BigDecimal limit = isOwner ? limitOf(d) : null;
                Customers.Outcome outcome = Customers.update(shopId, id, Json.str(d, "name").trim(),
                        Json.str(d, "phone"), Json.str(d, "gstin").toUpperCase(), Json.str(d, "address").trim(),
                        Json.str(d, "notes").trim(), limit);
                sendCustomerOutcome(exchange, outcome);
                return;
            }

            if (parts.length == 1 && "DELETE".equalsIgnoreCase(method)) {
                if (!isOwner) {
                    forbidden(exchange);
                    return;
                }
                sendCustomerOutcome(exchange, Customers.delete(shopId, id));
                return;
            }

            if (parts.length == 2 && "payments".equals(parts[1]) && "POST".equalsIgnoreCase(method)) {
                Map<String, Object> d = Json.asObject(Json.parse(readRequestBody(exchange)));
                java.math.BigDecimal amount = Json.num(d, "amount");
                String mode = Json.str(d, "mode").toLowerCase();
                if (mode.isEmpty()) mode = "cash";
                String note = Json.str(d, "note").trim();
                if (note.length() > 200) {
                    sendResponse(exchange, 400, "{\"success\":false,\"error\":\"The note is too long\"}");
                    return;
                }
                Customers.Outcome paid = Customers.receivePayment(shopId, username, id, amount, mode, note);
                if (paid.ok) exchange.setAttribute("audit", "Received " + amount + " by " + mode);
                sendCustomerOutcome(exchange, paid);
                return;
            }
        }

        sendResponse(exchange, 404, "{\"success\":false,\"error\":\"Not found\"}");

    } catch (IllegalArgumentException | Json.JsonException e) {
        sendResponse(exchange, 400, "{\"success\":false,\"error\":\"The request is not valid\"}");
    } catch (Exception e) {
        System.out.println("Customers error: " + e.getMessage());
        sendResponse(exchange, 500, "{\"success\":false,\"error\":\"Something went wrong\"}");
    }
}

private static String validateCustomer(Map<String, Object> d) {
    String name = Json.str(d, "name").trim();
    if (name.isEmpty()) return "Enter the customer name";
    if (name.length() > 80) return "Customer name is too long";
    if (!Json.str(d, "phone").matches("[0-9+()\\- ]{0,20}")) return "Phone number is not valid";
    String gstin = Json.str(d, "gstin").toUpperCase();
    if (!gstin.isEmpty() && !gstin.matches(GSTIN_PATTERN)) return "GSTIN is not valid";
    if (Json.str(d, "address").length() > 200) return "Address is too long";
    if (Json.str(d, "notes").length() > 300) return "Notes are too long";
    java.math.BigDecimal limit = Json.num(d, "creditLimit");
    if (limit != null && (limit.signum() < 0 || limit.scale() > 2
            || limit.compareTo(java.math.BigDecimal.valueOf(10000000)) > 0)) {
        return "Credit limit must be between 0 and 1,00,00,000";
    }
    return null;
}

private static java.math.BigDecimal limitOf(Map<String, Object> d) {
    return Json.num(d, "creditLimit");
}

private static void sendCustomerOutcome(HttpExchange exchange, Customers.Outcome outcome) {
    if (!outcome.ok) {
        sendResponse(exchange, outcome.status, "{\"success\":false,\"error\":" + Json.quote(outcome.message) + "}");
        return;
    }
    sendResponse(exchange, outcome.status, "{\"success\":true,\"message\":" + Json.quote(outcome.message)
            + ",\"id\":" + outcome.id
            + (outcome.balance == null ? "" : ",\"balance\":" + Json.money(outcome.balance)) + "}");
}

// ---------------------------------------------------------
// Suppliers and purchases (owner, pharmacist)
// ---------------------------------------------------------
// GET    /api/suppliers?search=&dues=1    list with what is owed
// POST   /api/suppliers                   add
// GET    /api/suppliers/{id}              details + ledger
// PUT    /api/suppliers/{id}              edit
// DELETE /api/suppliers/{id}              delete when it has no history (owner)
// POST   /api/suppliers/{id}/payments     money paid to the supplier
// GET    /api/purchases?search=           recent purchases
// POST   /api/purchases                   stock in
// GET    /api/purchases/{id}              one purchase
// POST   /api/purchases/{id}/cancel       take the stock back out
// GET    /api/purchases/medicines?search= medicines with cost, for the form

private static void sendPurchaseOutcome(HttpExchange exchange, Purchases.Outcome outcome) {
    if (!outcome.ok) {
        sendResponse(exchange, outcome.status, "{\"success\":false,\"error\":" + Json.quote(outcome.message) + "}");
        return;
    }
    sendResponse(exchange, outcome.status, "{\"success\":true,\"message\":" + Json.quote(outcome.message)
            + ",\"id\":" + outcome.id
            + (outcome.balance == null ? "" : ",\"balance\":" + Json.money(outcome.balance)) + "}");
}

private static String validateSupplier(Map<String, Object> d) {
    String name = Json.str(d, "name");
    if (name.isEmpty()) return "Enter the supplier name";
    if (name.length() > 100) return "Supplier name is too long";
    if (!Json.str(d, "phone").matches("[0-9+()\\- ]{0,20}")) return "Phone number is not valid";
    String gstin = Json.str(d, "gstin").toUpperCase();
    if (!gstin.isEmpty() && !gstin.matches(GSTIN_PATTERN)) return "GSTIN is not valid";
    if (Json.str(d, "drugLicense").length() > 60) return "Drug licence is too long";
    if (Json.str(d, "address").length() > 200) return "Address is too long";
    if (Json.str(d, "notes").length() > 300) return "Notes are too long";
    return null;
}

private static void suppliersRoute(HttpExchange exchange) {
    addCorsHeaders(exchange);
    String method = exchange.getRequestMethod();
    if ("OPTIONS".equalsIgnoreCase(method)) {
        sendResponse(exchange, 204, "");
        return;
    }
    String username = requireAuth(exchange);
    if (username == null) return;
    if (!hasRole(exchange, "owner", "pharmacist")) {
        forbidden(exchange);
        return;
    }

    long shopId = shopId(exchange);

    try {
        String path = exchange.getRequestURI().getPath();
        String rest = path.length() > "/api/suppliers".length() ? path.substring("/api/suppliers".length()) : "";
        if (rest.startsWith("/")) rest = rest.substring(1);
        String[] parts = rest.isEmpty() ? new String[0] : rest.split("/");

        if (parts.length == 0 && "GET".equalsIgnoreCase(method)) {
            Map<String, String> query = parseQueryParameters(exchange.getRequestURI().getRawQuery());
            sendResponse(exchange, 200, Purchases.suppliersJson(shopId, query.getOrDefault("search", ""),
                    "1".equals(query.get("dues")),
                    intParam(query, "limit", 200, 1, 500), intParam(query, "offset", 0, 0, 1000000)));
            return;
        }

        if (parts.length == 0 && "POST".equalsIgnoreCase(method)) {
            Map<String, Object> d = Json.asObject(Json.parse(readRequestBody(exchange)));
            String error = validateSupplier(d);
            if (error != null) {
                sendResponse(exchange, 400, "{\"success\":false,\"error\":" + Json.quote(error) + "}");
                return;
            }
            exchange.setAttribute("audit", Json.str(d, "name"));
            sendPurchaseOutcome(exchange, Purchases.createSupplier(shopId, username, Json.str(d, "name"), Json.str(d, "phone"),
                    Json.str(d, "gstin").toUpperCase(), Json.str(d, "drugLicense"), Json.str(d, "address"), Json.str(d, "notes")));
            return;
        }

        if (parts.length >= 1) {
            long id;
            try {
                id = Long.parseLong(parts[0]);
            } catch (NumberFormatException e) {
                sendResponse(exchange, 404, "{\"success\":false,\"error\":\"Supplier not found\"}");
                return;
            }

            if (parts.length == 1 && "GET".equalsIgnoreCase(method)) {
                String json = Purchases.supplierDetailJson(shopId, id);
                if (json == null) {
                    sendResponse(exchange, 404, "{\"success\":false,\"error\":\"Supplier not found\"}");
                    return;
                }
                sendResponse(exchange, 200, json);
                return;
            }

            if (parts.length == 1 && "PUT".equalsIgnoreCase(method)) {
                Map<String, Object> d = Json.asObject(Json.parse(readRequestBody(exchange)));
                String error = validateSupplier(d);
                if (error != null) {
                    sendResponse(exchange, 400, "{\"success\":false,\"error\":" + Json.quote(error) + "}");
                    return;
                }
                sendPurchaseOutcome(exchange, Purchases.updateSupplier(shopId, id, Json.str(d, "name"), Json.str(d, "phone"),
                        Json.str(d, "gstin").toUpperCase(), Json.str(d, "drugLicense"), Json.str(d, "address"), Json.str(d, "notes")));
                return;
            }

            if (parts.length == 1 && "DELETE".equalsIgnoreCase(method)) {
                if (!hasRole(exchange, "owner")) {
                    forbidden(exchange);
                    return;
                }
                sendPurchaseOutcome(exchange, Purchases.deleteSupplier(shopId, id));
                return;
            }

            if (parts.length == 2 && "payments".equals(parts[1]) && "POST".equalsIgnoreCase(method)) {
                Map<String, Object> d = Json.asObject(Json.parse(readRequestBody(exchange)));
                java.math.BigDecimal amount = Json.num(d, "amount");
                String mode = Json.str(d, "mode").toLowerCase();
                if (mode.isEmpty()) mode = "cash";
                String note = Json.str(d, "note");
                if (note.length() > 200) {
                    sendResponse(exchange, 400, "{\"success\":false,\"error\":\"The note is too long\"}");
                    return;
                }
                Purchases.Outcome paid = Purchases.paySupplier(shopId, username, id, amount, mode, note);
                if (paid.ok) exchange.setAttribute("audit", "Paid " + amount + " by " + mode);
                sendPurchaseOutcome(exchange, paid);
                return;
            }
        }

        sendResponse(exchange, 404, "{\"success\":false,\"error\":\"Not found\"}");

    } catch (IllegalArgumentException | Json.JsonException e) {
        sendResponse(exchange, 400, "{\"success\":false,\"error\":\"The request is not valid\"}");
    } catch (Exception e) {
        System.out.println("Suppliers error: " + e.getMessage());
        sendResponse(exchange, 500, "{\"success\":false,\"error\":\"Something went wrong\"}");
    }
}

private static int whole(Map<String, Object> row, String key) {
    java.math.BigDecimal value = Json.num(row, key);
    if (value == null) return 0;
    try {
        return value.intValueExact();
    } catch (ArithmeticException e) {
        return -1;
    }
}

private static void purchasesRoute(HttpExchange exchange) {
    addCorsHeaders(exchange);
    String method = exchange.getRequestMethod();
    if ("OPTIONS".equalsIgnoreCase(method)) {
        sendResponse(exchange, 204, "");
        return;
    }
    String username = requireAuth(exchange);
    if (username == null) return;
    if (!hasRole(exchange, "owner", "pharmacist")) {
        forbidden(exchange);
        return;
    }

    long shopId = shopId(exchange);

    try {
        String path = exchange.getRequestURI().getPath();
        String rest = path.length() > "/api/purchases".length() ? path.substring("/api/purchases".length()) : "";
        if (rest.startsWith("/")) rest = rest.substring(1);
        String[] parts = rest.isEmpty() ? new String[0] : rest.split("/");
        Map<String, String> query = parseQueryParameters(exchange.getRequestURI().getRawQuery());

        if (parts.length == 0 && "GET".equalsIgnoreCase(method)) {
            sendResponse(exchange, 200, Purchases.listJson(shopId, query.getOrDefault("search", ""),
                    intParam(query, "limit", 100, 1, 300), intParam(query, "offset", 0, 0, 1000000)));
            return;
        }

        if (parts.length == 1 && "medicines".equals(parts[0]) && "GET".equalsIgnoreCase(method)) {
            sendResponse(exchange, 200, Purchases.medicineSearchJson(shopId, query.getOrDefault("search", "")));
            return;
        }

        if (parts.length == 0 && "POST".equalsIgnoreCase(method)) {
            Map<String, Object> d = Json.asObject(Json.parse(readRequestBody(exchange)));

            java.math.BigDecimal supplier = Json.num(d, "supplierId");
            if (supplier == null) {
                sendResponse(exchange, 400, "{\"success\":false,\"error\":\"Choose a supplier\"}");
                return;
            }

            String billNo = Json.str(d, "billNo");
            String notes = Json.str(d, "notes");
            String clientRef = Json.str(d, "clientRef");

            if (billNo.length() > 40 || notes.length() > 300 || clientRef.length() > 64) {
                sendResponse(exchange, 400, "{\"success\":false,\"error\":\"Bill number or notes are too long\"}");
                return;
            }

            Object rawItems = d.get("items");
            if (!(rawItems instanceof java.util.List)) {
                sendResponse(exchange, 400, "{\"success\":false,\"error\":\"Add at least one medicine to the purchase\"}");
                return;
            }

            java.util.List<Purchases.ItemRequest> items = new java.util.ArrayList<>();

            for (Object raw : (java.util.List<?>) rawItems) {
                Map<String, Object> row = Json.asObject(raw);
                Purchases.ItemRequest item = new Purchases.ItemRequest();
                item.medicineId = Json.str(row, "medicineId");
                item.name = Json.str(row, "name");
                item.batch = Json.str(row, "batch");
                item.expiry = Json.str(row, "expiry");
                item.mfgDate = Json.str(row, "mfgDate");
                item.hsn = Json.str(row, "hsn");
                item.category = Json.str(row, "category");
                item.manufacturer = Json.str(row, "manufacturer");
                item.quantity = whole(row, "quantity");
                item.freeQty = whole(row, "freeQty");
                item.cost = Json.num(row, "cost");
                item.mrp = Json.num(row, "mrp");
                item.gstRate = Json.num(row, "gstRate");
                items.add(item);
            }

            Purchases.Outcome outcome = Purchases.createPurchase(shopId, username, supplier.longValue(), billNo,
                    Json.str(d, "billDate"), Json.num(d, "amountPaid"), Json.str(d, "paidMode").toLowerCase(),
                    notes, clientRef, items);

            if (!outcome.ok) {
                sendPurchaseOutcome(exchange, outcome);
                return;
            }

            exchange.setAttribute("audit", outcome.message + (billNo.isEmpty() ? "" : ", bill " + billNo) + " (" + items.size() + " items)");
            sendResponse(exchange, outcome.status, "{\"success\":true,\"purchase\":" + Purchases.detailJson(shopId, outcome.id) + "}");
            return;
        }

        if (parts.length >= 1) {
            long id;
            try {
                id = Long.parseLong(parts[0]);
            } catch (NumberFormatException e) {
                sendResponse(exchange, 404, "{\"success\":false,\"error\":\"Purchase not found\"}");
                return;
            }

            if (parts.length == 1 && "GET".equalsIgnoreCase(method)) {
                String json = Purchases.detailJson(shopId, id);
                if (json == null) {
                    sendResponse(exchange, 404, "{\"success\":false,\"error\":\"Purchase not found\"}");
                    return;
                }
                sendResponse(exchange, 200, "{\"success\":true,\"purchase\":" + json + "}");
                return;
            }

            if (parts.length == 2 && "cancel".equals(parts[1]) && "POST".equalsIgnoreCase(method)) {
                sendPurchaseOutcome(exchange, Purchases.cancelPurchase(shopId, username, id));
                return;
            }
        }

        sendResponse(exchange, 404, "{\"success\":false,\"error\":\"Not found\"}");

    } catch (IllegalArgumentException | Json.JsonException e) {
        sendResponse(exchange, 400, "{\"success\":false,\"error\":\"The request is not valid\"}");
    } catch (Exception e) {
        System.out.println("Purchases error: " + e.getMessage());
        sendResponse(exchange, 500, "{\"success\":false,\"error\":\"Something went wrong\"}");
    }
}

// ---------------------------------------------------------
// Activity log, data download, plan, platform admin
// ---------------------------------------------------------

private static void auditRoute(HttpExchange exchange) {
    addCorsHeaders(exchange);
    if ("OPTIONS".equalsIgnoreCase(exchange.getRequestMethod())) {
        sendResponse(exchange, 204, "");
        return;
    }
    if (requireAuth(exchange) == null) return;
    if (!hasRole(exchange, "owner")) {
        forbidden(exchange);
        return;
    }
    if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
        sendResponse(exchange, 405, "{\"success\":false,\"error\":\"Method Not Allowed\"}");
        return;
    }
    Map<String, String> query = parseQueryParameters(exchange.getRequestURI().getRawQuery());
    sendResponse(exchange, 200, Audit.listJson(shopId(exchange), query.get("user"), query.get("action"),
            intParam(query, "limit", 200, 1, 500), intParam(query, "offset", 0, 0, 1000000)));
}

// GET /api/backup/export  everything of this shop as a ZIP of CSV files (owner)
private static void backupRoute(HttpExchange exchange) {
    addCorsHeaders(exchange);
    if ("OPTIONS".equalsIgnoreCase(exchange.getRequestMethod())) {
        sendResponse(exchange, 204, "");
        return;
    }
    String username = requireAuth(exchange);
    if (username == null) return;
    if (!hasRole(exchange, "owner")) {
        forbidden(exchange);
        return;
    }
    if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())
            || !"/api/backup/export".equals(exchange.getRequestURI().getPath())) {
        sendResponse(exchange, 404, "{\"success\":false,\"error\":\"Not found\"}");
        return;
    }

    long shop = shopId(exchange);

    if (!SessionManager.allow("export|" + shop, 6, 60 * 60 * 1000L)) {
        sendResponse(exchange, 429, "{\"success\":false,\"error\":\"Too many downloads. Please try again in an hour.\"}");
        return;
    }

    try {
        byte[] zip = Export.shopZip(shop);
        Headers headers = exchange.getResponseHeaders();
        headers.set("Content-Type", "application/zip");
        headers.set("Content-Disposition", "attachment; filename=\"medtrack-data-" + LocalDate.now() + ".zip\"");
        exchange.sendResponseHeaders(200, zip.length);
        try (OutputStream output = exchange.getResponseBody()) {
            output.write(zip);
        }
        Audit.record(shop, username, (String) exchange.getAttribute("role"), "data.export", "", "", clientIp(exchange));
    } catch (Exception e) {
        System.out.println("Export error: " + e);
        if (exchange.getResponseCode() == -1) {
            sendResponse(exchange, 500, "{\"success\":false,\"error\":\"The download could not be prepared\"}");
        }
    }
}

// GET /api/plan  this shop's plan, days left and how to pay
private static void planRoute(HttpExchange exchange) {
    addCorsHeaders(exchange);
    if ("OPTIONS".equalsIgnoreCase(exchange.getRequestMethod())) {
        sendResponse(exchange, 204, "");
        return;
    }
    if (requireAuth(exchange) == null) return;
    sendResponse(exchange, 200, Plans.statusJson(shopId(exchange)));
}

// GET /api/public  prices and support contact for the landing page (no sign-in)
private static void publicRoute(HttpExchange exchange) {
    addCorsHeaders(exchange);
    if ("OPTIONS".equalsIgnoreCase(exchange.getRequestMethod())) {
        sendResponse(exchange, 204, "");
        return;
    }
    sendResponse(exchange, 200, Plans.publicJson());
}

// GET  /api/admin/shops       every shop on the platform
// POST /api/admin/shops/{id}  {extendDays} or {paidUntil, trialEnds, disabled}
// Only for the addresses in ADMIN_EMAILS.
private static void adminRoute(HttpExchange exchange) {
    addCorsHeaders(exchange);
    String method = exchange.getRequestMethod();
    if ("OPTIONS".equalsIgnoreCase(method)) {
        sendResponse(exchange, 204, "");
        return;
    }
    String username = requireAuth(exchange);
    if (username == null) return;
    if (!Plans.isAdmin(username)) {
        forbidden(exchange);
        return;
    }

    try {
        String path = exchange.getRequestURI().getPath();

        if ("/api/admin/shops".equals(path) && "GET".equalsIgnoreCase(method)) {
            sendResponse(exchange, 200, Plans.adminListJson());
            return;
        }

        if (path.startsWith("/api/admin/shops/") && "POST".equalsIgnoreCase(method)) {
            long target = Long.parseLong(path.substring("/api/admin/shops/".length()));
            Map<String, Object> d = Json.asObject(Json.parse(readRequestBody(exchange)));

            Integer extend = null;
            java.math.BigDecimal days = Json.num(d, "extendDays");

            if (days != null) {
                extend = days.intValueExact();
                if (extend < 1 || extend > 3660) {
                    sendResponse(exchange, 400, "{\"success\":false,\"error\":\"Days must be between 1 and 3660\"}");
                    return;
                }
            }

            String paidUntil = d.containsKey("paidUntil") ? Json.str(d, "paidUntil") : null;
            String trialEnds = d.containsKey("trialEnds") ? Json.str(d, "trialEnds") : null;

            if ((paidUntil != null && !paidUntil.isEmpty() && !ReportService.validDate(paidUntil))
                    || (trialEnds != null && !ReportService.validDate(trialEnds))) {
                sendResponse(exchange, 400, "{\"success\":false,\"error\":\"Enter dates as YYYY-MM-DD\"}");
                return;
            }

            Boolean disabled = d.get("disabled") instanceof Boolean ? (Boolean) d.get("disabled") : null;

            if (!Plans.adminUpdate(target, extend, paidUntil, trialEnds, disabled)) {
                sendResponse(exchange, 404, "{\"success\":false,\"error\":\"Shop not found\"}");
                return;
            }

            Audit.record(target, username, "platform-admin", "plan.update", "",
                    "extendDays=" + extend + " paidUntil=" + paidUntil + " trialEnds=" + trialEnds + " disabled=" + disabled,
                    clientIp(exchange));
            sendResponse(exchange, 200, "{\"success\":true}");
            return;
        }

        sendResponse(exchange, 404, "{\"success\":false,\"error\":\"Not found\"}");

    } catch (NumberFormatException | ArithmeticException | Json.JsonException e) {
        sendResponse(exchange, 400, "{\"success\":false,\"error\":\"The request is not valid\"}");
    } catch (Exception e) {
        System.out.println("Admin error: " + e.getMessage());
        sendResponse(exchange, 500, "{\"success\":false,\"error\":\"Something went wrong\"}");
    }
}

private static int intParam(Map<String, String> query, String key, int fallback, int min, int max) {

    try {
        int value = Integer.parseInt(query.getOrDefault(key, String.valueOf(fallback)).trim());
        return Math.max(min, Math.min(max, value));
    } catch (NumberFormatException e) {
        return fallback;
    }
}

private static void sendCsv(HttpExchange exchange, String filename, String body) {
    try {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        Headers headers = exchange.getResponseHeaders();
        headers.set("Content-Type", "text/csv; charset=UTF-8");
        headers.set("Content-Disposition",
                "attachment; filename=\"" + filename.replaceAll("[^A-Za-z0-9._-]", "_") + "\"");
        headers.set("Cache-Control", "no-store");
        exchange.sendResponseHeaders(200, bytes.length);
        try (OutputStream output = exchange.getResponseBody()) {
            output.write(bytes);
        }
    } catch (IOException e) {
        System.out.println("Error sending CSV: " + e.getMessage());
    }
}

private static void reportsRoute(HttpExchange exchange) {
    addCorsHeaders(exchange);
    String method = exchange.getRequestMethod();
    if ("OPTIONS".equalsIgnoreCase(method)) {
        sendResponse(exchange, 204, "");
        return;
    }
    if (requireAuth(exchange) == null) return;
    if (!hasRole(exchange, "owner", "pharmacist")) {
        forbidden(exchange);
        return;
    }
    if (!"GET".equalsIgnoreCase(method)) {
        sendResponse(exchange, 405, "{\"success\":false,\"error\":\"Method Not Allowed\"}");
        return;
    }

    long shopId = shopId(exchange);

    try {
        String path = exchange.getRequestURI().getPath();
        String name = path.length() > "/api/reports".length() ? path.substring("/api/reports".length()) : "";
        name = name.replace("/", "").trim();

        Map<String, String> query = parseQueryParameters(exchange.getRequestURI().getRawQuery());

        String to = query.getOrDefault("to", LocalDate.now().toString());
        String from = query.get("from");

        if (!ReportService.validDate(to)) {
            sendResponse(exchange, 400, "{\"success\":false,\"error\":\"The end date is not valid (use YYYY-MM-DD)\"}");
            return;
        }
        if (from == null) from = LocalDate.parse(to).minusDays(29).toString();
        if (!ReportService.validDate(from)) {
            sendResponse(exchange, 400, "{\"success\":false,\"error\":\"The start date is not valid (use YYYY-MM-DD)\"}");
            return;
        }
        if (from.compareTo(to) > 0) {
            sendResponse(exchange, 400, "{\"success\":false,\"error\":\"The start date is after the end date\"}");
            return;
        }
        if (java.time.temporal.ChronoUnit.DAYS.between(LocalDate.parse(from), LocalDate.parse(to)) > 1830) {
            sendResponse(exchange, 400, "{\"success\":false,\"error\":\"Choose a period of 5 years or less\"}");
            return;
        }

        int days = intParam(query, "days", 90, 1, 730);

        if ("summary".equals(name)) {
            sendResponse(exchange, 200, ReportService.summaryJson(shopId, from, to));
            return;
        }

        if ("stock".equals(name)) {
            sendResponse(exchange, 200, ReportService.stockJson(shopId, days, intParam(query, "low", 10, 0, 100000)));
            return;
        }

        if ("export".equals(name)) {
            ReportService.Csv csv = ReportService.csv(shopId, query.getOrDefault("type", ""), from, to, days);
            if (csv == null) {
                sendResponse(exchange, 400, "{\"success\":false,\"error\":\"Unknown report type\"}");
                return;
            }
            sendCsv(exchange, csv.filename, csv.body);
            return;
        }

        sendResponse(exchange, 404, "{\"success\":false,\"error\":\"Report not found\"}");
    } catch (Exception e) {
        e.printStackTrace();
        sendResponse(exchange, 500, "{\"success\":false,\"error\":\"Report request failed\"}");
    }
}
}
