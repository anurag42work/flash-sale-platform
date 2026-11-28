package com.flashsale.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.flashsale.common.event.EventEnvelope;
import com.flashsale.common.event.OrderCreated;
import com.flashsale.payment.provider.MockPaymentProvider;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Import;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

/** POST /internal/payments/{orderId}/expire, used by order-service's reconciliation job (step 2.6). */
@Import(TestcontainersConfiguration.class)
// Close this context after the class: a cached context keeps its Kafka listener and outbox relay running and
// would react to the next test class's events on the shared broker.
@DirtiesContext
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = {
        "payment.provider.latency-ms=0",
        "payment.provider.failure-rate-percent=0"
})
class PaymentExpireIT {

    private static final ParameterizedTypeReference<Map<String, Object>> JSON = new ParameterizedTypeReference<>() {
    };
    private static final Duration DEADLINE = Duration.ofSeconds(30);

    @Autowired
    TestRestTemplate rest;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    ObjectMapper json;

    @Autowired
    KafkaTemplate<String, String> kafka;

    @MockitoSpyBean
    MockPaymentProvider provider;

    @Test
    void orderWithoutPaymentIsRecordedAsExpiredOnceAndTheAnswerIsStable() {
        UUID orderId = UUID.randomUUID();

        ResponseEntity<Map<String, Object>> first = expire(orderId, "20.00");
        ResponseEntity<Map<String, Object>> second = expire(orderId, "20.00");

        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(first.getBody()).containsEntry("status", "FAILED").containsEntry("reason", "EXPIRED")
                .containsEntry("orderId", orderId.toString());
        assertThat(second.getBody()).isEqualTo(first.getBody());
        assertThat(paymentRows(orderId)).isEqualTo(1);
    }

    @Test
    void existingPaymentIsReturnedUnchanged() throws Exception {
        EventEnvelope<OrderCreated> event = orderCreated(UUID.randomUUID());
        publish(event);
        awaitTrue(() -> paymentRows(event.data().orderId()) == 1);

        ResponseEntity<Map<String, Object>> response = expire(event.data().orderId(), "20.00");

        assertThat(response.getBody()).containsEntry("status", "COMPLETED");
        assertThat(paymentRows(event.data().orderId())).isEqualTo(1);
    }

    @Test
    void lateOrderCreatedForAnExpiredOrderIsNeverCharged() throws Exception {
        EventEnvelope<OrderCreated> event = orderCreated(UUID.randomUUID());
        UUID orderId = event.data().orderId();
        expire(orderId, "20.00"); // the reconciliation job gave up on this order first

        publish(event); // e.g. replayed from order.created.DLT, or a relay that was stuck
        awaitTrue(() -> jdbc.queryForObject("SELECT count(*) FROM processed_events WHERE event_id = ?",
                Integer.class, event.eventId()) == 1);

        verify(provider, never()).charge(eq(orderId), any());
        assertThat(jdbc.queryForObject("SELECT status || ':' || failure_reason FROM payments WHERE order_id = ?",
                String.class, orderId)).isEqualTo("FAILED:EXPIRED");
    }

    @Test
    void invalidAmountOrOrderIdIsAProblemDetail400() {
        ResponseEntity<Map<String, Object>> zero = expire(UUID.randomUUID(), "0");
        ResponseEntity<Map<String, Object>> badId = rest.exchange("/internal/payments/not-a-uuid/expire",
                HttpMethod.POST, body("20.00"), JSON);

        assertThat(zero.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(zero.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_PROBLEM_JSON);
        assertThat(zero.getBody()).containsKey("detail");
        assertThat(badId.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(badId.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_PROBLEM_JSON);
    }

    private ResponseEntity<Map<String, Object>> expire(UUID orderId, String amount) {
        return rest.exchange("/internal/payments/" + orderId + "/expire", HttpMethod.POST, body(amount), JSON);
    }

    private static HttpEntity<Map<String, Object>> body(String amount) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return new HttpEntity<>(Map.of("amount", new BigDecimal(amount)), headers);
    }

    private int paymentRows(UUID orderId) {
        return jdbc.queryForObject("SELECT count(*) FROM payments WHERE order_id = ?", Integer.class, orderId);
    }

    private EventEnvelope<OrderCreated> orderCreated(UUID orderId) {
        OrderCreated data = new OrderCreated(orderId, UUID.randomUUID(), new BigDecimal("20.00"),
                List.of(new OrderCreated.Item(1, 2, new BigDecimal("10.00"))));
        return new EventEnvelope<>(UUID.randomUUID(), OrderCreated.TYPE, OrderCreated.VERSION, Instant.now(), data);
    }

    private void publish(EventEnvelope<OrderCreated> event) throws Exception {
        kafka.send(OrderCreated.TOPIC, event.data().orderId().toString(), json.writeValueAsString(event))
                .get(10, TimeUnit.SECONDS);
    }

    private static void awaitTrue(BooleanSupplier condition) throws InterruptedException {
        long end = System.nanoTime() + DEADLINE.toNanos();
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > end) {
                throw new AssertionError("condition not met within " + DEADLINE);
            }
            Thread.sleep(200);
        }
    }
}
