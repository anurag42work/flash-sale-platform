package com.flashsale.payment.config;

import com.flashsale.common.event.PaymentCompleted;
import com.flashsale.common.event.PaymentFailed;
import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

/** Topics this service writes to; KafkaAdmin creates them at startup if missing (same as order-service). */
@Configuration(proxyBeanMethods = false)
class KafkaTopicConfig {

    @Bean
    NewTopic paymentCompletedTopic(@Value("${payment.kafka.partitions:3}") int partitions,
            @Value("${payment.kafka.replicas:1}") int replicas) {
        return TopicBuilder.name(PaymentCompleted.TOPIC).partitions(partitions).replicas(replicas).build();
    }

    @Bean
    NewTopic paymentFailedTopic(@Value("${payment.kafka.partitions:3}") int partitions,
            @Value("${payment.kafka.replicas:1}") int replicas) {
        return TopicBuilder.name(PaymentFailed.TOPIC).partitions(partitions).replicas(replicas).build();
    }
}
