package com.flashsale.order.payment;

import java.math.BigDecimal;
import java.net.http.HttpClient;
import java.util.UUID;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

/**
 * Calls payment-service's internal API (step 2.6). Connect and read timeouts, like CatalogClient: a slow
 * payment-service must not hang the reconciliation job (it holds the job lock while it runs).
 */
@Component
public class PaymentClient {

    private final RestClient rest;

    public PaymentClient(RestClient.Builder builder, PaymentProperties properties) {
        HttpClient httpClient = HttpClient.newBuilder().connectTimeout(properties.connectTimeout()).build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(properties.readTimeout());
        this.rest = builder.baseUrl(properties.baseUrl()).requestFactory(requestFactory).build();
    }

    /** @param reason null when COMPLETED; e.g. DECLINED, PROVIDER_UNAVAILABLE, EXPIRED when FAILED */
    public record PaymentStatus(UUID orderId, String status, String reason) {
    }

    private record ExpireRequest(BigDecimal amount) {
    }

    /**
     * The order's final payment state. If payment-service has no payment for it, it records one as FAILED / EXPIRED
     * (so a late OrderCreated can't charge it) and returns that. Safe to call again.
     */
    public PaymentStatus finalStatus(UUID orderId, BigDecimal amount) {
        try {
            PaymentStatus status = rest.post()
                    .uri("/internal/payments/{orderId}/expire", orderId)
                    .body(new ExpireRequest(amount))
                    .retrieve()
                    .body(PaymentStatus.class);
            if (status == null || status.status() == null) {
                throw new PaymentServiceUnavailableException("Empty answer for order " + orderId, null);
            }
            return status;
        } catch (ResourceAccessException | RestClientResponseException e) {
            throw new PaymentServiceUnavailableException("payment-service could not be asked about order " + orderId, e);
        }
    }
}
