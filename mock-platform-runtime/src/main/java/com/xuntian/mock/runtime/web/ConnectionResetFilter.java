package com.xuntian.mock.runtime.web;

import com.xuntian.mock.runtime.capture.RuntimeRequestCapture;
import com.xuntian.mock.runtime.identity.MockAppVerifier;
import com.xuntian.mock.runtime.identity.RuntimeIdentity;
import org.reactivestreams.Publisher;
import org.springframework.core.Ordered;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.context.annotation.Profile;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

@Component
@Profile({"local", "test"})
public final class ConnectionResetFilter implements WebFilter, Ordered {

    private static final String PATH = "/__m0/reset";
    private final MockAppVerifier verifier;
    private final RuntimeRequestCapture capture;

    public ConnectionResetFilter(MockAppVerifier verifier, RuntimeRequestCapture capture) {
        this.verifier = verifier;
        this.capture = capture;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        if (!PATH.equals(exchange.getRequest().getURI().getPath())) {
            return chain.filter(exchange);
        }
        RuntimeIdentity identity = verifier.verify(exchange.getRequest().getHeaders());
        String provider = value(exchange, "X-Mock-Provider", "unknown");
        String api = value(exchange, "X-Mock-Api", "unknown");
        String requestId = value(exchange, "X-Mock-Request-Id", "mr-" + UUID.randomUUID());
        return MockRuntimeController.readBody(exchange.getRequest()).flatMap(body -> {
            capture.record(identity, exchange.getRequest(), provider, api, requestId, body.length);
            exchange.getResponse().setStatusCode(HttpStatus.OK);
            exchange.getResponse().getHeaders().setContentLength(1024);
            DataBuffer partial = exchange.getResponse().bufferFactory()
                    .wrap("runtime-received".getBytes(StandardCharsets.UTF_8));
            Flux<Publisher<? extends DataBuffer>> flushed = Flux.concat(
                    Mono.just(Mono.just(partial)),
                    Mono.error(new IOException("M0 intentional connection reset")));
            return exchange.getResponse().writeAndFlushWith(flushed);
        });
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE;
    }

    private String value(ServerWebExchange exchange, String name, String fallback) {
        String value = exchange.getRequest().getHeaders().getFirst(name);
        return value == null || value.trim().isEmpty() ? fallback : value;
    }
}
