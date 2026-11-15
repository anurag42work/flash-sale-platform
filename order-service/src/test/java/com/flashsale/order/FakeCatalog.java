package com.flashsale.order;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Stands in for catalog-service's internal inventory API in order tests (JDK HTTP server, no extra library).
 * Keeps stock and prices in memory; can be made slow to test the read timeout.
 */
final class FakeCatalog {

    private static final Pattern PATH = Pattern.compile("/internal/inventory/(\\d+)/(reserve|release)");
    private static final Pattern QUANTITY = Pattern.compile("\"quantity\"\\s*:\\s*(\\d+)");

    private final HttpServer server;
    private final Map<Long, Integer> stock = new ConcurrentHashMap<>();
    private final Map<Long, BigDecimal> prices = new ConcurrentHashMap<>();
    private volatile long delayMillis;

    FakeCatalog() {
        try {
            server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        server.createContext("/internal/inventory/", this::handle);
        // A thread pool, so a deliberately slow request does not block the requests of the next test.
        server.setExecutor(Executors.newCachedThreadPool());
        server.start();
    }

    String baseUrl() {
        return "http://localhost:" + server.getAddress().getPort();
    }

    void product(long id, String price, int available) {
        prices.put(id, new BigDecimal(price));
        stock.put(id, available);
    }

    int stock(long id) {
        return stock.get(id);
    }

    void delay(long millis) {
        delayMillis = millis;
    }

    void reset() {
        stock.clear();
        prices.clear();
        delayMillis = 0;
    }

    private void handle(HttpExchange exchange) throws IOException {
        if (delayMillis > 0) {
            sleep(delayMillis);
        }
        Matcher path = PATH.matcher(exchange.getRequestURI().getPath());
        Matcher quantity = QUANTITY.matcher(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
        if (!path.matches() || !quantity.find()) {
            respond(exchange, 400, "{}");
            return;
        }
        long id = Long.parseLong(path.group(1));
        int qty = Integer.parseInt(quantity.group(1));
        if (!stock.containsKey(id)) {
            respond(exchange, 404, "{\"status\":404}");
            return;
        }
        if (path.group(2).equals("release")) {
            int now = stock.merge(id, qty, Integer::sum);
            respond(exchange, 200, "{\"productId\":" + id + ",\"available\":" + now + "}");
            return;
        }
        int available = stock.get(id);
        if (available < qty) {
            respond(exchange, 409, "{\"status\":409,\"code\":\"OUT_OF_STOCK\"}");
            return;
        }
        stock.put(id, available - qty);
        respond(exchange, 200, "{\"productId\":" + id + ",\"quantity\":" + qty + ",\"unitPrice\":" + prices.get(id)
                + ",\"remaining\":" + (available - qty) + "}");
    }

    private static void respond(HttpExchange exchange, int status, String json) throws IOException {
        byte[] body = json.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, body.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(body);
        }
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
