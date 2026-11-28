package com.flashsale.payment.config;

import com.flashsale.payment.payment.ResilientPaymentGateway;
import com.flashsale.payment.provider.MockPaymentProvider;
import io.github.resilience4j.bulkhead.ThreadPoolBulkheadRegistry;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.retry.RetryRegistry;
import io.github.resilience4j.timelimiter.TimeLimiterRegistry;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Builds the provider gateway from the Resilience4j instances named {@value #PROVIDER} in application.yml
 * (resilience4j-spring-boot3 creates the registries and their Micrometer metrics).
 */
@Configuration(proxyBeanMethods = false)
class ResilienceConfig {

    static final String PROVIDER = "paymentProvider";

    @Bean
    ResilientPaymentGateway resilientPaymentGateway(MockPaymentProvider provider, CircuitBreakerRegistry circuitBreakers,
            RetryRegistry retries, TimeLimiterRegistry timeLimiters, ThreadPoolBulkheadRegistry bulkheads) {
        return new ResilientPaymentGateway(provider, circuitBreakers.circuitBreaker(PROVIDER), retries.retry(PROVIDER),
                timeLimiters.timeLimiter(PROVIDER), bulkheads.bulkhead(PROVIDER));
    }
}
