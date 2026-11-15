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
import java.util.concurrent.atomic.AtomicReference;

/**
 * Stands in for auth/catalog/order in gateway tests, using the JDK's built-in HTTP server (no extra library).
 * Serves the JWKS for the test signing key and records the headers of the last routed request.
 */
final class FakeDownstream {

    private final HttpServer server;
    private final AtomicReference<Headers> lastRequest = new AtomicReference<>();

    FakeDownstream(RSAKey signingKey) {
        try {
            server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        String jwks = new JWKSet(signingKey.toPublicJWK()).toString();
        server.createContext("/.well-known/jwks.json", exchange -> respond(exchange, jwks));
        server.createContext("/", exchange -> {
            lastRequest.set(exchange.getRequestHeaders());
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

    void reset() {
        lastRequest.set(null);
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
