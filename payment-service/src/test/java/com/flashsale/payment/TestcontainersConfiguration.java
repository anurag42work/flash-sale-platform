package com.flashsale.payment;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Real Postgres for integration tests. @ServiceConnection points the datasource at it.
 * withReuse keeps the container between runs when ~/.testcontainers.properties has
 * testcontainers.reuse.enable=true (see README); otherwise a fresh one is started.
 */
@TestConfiguration(proxyBeanMethods = false)
class TestcontainersConfiguration {

    @Bean
    @ServiceConnection
    PostgreSQLContainer<?> postgres() {
        return new PostgreSQLContainer<>("postgres:16.16-alpine")
                // Test data is throwaway: trade durability for speed and memory.
                .withCommand("postgres", "-c", "fsync=off", "-c", "shared_buffers=32MB")
                .withReuse(true);
    }
}
