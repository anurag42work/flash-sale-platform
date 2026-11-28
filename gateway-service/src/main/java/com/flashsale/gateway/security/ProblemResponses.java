package com.flashsale.gateway.security;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * Writes errors produced by the gateway itself (401/403 from security, 429 from rate limiting) as RFC 7807
 * ProblemDetail, like the services do.
 */
@Component
public class ProblemResponses {

    private final ObjectMapper objectMapper;

    ProblemResponses(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    Mono<Void> write(ServerWebExchange exchange, HttpStatus status, String detail) {
        return write(exchange.getRequest().getPath().value(), exchange.getResponse(), status, detail);
    }

    /** @param path the request path, used as the problem's "instance" */
    public Mono<Void> write(String path, ServerHttpResponse response, HttpStatus status, String detail) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setInstance(URI.create(path));

        response.setStatusCode(status);
        response.getHeaders().setContentType(MediaType.APPLICATION_PROBLEM_JSON);
        if (status == HttpStatus.UNAUTHORIZED) {
            // RFC 6750: a 401 for a bearer-token API names the scheme the client must use.
            response.getHeaders().set(HttpHeaders.WWW_AUTHENTICATE, "Bearer");
        }
        try {
            DataBuffer body = response.bufferFactory().wrap(objectMapper.writeValueAsBytes(problem));
            return response.writeWith(Mono.just(body));
        } catch (JsonProcessingException e) {
            return Mono.error(e);
        }
    }
}
