package com.flashsale.order.order;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.flashsale.common.event.OrderCreated;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

/**
 * NAIVE on purpose (incident 003, dual write): publishes straight to Kafka AFTER the order row is committed.
 *
 * <p>Two writes to two systems with no shared transaction. If this send fails (Kafka down, timeout, the process
 * dies right after the commit), the order stays PENDING in the DB and its event is lost: nothing ever retries it.
 * Fixed in step 2.2 with the transactional outbox.
 */
@Component
class OrderEventPublisher {

    private static final Logger log = LoggerFactory.getLogger(OrderEventPublisher.class);

    private final KafkaTemplate<String, String> kafka;
    private final ObjectMapper json;

    OrderEventPublisher(KafkaTemplate<String, String> kafka, ObjectMapper json) {
        this.kafka = kafka;
        this.json = json;
    }

    /** Fire and forget: a failure is only logged, so the client still gets 202 for an order with no event. */
    void publishCreated(Order order) {
        OrderCreated event = new OrderCreated(order.getId(), order.getUserId(), order.getTotalAmount(),
                order.getItems().stream()
                        .map(i -> new OrderCreated.Item(i.getProductId(), i.getQuantity(), i.getUnitPrice()))
                        .toList(),
                order.getCreatedAt());
        String key = order.getId().toString();
        try {
            kafka.send(OrderCreated.TOPIC, key, json.writeValueAsString(event))
                    .whenComplete((result, error) -> {
                        if (error != null) {
                            log.error("OrderCreated for order {} was not sent (lost)", key, error);
                        }
                    });
        } catch (JsonProcessingException | RuntimeException e) {
            log.error("OrderCreated for order {} was not sent (lost)", key, e);
        }
    }
}
