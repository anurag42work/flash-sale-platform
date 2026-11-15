package com.flashsale.auth.token;

import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Duration;
import java.util.Objects;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * auth.token.* settings. Keys are PEM: a location ("file:keys/jwt-private.pem") or the PEM text itself.
 * Spring Security registers the String-to-RSA-key converters used for binding.
 */
@ConfigurationProperties("auth.token")
public record TokenProperties(
        String issuer,
        Duration accessTokenTtl,
        Duration refreshTokenTtl,
        RSAPublicKey publicKey,
        RSAPrivateKey privateKey) {

    public TokenProperties {
        Objects.requireNonNull(issuer, "auth.token.issuer is required");
        Objects.requireNonNull(accessTokenTtl, "auth.token.access-token-ttl is required");
        Objects.requireNonNull(refreshTokenTtl, "auth.token.refresh-token-ttl is required");
        Objects.requireNonNull(publicKey, "auth.token.public-key is required");
        Objects.requireNonNull(privateKey, "auth.token.private-key is required");
    }
}
