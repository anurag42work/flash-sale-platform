package com.flashsale.gateway.correlation;

import com.flashsale.common.web.Headers;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

/**
 * Gives every request an X-Correlation-Id, passes it downstream and returns it in the response, so one
 * request can be followed through the logs of every service. Runs before security, so 401s carry it too.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
class CorrelationIdFilter implements WebFilter {

    // Accept a client id only if it is short and plain; anything else could inject text into headers or logs.
    private static final Pattern VALID_ID = Pattern.compile("[A-Za-z0-9-]{1,64}");

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        String incoming = exchange.getRequest().getHeaders().getFirst(Headers.CORRELATION_ID);
        String id = incoming != null && VALID_ID.matcher(incoming).matches() ? incoming : UUID.randomUUID().toString();

        ServerHttpRequest request = exchange.getRequest().mutate()
                .headers(headers -> headers.set(Headers.CORRELATION_ID, id))
                .build();
        // Set just before the response is sent, so it also replaces any value copied from a downstream response.
        exchange.getResponse().beforeCommit(() -> {
            exchange.getResponse().getHeaders().set(Headers.CORRELATION_ID, id);
            return Mono.empty();
        });
        return chain.filter(exchange.mutate().request(request).build());
    }
}
