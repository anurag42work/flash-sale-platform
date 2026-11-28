package com.flashsale.payment.outbox;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @param pollInterval pause between two relay runs (the worst-case extra delay before an event is published)
 * @param batchSize    max rows one run locks and publishes
 * @param sendTimeout  how long the relay waits for Kafka's ack of one event before counting it as failed;
 *                     keep it above the producer's delivery.timeout.ms
 */
@ConfigurationProperties("outbox")
public record OutboxProperties(Duration pollInterval, int batchSize, Duration sendTimeout) {
}
