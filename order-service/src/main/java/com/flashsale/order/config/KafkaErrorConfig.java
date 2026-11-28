package com.flashsale.order.config;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.flashsale.common.event.PaymentCompleted;
import com.flashsale.common.event.PaymentFailed;
import com.flashsale.common.kafka.KafkaErrorHandling;
import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.CommonErrorHandler;

/**
 * Incident 004: retry with backoff, then dead-letter topic. Spring Boot plugs a CommonErrorHandler bean into every
 * @KafkaListener container. Malformed JSON is bad data: never retried.
 */
@Configuration(proxyBeanMethods = false)
class KafkaErrorConfig {

    @Bean
    CommonErrorHandler kafkaErrorHandler(KafkaTemplate<?, ?> template) {
        return KafkaErrorHandling.deadLetterErrorHandler(template, JsonProcessingException.class);
    }

    /** DLTs for the topics this service consumes; same partitions as payment.* (declared by payment-service). */
    @Bean
    NewTopic paymentCompletedDeadLetterTopic(@Value("${order.kafka.partitions:3}") int partitions,
            @Value("${order.kafka.replicas:1}") int replicas) {
        return KafkaErrorHandling.deadLetterTopic(PaymentCompleted.TOPIC, partitions, replicas);
    }

    @Bean
    NewTopic paymentFailedDeadLetterTopic(@Value("${order.kafka.partitions:3}") int partitions,
            @Value("${order.kafka.replicas:1}") int replicas) {
        return KafkaErrorHandling.deadLetterTopic(PaymentFailed.TOPIC, partitions, replicas);
    }
}
