package com.flashsale.order.reconciliation;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Stands in for payment-service's POST /internal/payments/{orderId}/expire (JDK HTTP server, no extra library).
 * Same contract: return the order's payment, or record FAILED / EXPIRED if there is none.
 */
final class FakePayments {

    private static final Pattern PATH = Pattern.compile("/internal/payments/([0-9a-f-]{36})/expire");

    private final HttpServer server;
    private final Map<UUID, String[]> payments = new ConcurrentHashMap<>(); // orderId -> {status, reason}
    private final AtomicInteger calls = new AtomicInteger();
    private volatile boolean down;
    private volatile long delayMillis;

    FakePayments() {
        try {
            server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        server.createContext("/internal/payments/", this::handle);
        server.setExecutor(Executors.newCachedThreadPool());
        server.start();
    }

    String baseUrl() {
        return "http://localhost:" + server.getAddress().getPort();
    }

    void payment(UUID orderId, String status, String reason) {
        payments.put(orderId, new String[] {status, reason});
    }

    String[] payment(UUID orderId) {
        return payments.get(orderId);
    }

    int calls() {
        return calls.get();
    }

    void down(boolean down) {
        this.down = down;
    }

    void delay(long millis) {
        this.delayMillis = millis;
    }

    void reset() {
        payments.clear();
        calls.set(0);
        down = false;
        delayMillis = 0;
    }

    private void handle(HttpExchange exchange) throws IOException {
        exchange.getRequestBody().readAllBytes();
        calls.incrementAndGet();
        if (delayMillis > 0) {
            try {
                Thread.sleep(delayMillis);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        if (down) {
            respond(exchange, 503, "{\"status\":503}");
            return;
        }
        Matcher path = PATH.matcher(exchange.getRequestURI().getPath());
        if (!"POST".equals(exchange.getRequestMethod()) || !path.matches()) {
            respond(exchange, 404, "{\"status\":404}");
            return;
        }
        UUID orderId = UUID.fromString(path.group(1));
        String[] p = payments.computeIfAbsent(orderId, id -> new String[] {"FAILED", "EXPIRED"});
        String reason = p[1] == null ? "null" : "\"" + p[1] + "\"";
        respond(exchange, 200, "{\"orderId\":\"" + orderId + "\",\"status\":\"" + p[0] + "\",\"reason\":" + reason + "}");
    }

    private static void respond(HttpExchange exchange, int status, String json) throws IOException {
        byte[] body = json.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, body.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(body);
        }
    }
}
