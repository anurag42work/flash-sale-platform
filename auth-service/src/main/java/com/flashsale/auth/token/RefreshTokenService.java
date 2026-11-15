package com.flashsale.auth.token;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Refresh token rotation: every refresh revokes the presented token and issues a new one in the same family.
 * A token that is presented again after it was revoked has been copied (stolen or replayed), so the whole
 * family is revoked and both the attacker and the real user must log in again.
 */
@Service
public class RefreshTokenService {

    private static final Logger log = LoggerFactory.getLogger(RefreshTokenService.class);

    private final RefreshTokenRepository tokens;
    private final TokenProperties properties;
    private final Clock clock;

    public RefreshTokenService(RefreshTokenRepository tokens, TokenProperties properties, Clock clock) {
        this.tokens = tokens;
        this.properties = properties;
        this.clock = clock;
    }

    public record Rotation(UUID userId, String refreshToken) {
    }

    /** New login: starts a new token family. Returns the raw token, which is sent once and never stored. */
    @Transactional
    public String issue(UUID userId) {
        return create(userId, UUID.randomUUID(), clock.instant()).raw();
    }

    // noRollbackFor: on reuse, the family revocation must be committed even though the caller gets a 401.
    @Transactional(noRollbackFor = InvalidRefreshTokenException.class)
    public Rotation rotate(String rawToken) {
        RefreshToken current = tokens.findByTokenHashForUpdate(RefreshTokens.hash(rawToken))
                .orElseThrow(InvalidRefreshTokenException::new);
        Instant now = clock.instant();

        if (current.isRevoked()) {
            int revoked = tokens.revokeFamily(current.getFamilyId(), now);
            log.warn("Refresh token reuse detected: family {} revoked ({} active tokens)",
                    current.getFamilyId(), revoked);
            throw new InvalidRefreshTokenException();
        }
        if (current.isExpired(now)) {
            throw new InvalidRefreshTokenException();
        }

        Created next = create(current.getUserId(), current.getFamilyId(), now);
        current.revoke(now, next.entity().getId());
        return new Rotation(current.getUserId(), next.raw());
    }

    /** Logout ends the whole login session (family). Unknown tokens are ignored, so logout is idempotent. */
    @Transactional
    public void revokeFamily(String rawToken) {
        tokens.findByTokenHash(RefreshTokens.hash(rawToken))
                .ifPresent(token -> tokens.revokeFamily(token.getFamilyId(), clock.instant()));
    }

    private record Created(RefreshToken entity, String raw) {
    }

    private Created create(UUID userId, UUID familyId, Instant now) {
        String raw = RefreshTokens.generate();
        RefreshToken entity = tokens.save(new RefreshToken(
                userId, familyId, RefreshTokens.hash(raw), now, now.plus(properties.refreshTokenTtl())));
        return new Created(entity, raw);
    }
}
