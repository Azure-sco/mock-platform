package com.xuntian.mock.runtime.web;

import com.xuntian.mock.runtime.capture.RuntimeRequestCapture;
import com.xuntian.mock.runtime.identity.MockAppVerifier;
import com.xuntian.mock.runtime.identity.RuntimeIdentity;
import org.junit.jupiter.api.Test;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.test.StepVerifier;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;

class ConnectionResetFilterTest {

    @Test
    void emitsTransportFailureAfterRecordingThatRuntimeReceivedRequest() {
        RuntimeRequestCapture capture = new RuntimeRequestCapture();
        MockAppVerifier verifier = headers -> new RuntimeIdentity("app", "TEST");
        ConnectionResetFilter filter = new ConnectionResetFilter(verifier, capture);
        MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest
                .post("/__m0/reset")
                .header("X-Mock-Provider", "P")
                .header("X-Mock-Api", "A")
                .header("X-Mock-Request-Id", "mr-reset-unit")
                .body("received"));

        StepVerifier.create(filter.filter(exchange, ignored ->
                        reactor.core.publisher.Mono.error(new AssertionError("chain must not be called"))))
                .expectError(IOException.class)
                .verify();

        assertThat(capture.last().requestId()).isEqualTo("mr-reset-unit");
        assertThat(capture.last().bodyBytes()).isEqualTo(8);
    }
}
