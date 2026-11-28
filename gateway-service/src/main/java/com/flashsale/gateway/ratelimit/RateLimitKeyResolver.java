package com.flashsale.gateway.ratelimit;

import java.net.InetSocketAddress;
import org.springframework.cloud.gateway.filter.ratelimit.KeyResolver;
import org.springframework.cloud.gateway.route.Route;
import org.springframework.cloud.gateway.support.ServerWebExchangeUtils;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * Whose token bucket a request uses (step 2.7): {@code <routeId>:user:<sub>} with a valid JWT, otherwise
 * {@code <routeId>:ip:<address>}. Referenced from application.yml as {@code #{@rateLimitKeyResolver}}.
 *
 * <ul>
 *   <li>The route id is part of the key, so the strict login bucket and the general bucket of the same client are
 *       separate (and their different limits never mix).</li>
 *   <li>The IP is the TCP peer address. X-Forwarded-For is NOT used: any client can set it and get a fresh bucket
 *       per request. On the VM the gateway is the edge, so the peer is the real client. Behind a proxy you'd trust
 *       that header only from the proxy's address.</li>
 * </ul>
 */
@Component("rateLimitKeyResolver")
public class RateLimitKeyResolver implements KeyResolver {

    @Override
    public Mono<String> resolve(ServerWebExchange exchange) {
        String route = routeId(exchange);
        return exchange.getPrincipal()
                .filter(JwtAuthenticationToken.class::isInstance) // ignore anonymous principals
                .map(principal -> route + ":user:" + principal.getName()) // getName() = the JWT "sub" (user id)
                .defaultIfEmpty(route + ":ip:" + clientIp(exchange));
    }

    private static String routeId(ServerWebExchange exchange) {
        Route route = exchange.getAttribute(ServerWebExchangeUtils.GATEWAY_ROUTE_ATTR);
        return route == null ? "none" : route.getId();
    }

    private static String clientIp(ServerWebExchange exchange) {
        InetSocketAddress remote = exchange.getRequest().getRemoteAddress();
        if (remote == null || remote.getAddress() == null) {
            return "unknown";
        }
        return remote.getAddress().getHostAddress();
    }
}
