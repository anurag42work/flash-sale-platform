package com.flashsale.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

/** Real gateway (random port) in front of a fake downstream service. No Docker needed. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class GatewaySecurityTest {

    private static final String ISSUER = "flash-sale-auth";
    private static final RSAKey SIGNING_KEY = newKey("test-key");
    private static final FakeDownstream DOWNSTREAM = new FakeDownstream(SIGNING_KEY);

    @DynamicPropertySource
    static void routeToFakeDownstream(DynamicPropertyRegistry registry) {
        registry.add("AUTH_URL", DOWNSTREAM::baseUrl);
        registry.add("CATALOG_URL", DOWNSTREAM::baseUrl);
        registry.add("ORDER_URL", DOWNSTREAM::baseUrl);
        registry.add("AUTH_JWKS_URI", () -> DOWNSTREAM.baseUrl() + "/.well-known/jwks.json");
        registry.add("JWT_ISSUER", () -> ISSUER);
    }

    @Autowired
    WebTestClient client;

    @LocalServerPort
    int port;

    @BeforeEach
    void resetDownstream() {
        DOWNSTREAM.reset();
    }

    // ---- 401 cases: the request must never reach the service ----

    @Test
    void ordersWithoutTokenReturn401Problem() {
        client.get().uri("/api/orders/1").exchange()
                .expectStatus().isUnauthorized()
                .expectHeader().valueEquals(HttpHeaders.WWW_AUTHENTICATE, "Bearer")
                .expectHeader().contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .expectBody()
                .jsonPath("$.status").isEqualTo(401)
                .jsonPath("$.instance").isEqualTo("/api/orders/1");
        assertThat(DOWNSTREAM.lastRequest()).isNull();
    }

    @Test
    void expiredTokenReturns401() {
        // Expired 5 minutes ago: beyond the 60 s clock-skew allowance.
        String token = token(SIGNING_KEY, ISSUER, Instant.now().minus(Duration.ofMinutes(5)));

        expectUnauthorized(token);
    }

    @Test
    void tokenSignedByAnotherKeyReturns401() {
        String token = token(newKey("test-key"), ISSUER, Instant.now().plus(Duration.ofMinutes(15)));

        expectUnauthorized(token);
    }

    @Test
    void tokenFromAnotherIssuerReturns401() {
        String token = token(SIGNING_KEY, "someone-else", Instant.now().plus(Duration.ofMinutes(15)));

        expectUnauthorized(token);
    }

    @Test
    void garbageTokenReturns401() {
        expectUnauthorized("not-a-jwt");
    }

    @Test
    void writingProductsNeedsAToken() {
        client.post().uri("/api/products").contentType(MediaType.APPLICATION_JSON).bodyValue("{}").exchange()
                .expectStatus().isUnauthorized();
        assertThat(DOWNSTREAM.lastRequest()).isNull();
    }

    @Test
    void unknownPathsAreClosed() {
        client.get().uri("/api/admin/secrets").exchange()
                .expectStatus().isUnauthorized();
    }

    // ---- public routes ----

    @Test
    void authRoutesArePublic() {
        client.post().uri("/api/auth/login").contentType(MediaType.APPLICATION_JSON).bodyValue("{}").exchange()
                .expectStatus().isOk();
        assertThat(DOWNSTREAM.lastRequest()).isNotNull();
    }

    @Test
    void readingProductsIsPublic() {
        client.get().uri("/api/products/42").exchange()
                .expectStatus().isOk();
        assertThat(DOWNSTREAM.lastRequest()).isNotNull();
    }

    /**
     * Path traversal: a public route must not be usable to reach a path the gateway doesn't route, such as
     * catalog's /internal/inventory API (Tomcat would normalise "/api/products/../../internal/..." downstream).
     */
    @ParameterizedTest
    @ValueSource(strings = {
            "/api/products/../../internal/inventory/1/reserve",
            "/api/products/..%2F..%2Finternal/inventory/1/reserve",
            "/api/products/%2e%2e/%2e%2e/internal/inventory/1/reserve",
            "/api/products/..;/..;/internal/inventory/1/reserve",
            "/api/products/%252e%252e/%252e%252e/internal/inventory/1/reserve"})
    void traversalFromARoutedPathNeverReachesInternalPaths(String path) {
        // A valid USER token: authentication passes, so only path handling stands between us and /internal.
        int status = client.post().uri(URI.create("http://localhost:" + port + path))
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token(UUID.randomUUID(), List.of("USER")))
                .contentType(MediaType.APPLICATION_JSON).bodyValue("{}")
                .exchange().returnResult(String.class).getStatus().value();

        String reached = DOWNSTREAM.lastPath();
        System.out.printf("traversal %s -> status %d, downstream path %s%n", path, status, reached);
        if (reached != null) {
            // Whatever got through must stay a plain path under /api/products that Tomcat can't resolve elsewhere.
            assertThat(reached).as("downstream path for " + path).startsWith("/api/products/");
            assertThat(reached.toLowerCase()).as("downstream path for " + path)
                    .doesNotContain("..").doesNotContain("%2e").doesNotContain("%2f").doesNotContain("%5c")
                    .doesNotContain(";");
        }
    }

    // ---- identity headers ----

    @Test
    void validTokenIsForwardedWithIdentityHeaders() {
        UUID userId = UUID.randomUUID();

        client.get().uri("/api/orders/1")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token(userId, List.of("USER")))
                .exchange()
                .expectStatus().isOk();

        assertThat(DOWNSTREAM.lastRequest().getFirst("X-User-Id")).isEqualTo(userId.toString());
        assertThat(DOWNSTREAM.lastRequest().getFirst("X-User-Roles")).isEqualTo("USER");
    }

    @Test
    void spoofedIdentityHeadersAreReplacedByTheTokenValues() {
        UUID userId = UUID.randomUUID();

        client.get().uri("/api/orders/1")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token(userId, List.of("USER")))
                .header("X-User-Id", "attacker")
                .header("X-User-Roles", "ADMIN")
                .exchange()
                .expectStatus().isOk();

        assertThat(DOWNSTREAM.lastRequest().get("X-User-Id")).containsExactly(userId.toString());
        assertThat(DOWNSTREAM.lastRequest().get("X-User-Roles")).containsExactly("USER");
    }

    @Test
    void spoofedIdentityHeadersAreRemovedOnPublicRoutes() {
        client.get().uri("/api/products/42")
                .header("X-User-Id", "attacker")
                .header("x-user-roles", "ADMIN")
                .exchange()
                .expectStatus().isOk();

        assertThat(DOWNSTREAM.lastRequest().containsKey("X-User-Id")).isFalse();
        assertThat(DOWNSTREAM.lastRequest().containsKey("X-User-Roles")).isFalse();
    }

    // ---- correlation id ----

    @Test
    void correlationIdIsGeneratedWhenMissing() {
        String returned = client.get().uri("/api/products/42").exchange()
                .expectStatus().isOk()
                .returnResult(String.class).getResponseHeaders().getFirst("X-Correlation-Id");

        assertThat(UUID.fromString(returned)).isNotNull();
        assertThat(DOWNSTREAM.lastRequest().getFirst("X-Correlation-Id")).isEqualTo(returned);
    }

    @Test
    void validCorrelationIdIsKept() {
        client.get().uri("/api/products/42").header("X-Correlation-Id", "client-abc-123").exchange()
                .expectHeader().valueEquals("X-Correlation-Id", "client-abc-123");

        assertThat(DOWNSTREAM.lastRequest().getFirst("X-Correlation-Id")).isEqualTo("client-abc-123");
    }

    @Test
    void unsafeCorrelationIdIsReplaced() {
        String returned = client.get().uri("/api/products/42")
                .header("X-Correlation-Id", "bad id; <script>")
                .exchange()
                .returnResult(String.class).getResponseHeaders().getFirst("X-Correlation-Id");

        assertThat(returned).isNotEqualTo("bad id; <script>");
        assertThat(UUID.fromString(returned)).isNotNull();
    }

    @Test
    void correlationIdIsAlsoReturnedOn401() {
        client.get().uri("/api/orders/1").header("X-Correlation-Id", "trace-401").exchange()
                .expectStatus().isUnauthorized()
                .expectHeader().valueEquals("X-Correlation-Id", "trace-401");
    }

    // ---- helpers ----

    private void expectUnauthorized(String token) {
        client.get().uri("/api/orders/1").header(HttpHeaders.AUTHORIZATION, "Bearer " + token).exchange()
                .expectStatus().isUnauthorized()
                .expectHeader().contentType(MediaType.APPLICATION_PROBLEM_JSON);
        assertThat(DOWNSTREAM.lastRequest()).isNull();
    }

    private static String token(UUID userId, List<String> roles) {
        return sign(SIGNING_KEY, ISSUER, userId, roles, Instant.now().plus(Duration.ofMinutes(15)));
    }

    private static String token(RSAKey key, String issuer, Instant expiresAt) {
        return sign(key, issuer, UUID.randomUUID(), List.of("USER"), expiresAt);
    }

    /** Builds a token the same way auth-service does: RS256, kid header, sub, roles, iss, iat, exp. */
    private static String sign(RSAKey key, String issuer, UUID userId, List<String> roles, Instant expiresAt) {
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .issuer(issuer)
                .subject(userId.toString())
                .claim("roles", roles)
                .issueTime(Date.from(expiresAt.minus(Duration.ofMinutes(15))))
                .expirationTime(Date.from(expiresAt))
                .build();
        try {
            SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(key.getKeyID()).build(), claims);
            jwt.sign(new RSASSASigner(key));
            return jwt.serialize();
        } catch (JOSEException e) {
            throw new IllegalStateException(e);
        }
    }

    private static RSAKey newKey(String keyId) {
        try {
            return new RSAKeyGenerator(2048).keyID(keyId).generate();
        } catch (JOSEException e) {
            throw new IllegalStateException(e);
        }
    }
}
