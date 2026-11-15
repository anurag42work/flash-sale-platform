package com.flashsale.auth;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.util.Base64;
import org.springframework.test.context.DynamicPropertyRegistry;

/** One RSA key pair per test run, generated in memory, so no key file is ever committed. */
public final class TestKeys {

    private static final KeyPair PAIR = generate();

    private TestKeys() {
    }

    public static RSAPublicKey publicKey() {
        return (RSAPublicKey) PAIR.getPublic();
    }

    public static RSAPrivateKey privateKey() {
        return (RSAPrivateKey) PAIR.getPrivate();
    }

    /** Passes the keys as PEM text, the same format scripts/gen-dev-keys.sh writes to files. */
    public static void register(DynamicPropertyRegistry registry) {
        registry.add("auth.token.public-key", () -> pem("PUBLIC KEY", publicKey().getEncoded()));
        registry.add("auth.token.private-key", () -> pem("PRIVATE KEY", privateKey().getEncoded()));
    }

    private static String pem(String type, byte[] der) {
        String body = Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.US_ASCII)).encodeToString(der);
        return "-----BEGIN " + type + "-----\n" + body + "\n-----END " + type + "-----\n";
    }

    private static KeyPair generate() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            return generator.generateKeyPair();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
