package com.flashsale.auth.token;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;

/** Creating and hashing opaque refresh tokens. */
final class RefreshTokens {

    private static final SecureRandom RANDOM = new SecureRandom();

    private RefreshTokens() {
    }

    /** 32 random bytes (256 bits), URL-safe Base64 without padding: 43 characters. */
    static String generate() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /**
     * SHA-256 as 64 hex characters. Unlike a password, the token is 256 random bits and cannot be guessed,
     * so a fast unsalted hash is safe here, and it can be looked up through a unique index.
     */
    static String hash(String token) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is always available in the JDK", e);
        }
    }
}
