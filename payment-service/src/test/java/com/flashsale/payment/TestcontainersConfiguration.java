package com.flashsale.payment;

import com.flashsale.common.event.OrderCreated;
import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.kafka.config.TopicBuilder;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.kafka.KafkaContainer;

/**
 * Real Postgres and Kafka for integration tests. @ServiceConnection points the datasource / Kafka clients at them.
 * withReuse keeps the Postgres container between runs when ~/.testcontainers.properties has
 * testcontainers.reuse.enable=true (see README); otherwise a fresh one is started.
 */
@TestConfiguration(proxyBeanMethods = false)
class TestcontainersConfiguration {

    /** One Kafka per test JVM, shared by every Spring test context (fits the 3 GB Docker limit). */
    static final KafkaContainer KAFKA = new KafkaContainer("apache/kafka:3.9.1")
            .withEnv("KAFKA_HEAP_OPTS", "-Xmx256m -Xms256m");

    @Bean
    @ServiceConnection
    PostgreSQLContainer<?> postgres() {
        return new PostgreSQLContainer<>("postgres:16.16-alpine")
                // Test data is throwaway: trade durability for speed and memory.
                .withCommand("postgres", "-c", "fsync=off", "-c", "shared_buffers=32MB")
                .withReuse(true);
    }

    // destroyMethod "": do not stop the shared broker when the first context closes.
    @Bean(destroyMethod = "")
    @ServiceConnection
    KafkaContainer kafka() {
        return KAFKA;
    }

    /**
     * In production order-service declares order.created with 3 partitions. Nobody declares it in payment's tests,
     * so the broker would auto-create it with 1 partition and only one of the 3 listener threads would get work.
     */
    @Bean
    NewTopic orderCreatedTopic() {
        return TopicBuilder.name(OrderCreated.TOPIC).partitions(3).replicas(1).build();
    }
}
