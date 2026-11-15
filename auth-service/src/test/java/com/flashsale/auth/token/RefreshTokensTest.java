package com.flashsale.auth.token;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

class RefreshTokensTest {

    @Test
    void generatesUrlSafeTokensOf256Bits() {
        String token = RefreshTokens.generate();

        assertThat(token).hasSize(43).matches("[A-Za-z0-9_-]+");
    }

    @Test
    void generatesDifferentTokensEachTime() {
        Set<String> tokens = new HashSet<>();
        for (int i = 0; i < 1000; i++) {
            tokens.add(RefreshTokens.generate());
        }

        assertThat(tokens).hasSize(1000);
    }

    @Test
    void hashIsSha256AsLowercaseHex() {
        // Known SHA-256 test vector for "abc".
        assertThat(RefreshTokens.hash("abc"))
                .isEqualTo("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
    }
}
