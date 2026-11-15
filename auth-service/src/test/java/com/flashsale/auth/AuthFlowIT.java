package com.flashsale.auth;

import static org.assertj.core.api.Assertions.assertThat;

import com.flashsale.auth.account.AccountDtos.TokenResponse;
import com.flashsale.auth.account.AccountDtos.UserResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** The whole auth flow over HTTP against a real Postgres (Testcontainers). */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AuthFlowIT {

    private static final String PASSWORD = "correct-horse-battery";

    @DynamicPropertySource
    static void jwtKeys(DynamicPropertyRegistry registry) {
        TestKeys.register(registry);
    }

    @Autowired
    TestRestTemplate rest;

    @Autowired
    JdbcTemplate jdbc;

    @LocalServerPort
    int port;

    @Test
    void accessTokenFromLoginVerifiesAgainstThePublishedJwks() {
        String email = newEmail();
        UserResponse user = register(email).getBody();

        TokenResponse tokens = login(email, PASSWORD).getBody();

        // Exactly what the gateway will do in step 1.3: fetch the JWKS and verify the signature.
        Jwt jwt = NimbusJwtDecoder.withJwkSetUri("http://localhost:" + port + "/.well-known/jwks.json").build()
                .decode(tokens.accessToken());
        assertThat(jwt.getSubject()).isEqualTo(user.id().toString());
        assertThat(jwt.getClaimAsStringList("roles")).containsExactly("USER");
        assertThat(tokens.expiresIn()).isEqualTo(900);
    }

    @Test
    void jwksContainsOnlyThePublicKey() {
        @SuppressWarnings("unchecked")
        Map<String, Object> jwks = rest.getForObject("/.well-known/jwks.json", Map.class);

        @SuppressWarnings("unchecked")
        Map<String, Object> key = ((List<Map<String, Object>>) jwks.get("keys")).get(0);
        assertThat(key).containsEntry("kty", "RSA").containsEntry("alg", "RS256").containsEntry("use", "sig")
                .containsKeys("kid", "n", "e")
                .doesNotContainKeys("d", "p", "q", "dp", "dq", "qi");
    }

    @Test
    void passwordIsStoredAsBcryptAndEmailIsNormalised() {
        String email = newEmail();
        register(email.toUpperCase());

        String hash = jdbc.queryForObject("SELECT password_hash FROM users WHERE email = ?", String.class, email);
        assertThat(hash).startsWith("$2a$").doesNotContain(PASSWORD);
        assertThat(login(email.toUpperCase(), PASSWORD).getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void duplicateEmailReturns409() {
        String email = newEmail();
        register(email);

        ResponseEntity<String> second = rest.postForEntity("/api/auth/register",
                Map.of("email", email.toUpperCase(), "password", PASSWORD), String.class);

        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void unknownEmailAndWrongPasswordGiveTheSame401() {
        String email = newEmail();
        register(email);

        ResponseEntity<String> wrongPassword = rest.postForEntity("/api/auth/login",
                Map.of("email", email, "password", "wrong-password"), String.class);
        ResponseEntity<String> unknownEmail = rest.postForEntity("/api/auth/login",
                Map.of("email", newEmail(), "password", PASSWORD), String.class);

        assertThat(wrongPassword.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(unknownEmail.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(wrongPassword.getBody()).isEqualTo(unknownEmail.getBody());
    }

    @Test
    void refreshRotatesTheToken() {
        String email = newEmail();
        register(email);
        TokenResponse first = login(email, PASSWORD).getBody();

        ResponseEntity<TokenResponse> second = refresh(first.refreshToken());

        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(second.getBody().refreshToken()).isNotEqualTo(first.refreshToken());
        assertThat(second.getBody().accessToken()).isNotBlank();
        // The raw token is never stored, only its hash.
        Integer rawStored = jdbc.queryForObject(
                "SELECT count(*) FROM refresh_tokens WHERE token_hash = ?", Integer.class, first.refreshToken());
        assertThat(rawStored).isZero();
    }

    @Test
    void reusingARotatedTokenRevokesTheWholeFamily() {
        String email = newEmail();
        register(email);
        String stolen = login(email, PASSWORD).getBody().refreshToken();
        String current = refresh(stolen).getBody().refreshToken();

        // The attacker replays the old (already rotated) token.
        assertThat(refresh(stolen).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);

        // The family is revoked, so the real user's newest token stops working too.
        assertThat(refresh(current).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void logoutRevokesTheRefreshToken() {
        String email = newEmail();
        register(email);
        String refreshToken = login(email, PASSWORD).getBody().refreshToken();

        ResponseEntity<Void> logout = rest.postForEntity("/api/auth/logout",
                Map.of("refreshToken", refreshToken), Void.class);

        assertThat(logout.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(refresh(refreshToken).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void expiredRefreshTokenIsRejected() {
        String email = newEmail();
        register(email);
        String refreshToken = login(email, PASSWORD).getBody().refreshToken();
        jdbc.update("UPDATE refresh_tokens SET created_at = now() - interval '8 days', "
                + "expires_at = now() - interval '1 day' "
                + "WHERE user_id = (SELECT id FROM users WHERE email = ?)", email);

        assertThat(refresh(refreshToken).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void twoParallelRefreshesWithTheSameTokenSucceedOnlyOnce() throws Exception {
        String email = newEmail();
        register(email);
        String refreshToken = login(email, PASSWORD).getBody().refreshToken();

        CountDownLatch start = new CountDownLatch(1);
        Callable<HttpStatusCode> call = () -> {
            start.await();
            return refresh(refreshToken).getStatusCode();
        };
        List<HttpStatusCode> statuses = new ArrayList<>();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<HttpStatusCode> a = pool.submit(call);
            Future<HttpStatusCode> b = pool.submit(call);
            start.countDown();
            statuses.add(a.get());
            statuses.add(b.get());
        } finally {
            pool.shutdownNow();
        }

        // The row lock (SELECT ... FOR UPDATE) makes the second request see the token as already used.
        assertThat(statuses).containsExactlyInAnyOrder(HttpStatus.OK, HttpStatus.UNAUTHORIZED);
    }

    private ResponseEntity<UserResponse> register(String email) {
        ResponseEntity<UserResponse> response = rest.postForEntity("/api/auth/register",
                Map.of("email", email, "password", PASSWORD), UserResponse.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return response;
    }

    private ResponseEntity<TokenResponse> login(String email, String password) {
        return rest.postForEntity("/api/auth/login", Map.of("email", email, "password", password),
                TokenResponse.class);
    }

    private ResponseEntity<TokenResponse> refresh(String refreshToken) {
        return rest.postForEntity("/api/auth/refresh", Map.of("refreshToken", refreshToken), TokenResponse.class);
    }

    private static String newEmail() {
        return "user-" + UUID.randomUUID() + "@example.com";
    }
}
