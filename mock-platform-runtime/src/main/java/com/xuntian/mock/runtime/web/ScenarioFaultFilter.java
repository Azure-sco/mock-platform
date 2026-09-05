package com.xuntian.mock.runtime.web;

import com.xuntian.mock.runtime.engine.RuntimeFault;
import org.reactivestreams.Publisher;
import org.springframework.core.Ordered;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.server.reactive.AbstractServerHttpResponse;
import org.springframework.http.server.reactive.ServerHttpResponseDecorator;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.netty.http.server.HttpServerResponse;

import java.io.IOException;
import java.time.Duration;

/** Applies published transport faults after the transactional decision has completed. */
@Component
public final class ScenarioFaultFilter implements WebFilter, Ordered {

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        ServerHttpResponseDecorator response = new ServerHttpResponseDecorator(exchange.getResponse()) {
            @Override
            public Mono<Void> writeWith(Publisher<? extends DataBuffer> body) {
                RuntimeFault fault = exchange.getAttribute(MockRuntimeController.RUNTIME_FAULT_ATTRIBUTE);
                if (fault == null || !isConnectionFault(fault)) return super.writeWith(body);
                return abort(fault);
            }

            @Override
            public Mono<Void> writeAndFlushWith(
                    Publisher<? extends Publisher<? extends DataBuffer>> body) {
                RuntimeFault fault = exchange.getAttribute(MockRuntimeController.RUNTIME_FAULT_ATTRIBUTE);
                if (fault == null || !isConnectionFault(fault)) return super.writeAndFlushWith(body);
                return abort(fault);
            }

            private Mono<Void> abort(RuntimeFault fault) {
                Mono<Long> wait = fault.durationMs() == 0
                        ? Mono.just(0L)
                        : Mono.delay(Duration.ofMillis(fault.durationMs()));
                return wait.then(Mono.defer(() -> {
                    if (getDelegate() instanceof AbstractServerHttpResponse response
                            && response.getNativeResponse() instanceof HttpServerResponse reactor) {
                        reactor.withConnection(connection -> connection.channel().close());
                        return Mono.empty();
                    }
                    return Mono.error(new IOException("Intentional Mock transport fault: " + fault.type()));
                }));
            }
        };
        return chain.filter(exchange.mutate().response(response).build());
    }

    private static boolean isConnectionFault(RuntimeFault fault) {
        return fault.type() == RuntimeFault.Type.READ_TIMEOUT
                || fault.type() == RuntimeFault.Type.CONNECTION_RESET;
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE + 10;
    }
}
