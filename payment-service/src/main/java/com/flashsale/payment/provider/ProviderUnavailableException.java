package com.flashsale.payment.provider;

/** The provider could not answer (like HTTP 503 or a dropped connection). Transient: worth retrying. */
public class ProviderUnavailableException extends RuntimeException {

    public ProviderUnavailableException(String message) {
        super(message);
    }
}
