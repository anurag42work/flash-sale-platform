package com.flashsale.common.kafka;

import com.flashsale.common.event.InvalidEventException;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.common.TopicPartition;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.KafkaOperations;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.support.ExponentialBackOffWithMaxRetries;

/**
 * Shared consumer error policy (incident 004). Used by every service with a Kafka listener.
 *
 * <ul>
 *   <li>A failing record is tried {@value #ATTEMPTS} times: at once, after 1 s, after 2 s more. The partition waits
 *       meanwhile, so events of one order stay in order.</li>
 *   <li>Then it is published to {@code <topic>.DLT} (same key, same partition number) with headers describing the
 *       error and the original topic/partition/offset, and the consumer moves on. Nothing is lost:
 *       {@code scripts/dlt.sh} lists and replays.</li>
 *   <li>Bad data ({@link InvalidEventException} and whatever the caller adds, e.g. Jackson's parse exception) is never
 *       retried: retrying can't fix it and would only delay the healthy records behind it.</li>
 * </ul>
 */
public final class KafkaErrorHandling {

    public static final String DLT_SUFFIX = ".DLT";
    static final int ATTEMPTS = 3;

    private KafkaErrorHandling() {
    }

    /**
     * @param template    used to publish to the DLT
     * @param notRetryable extra exception types that mean "bad data" in this service (checked along the cause chain)
     */
    @SafeVarargs
    public static DefaultErrorHandler deadLetterErrorHandler(KafkaOperations<?, ?> template,
            Class<? extends Exception>... notRetryable) {
        ExponentialBackOffWithMaxRetries backOff = new ExponentialBackOffWithMaxRetries(ATTEMPTS - 1);
        backOff.setInitialInterval(1_000);
        backOff.setMultiplier(2.0);
        // Explicit destination: Spring Kafka 3.x defaults to "<topic>-dlt"; we name it ourselves and keep the partition.
        DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(template,
                (record, exception) -> new TopicPartition(record.topic() + DLT_SUFFIX, record.partition()));
        DefaultErrorHandler handler = new DefaultErrorHandler(recoverer, backOff);
        handler.addNotRetryableExceptions(InvalidEventException.class);
        handler.addNotRetryableExceptions(notRetryable);
        return handler;
    }

    /** The DLT for a topic. Same partition count, because the recoverer keeps the original partition number. */
    public static NewTopic deadLetterTopic(String topic, int partitions, int replicas) {
        return TopicBuilder.name(topic + DLT_SUFFIX).partitions(partitions).replicas(replicas).build();
    }
}
