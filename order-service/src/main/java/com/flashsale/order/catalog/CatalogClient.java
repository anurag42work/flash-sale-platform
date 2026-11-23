package com.flashsale.order.catalog;

import java.math.BigDecimal;
import java.net.http.HttpClient;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

/**
 * Calls catalog-service's internal inventory API. Every call has a connect and a read timeout: without them a
 * slow catalog would hold this request thread (and later a DB connection) forever.
 */
@Component
public class CatalogClient {

    private final RestClient rest;

    public CatalogClient(RestClient.Builder builder, CatalogProperties properties) {
        HttpClient httpClient = HttpClient.newBuilder().connectTimeout(properties.connectTimeout()).build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(properties.readTimeout());
        this.rest = builder.baseUrl(properties.baseUrl()).requestFactory(requestFactory).build();
    }

    public record Reservation(long productId, int quantity, BigDecimal unitPrice, int remaining) {
    }

    private record QuantityRequest(int quantity) {
    }

    public Reservation reserve(long productId, int quantity) {
        try {
            return rest.post()
                    .uri("/internal/inventory/{id}/reserve", productId)
                    .body(new QuantityRequest(quantity))
                    .retrieve()
                    .onStatus(status -> status.isSameCodeAs(HttpStatus.CONFLICT), (request, response) -> {
                        throw new OutOfStockException(productId);
                    })
                    .onStatus(status -> status.isSameCodeAs(HttpStatus.NOT_FOUND), (request, response) -> {
                        throw new UnknownProductException(productId);
                    })
                    .body(Reservation.class);
        } catch (ResourceAccessException | RestClientResponseException e) {
            // Connection refused, timeout, or any other error status from catalog.
            throw new CatalogUnavailableException(e);
        }
    }

    public void release(long productId, int quantity) {
        try {
            rest.post()
                    .uri("/internal/inventory/{id}/release", productId)
                    .body(new QuantityRequest(quantity))
                    .retrieve()
                    .toBodilessEntity();
        } catch (ResourceAccessException | RestClientResponseException e) {
            throw new CatalogUnavailableException(e);
        }
    }
}
