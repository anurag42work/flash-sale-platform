package com.flashsale.auth.token;

import com.flashsale.auth.user.Role;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Component;

/** Signs short-lived RS256 access tokens. Nothing is stored: the gateway checks the signature with the JWKS. */
@Component
public class AccessTokenIssuer {

    public static final String ROLES_CLAIM = "roles";

    private final JwtEncoder encoder;
    private final TokenProperties properties;
    private final Clock clock;

    public AccessTokenIssuer(JwtEncoder encoder, TokenProperties properties, Clock clock) {
        this.encoder = encoder;
        this.properties = properties;
        this.clock = clock;
    }

    public String issue(UUID userId, Role role) {
        Instant now = clock.instant();
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(properties.issuer())
                .subject(userId.toString())
                .issuedAt(now)
                .expiresAt(now.plus(properties.accessTokenTtl()))
                .claim(ROLES_CLAIM, List.of(role.name()))
                .build();
        JwsHeader header = JwsHeader.with(SignatureAlgorithm.RS256).build();
        return encoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
    }

    public long ttlSeconds() {
        return properties.accessTokenTtl().toSeconds();
    }
}
