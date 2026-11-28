package com.flashsale.gateway.ratelimit;

import com.flashsale.gateway.security.ProblemResponses;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.http.server.reactive.ServerHttpResponseDecorator;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * Turns the empty 429 of Spring Cloud Gateway's RequestRateLimiter into a ProblemDetail with Retry-After (step 2.7).
 *
 * <p>The rate limiter rejects with {@code setStatusCode(429); setComplete()}. This filter runs before every route
 * filter and hands the rest of the chain a decorated response: when {@code setComplete()} is called on a 429, it
 * writes the problem body instead of an empty one.
 *
 * <p>Retry-After (seconds) comes from the limiter's own headers: a request costs {@code X-RateLimit-Requested-Tokens}
 * and the bucket refills {@code X-RateLimit-Replenish-Rate} tokens per second, so one request's worth is back after
 * requested / rate seconds (rounded up, at least 1).
 */
@Component
class RateLimitProblemFilter implements GlobalFilter, Ordered {

    static final String REQUESTED_TOKENS = "X-RateLimit-Requested-Tokens";
    static final String REPLENISH_RATE = "X-RateLimit-Replenish-Rate";

    private final ProblemResponses problems;

    RateLimitProblemFilter(ProblemResponses problems) {
        this.problems = problems;
    }

    @Override
    public int getOrder() {
        // Before the route filters (RequestRateLimiter gets order 1, 2, ... from its position in the route).
        return Ordered.HIGHEST_PRECEDENCE + 1;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        String path = exchange.getRequest().getPath().value();
        ServerHttpResponse original = exchange.getResponse();
        ServerHttpResponseDecorator response = new ServerHttpResponseDecorator(original) {
            @Override
            public Mono<Void> setComplete() {
                if (HttpStatus.TOO_MANY_REQUESTS.equals(getStatusCode())) {
                    getHeaders().set(HttpHeaders.RETRY_AFTER, Long.toString(retryAfterSeconds(getHeaders())));
                    return problems.write(path, getDelegate(), HttpStatus.TOO_MANY_REQUESTS,
                            "Too many requests. Try again after " + getHeaders().getFirst(HttpHeaders.RETRY_AFTER)
                                    + " s.");
                }
                return super.setComplete();
            }
        };
        return chain.filter(exchange.mutate().response(response).build());
    }

    static long retryAfterSeconds(HttpHeaders headers) {
        try {
            double requested = Double.parseDouble(headers.getFirst(REQUESTED_TOKENS));
            double rate = Double.parseDouble(headers.getFirst(REPLENISH_RATE));
            return rate > 0 ? Math.max(1, (long) Math.ceil(requested / rate)) : 1;
        } catch (NullPointerException | NumberFormatException e) {
            return 1; // headers missing (e.g. a 429 from elsewhere): a safe small value
        }
    }
}
