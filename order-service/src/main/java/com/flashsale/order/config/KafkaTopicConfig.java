package com.flashsale.order.config;

import com.flashsale.common.event.OrderCreated;
import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

/**
 * Declares the topics this service writes to. Spring's KafkaAdmin creates them at startup if missing,
 * so we do not rely on broker auto-create (which uses broker defaults and hides typos in topic names).
 */
@Configuration(proxyBeanMethods = false)
class KafkaTopicConfig {

    @Bean
    NewTopic orderCreatedTopic(@Value("${order.kafka.partitions:3}") int partitions,
            @Value("${order.kafka.replicas:1}") int replicas) {
        return TopicBuilder.name(OrderCreated.TOPIC).partitions(partitions).replicas(replicas).build();
    }
}
