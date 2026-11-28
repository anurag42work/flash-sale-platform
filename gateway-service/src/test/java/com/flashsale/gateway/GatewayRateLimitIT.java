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
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.EntityExchangeResult;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Step 2.7: Redis token-bucket rate limiting in the gateway, with a real Redis (Testcontainers) and a fake
 * downstream. Limits are the ones in application.yml. Buckets are emptied (FLUSHALL) before every test.
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class GatewayRateLimitIT {

    @Container
    @ServiceConnection(name = "redis")
    static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7.4.12-alpine").withExposedPorts(6379);

    private static final String ISSUER = "flash-sale-auth";
    private static final RSAKey SIGNING_KEY = newKey();
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

    @BeforeEach
    void emptyBuckets() throws Exception {
        REDIS.execInContainer("redis-cli", "FLUSHALL");
        DOWNSTREAM.reset();
    }

    @Test
    void sixthLoginFromOneAddressGets429ProblemWithRetryAfterAndNeverReachesAuth() {
        List<Integer> statuses = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            statuses.add(login().getStatus().value());
        }
        EntityExchangeResult<String> sixth = login();
        System.out.printf("%n=== RATE LIMIT (login): attempts 1-5 -> %s, 6th -> %d, Retry-After %s, reached auth %d ===%n%n",
                statuses, sixth.getStatus().value(), sixth.getResponseHeaders().getFirst(HttpHeaders.RETRY_AFTER),
                DOWNSTREAM.requests());

        assertThat(statuses).containsOnly(200);
        assertThat(sixth.getStatus()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(sixth.getResponseHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_PROBLEM_JSON);
        assertThat(sixth.getResponseHeaders().getFirst(HttpHeaders.RETRY_AFTER))
                .as("12 tokens per login / 1 token per second").isEqualTo("12");
        assertThat(sixth.getResponseBody()).contains("\"status\":429").contains("\"instance\":\"/api/auth/login\"");
        assertThat(DOWNSTREAM.requests()).as("the 6th never reached auth-service").isEqualTo(5);
    }

    @Test
    void loginLimitDoesNotUseUpTheOtherAuthEndpoints() {
        for (int i = 0; i < 6; i++) {
            login();
        }

        client.post().uri("/api/auth/register").contentType(MediaType.APPLICATION_JSON).bodyValue("{}")
                .exchange().expectStatus().isOk();
    }

    @Test
    void orderLimitIsPerUser() {
        String alice = token(UUID.randomUUID());
        String bob = token(UUID.randomUUID());
        List<Integer> aliceStatuses = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            aliceStatuses.add(order(alice).getStatus().value());
        }
        EntityExchangeResult<String> aliceAgain = order(alice);
        int bobStatus = order(bob).getStatus().value();
        System.out.printf("%n=== RATE LIMIT (orders): alice 6 fast POSTs -> %s, bob -> %d ===%n%n", aliceStatuses, bobStatus);

        assertThat(aliceStatuses.subList(0, 5)).as("burst capacity 5").containsOnly(200);
        assertThat(aliceStatuses.get(5)).isEqualTo(429);
        assertThat(aliceAgain.getResponseHeaders().getFirst(HttpHeaders.RETRY_AFTER))
                .as("1 token per order / 2 tokens per second, rounded up").isEqualTo("1");
        assertThat(bobStatus).as("bob has his own bucket").isEqualTo(200);
    }

    @Test
    void bucketRefillsOverTime() throws Exception {
        String alice = token(UUID.randomUUID());
        for (int i = 0; i < 6; i++) {
            order(alice);
        }
        assertThat(order(alice).getStatus()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);

        Thread.sleep(1100); // 2 tokens per second

        assertThat(order(alice).getStatus()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void normalBrowsingIsNotLimited() {
        for (int i = 0; i < 30; i++) {
            client.get().uri("/api/products?page=0").exchange().expectStatus().isOk();
        }
    }

    private EntityExchangeResult<String> login() {
        return client.post().uri("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"email\":\"a@example.com\",\"password\":\"wrong-password\"}")
                .exchange().expectBody(String.class).returnResult();
    }

    private EntityExchangeResult<String> order(String token) {
        return client.post().uri("/api/orders").contentType(MediaType.APPLICATION_JSON)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .bodyValue("{\"items\":[{\"productId\":1,\"quantity\":1}]}")
                .exchange().expectBody(String.class).returnResult();
    }

    private static String token(UUID userId) {
        Instant now = Instant.now();
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .issuer(ISSUER)
                .subject(userId.toString())
                .claim("roles", List.of("USER"))
                .issueTime(Date.from(now))
                .expirationTime(Date.from(now.plus(Duration.ofMinutes(15))))
                .build();
        try {
            SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(SIGNING_KEY.getKeyID()).build(),
                    claims);
            jwt.sign(new RSASSASigner(SIGNING_KEY));
            return jwt.serialize();
        } catch (JOSEException e) {
            throw new IllegalStateException(e);
        }
    }

    private static RSAKey newKey() {
        try {
            return new RSAKeyGenerator(2048).keyID("rate-limit-test-key").generate();
        } catch (JOSEException e) {
            throw new IllegalStateException(e);
        }
    }
}
