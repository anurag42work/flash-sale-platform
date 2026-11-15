package com.flashsale.auth.token;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.flashsale.auth.TestKeys;
import com.flashsale.auth.user.Role;
import com.nimbusds.jose.jwk.RSAKey;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPublicKey;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

class AccessTokenIssuerTest {

    private final Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
    private final TokenProperties properties = new TokenProperties(
            "test-issuer", Duration.ofMinutes(15), Duration.ofDays(7), TestKeys.publicKey(), TestKeys.privateKey());
    private final RSAKey signingKey = signingKey();
    private final AccessTokenIssuer issuer = new AccessTokenIssuer(
            new JwtConfig().jwtEncoder(signingKey), properties, Clock.fixed(now, ZoneOffset.UTC));

    @Test
    void issuesRs256TokenWithUserIdRolesAndFifteenMinuteExpiry() {
        UUID userId = UUID.randomUUID();

        Jwt jwt = NimbusJwtDecoder.withPublicKey(TestKeys.publicKey()).build()
                .decode(issuer.issue(userId, Role.ADMIN));

        assertThat(jwt.getHeaders()).containsEntry("alg", "RS256").containsEntry("kid", signingKey.getKeyID());
        assertThat(jwt.getSubject()).isEqualTo(userId.toString());
        assertThat(jwt.getClaimAsStringList("roles")).containsExactly("ADMIN");
        assertThat(jwt.getClaimAsString("iss")).isEqualTo("test-issuer");
        assertThat(jwt.getIssuedAt()).isEqualTo(now);
        assertThat(jwt.getExpiresAt()).isEqualTo(now.plus(Duration.ofMinutes(15)));
        assertThat(issuer.ttlSeconds()).isEqualTo(900);
    }

    @Test
    void tokenIsRejectedByAnotherPublicKey() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        RSAPublicKey otherKey = (RSAPublicKey) generator.generateKeyPair().getPublic();
        String token = issuer.issue(UUID.randomUUID(), Role.USER);

        assertThatThrownBy(() -> NimbusJwtDecoder.withPublicKey(otherKey).build().decode(token))
                .isInstanceOf(JwtException.class);
    }

    private RSAKey signingKey() {
        try {
            return new JwtConfig().signingKey(properties);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
