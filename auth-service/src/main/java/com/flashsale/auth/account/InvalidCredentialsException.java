package com.flashsale.auth.account;

/** Same exception for "unknown email" and "wrong password", so the API does not reveal which emails exist. */
public class InvalidCredentialsException extends RuntimeException {

    public InvalidCredentialsException() {
        super("Invalid email or password");
    }
}
