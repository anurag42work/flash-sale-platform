package com.flashsale.order.payment;

/** payment-service could not be asked (down, timeout, unexpected answer). The caller tries again later. */
public class PaymentServiceUnavailableException extends RuntimeException {

    public PaymentServiceUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
