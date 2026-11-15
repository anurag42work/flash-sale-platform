package com.flashsale.auth.account;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.UUID;

/** Request and response bodies of the auth API. Entities are never returned directly. */
public final class AccountDtos {

    private AccountDtos() {
    }

    // BCrypt only uses the first 72 bytes of a password, so longer passwords are rejected.
    public record RegisterRequest(
            @NotBlank @Email @Size(max = 254) String email,
            @NotBlank @Size(min = 8, max = 72) String password) {
    }

    public record LoginRequest(
            @NotBlank @Size(max = 254) String email,
            @NotBlank @Size(max = 72) String password) {
    }

    public record RefreshRequest(@NotBlank @Size(max = 128) String refreshToken) {
    }

    public record UserResponse(UUID id, String email, String role) {
    }

    /** expiresIn is the access token lifetime in seconds (OAuth 2.0 naming). */
    public record TokenResponse(String accessToken, String tokenType, long expiresIn, String refreshToken) {
    }
}
