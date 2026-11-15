package com.flashsale.auth.token;

/** Unknown, expired, revoked or reused refresh token. Clients get the same 401 for all of them. */
public class InvalidRefreshTokenException extends RuntimeException {

    public InvalidRefreshTokenException() {
        super("Invalid refresh token");
    }
}
