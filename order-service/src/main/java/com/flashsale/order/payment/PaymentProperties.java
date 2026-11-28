package com.flashsale.order.payment;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** payment.* settings: where payment-service is and how long to wait for it (used by the reconciliation job). */
@ConfigurationProperties("payment")
public record PaymentProperties(String baseUrl, Duration connectTimeout, Duration readTimeout) {
}
