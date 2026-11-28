package com.flashsale.gateway;

import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Stands in for auth/catalog/order in gateway tests, using the JDK's built-in HTTP server (no extra library).
 * Serves the JWKS for the test signing key and records the headers of the last routed request.
 */
final class FakeDownstream {

    private final HttpServer server;
    private final AtomicReference<Headers> lastRequest = new AtomicReference<>();
    private final AtomicReference<String> lastPath = new AtomicReference<>();
    private final AtomicInteger requests = new AtomicInteger();

    FakeDownstream(RSAKey signingKey) {
        try {
            server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        String jwks = new JWKSet(signingKey.toPublicJWK()).toString();
        server.createContext("/.well-known/jwks.json", exchange -> respond(exchange, jwks));
        server.createContext("/", exchange -> {
            exchange.getRequestBody().readAllBytes();
            requests.incrementAndGet();
            lastRequest.set(exchange.getRequestHeaders());
            lastPath.set(exchange.getRequestURI().getRawPath());
            respond(exchange, "{\"downstream\":\"ok\"}");
        });
        server.start();
    }

    String baseUrl() {
        return "http://localhost:" + server.getAddress().getPort();
    }

    /** Headers the downstream service received, or null if the request never reached it. */
    Headers lastRequest() {
        return lastRequest.get();
    }

    /** Raw (still encoded) path the downstream service received, or null. */
    String lastPath() {
        return lastPath.get();
    }

    /** How many routed requests reached the downstream service since the last reset. */
    int requests() {
        return requests.get();
    }

    void reset() {
        lastRequest.set(null);
        lastPath.set(null);
        requests.set(0);
    }

    private static void respond(HttpExchange exchange, String json) throws IOException {
        byte[] body = json.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, body.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(body);
        }
    }
}
