package com.flashsale.gateway.security;

import com.flashsale.common.web.Headers;
import java.util.List;
import java.util.Optional;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * Services behind the gateway trust X-User-Id / X-User-Roles. So on every routed request the gateway first
 * removes whatever the client sent in those headers, then sets them from the validated JWT (if there is one).
 * Without the removal, anyone could call a public route with "X-User-Id: <someone else>".
 */
@Component
class IdentityHeadersFilter implements GlobalFilter, Ordered {

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        return exchange.getPrincipal()
                .filter(JwtAuthenticationToken.class::isInstance)
                .map(principal -> Optional.of(((JwtAuthenticationToken) principal).getToken()))
                .defaultIfEmpty(Optional.empty())
                .flatMap(jwt -> chain.filter(withIdentityHeaders(exchange, jwt)));
    }

    private static ServerWebExchange withIdentityHeaders(ServerWebExchange exchange, Optional<Jwt> jwt) {
        return exchange.mutate()
                .request(request -> request.headers(headers -> {
                    headers.remove(Headers.USER_ID);
                    headers.remove(Headers.USER_ROLES);
                    jwt.ifPresent(token -> {
                        headers.set(Headers.USER_ID, token.getSubject());
                        List<String> roles = token.getClaimAsStringList("roles");
                        headers.set(Headers.USER_ROLES, roles == null ? "" : String.join(",", roles));
                    });
                }))
                .build();
    }

    // Run before the routing filters that send the request downstream.
    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE;
    }
}
