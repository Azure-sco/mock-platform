package com.xuntian.mock.sample.jdk8;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import com.xuntian.mock.client.config.LocalConfigProvider;
import com.xuntian.mock.client.config.MockConfigProvider;
import com.xuntian.mock.client.core.model.MockMode;
import com.xuntian.mock.client.core.routing.RouteConfig;
import com.xuntian.mock.client.core.routing.RoutingSnapshot;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.web.client.ResourceAccessException;

import feign.FeignException;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class Jdk8RestTemplateRoutingIntegrationTest {

    private final AtomicReference<RecordedRequest> realRequest = new AtomicReference<RecordedRequest>();
    private final AtomicReference<RecordedRequest> mockRequest = new AtomicReference<RecordedRequest>();
    private final AtomicInteger realCalls = new AtomicInteger();
    private final AtomicInteger mockCalls = new AtomicInteger();
    private HttpServer realServer;
    private HttpServer runtimeServer;
    private ConfigurableApplicationContext application;

    @BeforeEach
    void startServersAndApplication() throws IOException {
        realServer = server(
                "{\"source\":\"FAKE_REAL\",\"data\":{\"flowNo\":\"REAL-OA-M0\"}}",
                realRequest,
                realCalls,
                false);
        runtimeServer = server(
                "{\"source\":\"M0_FIXED\",\"data\":{\"flowNo\":\"MOCK-OA-M0\"}}",
                mockRequest,
                mockCalls,
                true);

        application = new SpringApplicationBuilder(Jdk8SampleApplication.class)
                .web(WebApplicationType.NONE)
                .run(
                        "--spring.main.banner-mode=off",
                        "--oa.base-url=http://127.0.0.1:" + realServer.getAddress().getPort(),
                        "--xuntian.mock.client.app-code=sample-jdk8",
                        "--xuntian.mock.client.environment=TEST",
                        "--xuntian.mock.client.runtime-base-uri=http://127.0.0.1:" + runtimeServer.getAddress().getPort(),
                        "--xuntian.mock.client.mock-app-token=local-token-8",
                        "--xuntian.mock.client.mode=REAL",
                        "--xuntian.mock.client.allowed-business-headers[0]=X-Business-Tag");
    }

    @AfterEach
    void stopServersAndApplication() {
        if (application != null) {
            application.close();
        }
        if (realServer != null) {
            realServer.stop(0);
        }
        if (runtimeServer != null) {
            runtimeServer.stop(0);
        }
    }

    @Test
    void dynamicallySwitchesRealToMockAndStripsCredentialsFromMultipartCopy() throws Exception {
        OaSettlementGateway gateway = application.getBean(OaSettlementGateway.class);
        assertThat(gateway.createReview("SETTLE-42")).contains("FAKE_REAL");
        assertThat(realRequest.get().headers.getFirst("Authorization")).isEqualTo("Bearer real-oa-secret");

        LocalConfigProvider config = (LocalConfigProvider) application.getBean(MockConfigProvider.class);
        RoutingSnapshot current = config.current();
        config.update(RoutingSnapshot.builderFrom(current)
                .configVersion(2)
                .defaultRoute(RouteConfig.builder(MockMode.MOCK)
                        .allowBusinessHeader("X-Business-Tag")
                        .build())
                .build());

        assertThat(gateway.createReview("SETTLE-42")).contains("M0_FIXED");
        RecordedRequest sentToMock = mockRequest.get();
        assertThat(sentToMock.path).isEqualTo("/api/km-review/kmReviewRestService/addReviewNew");
        assertThat(sentToMock.headers.getFirst("Authorization")).isEqualTo("MockApp local-token-8");
        assertThat(sentToMock.headers.getFirst("Cookie")).isNull();
        assertThat(sentToMock.headers.getFirst("X-Signature")).isNull();
        assertThat(sentToMock.headers.getFirst("X-Mock-Provider")).isEqualTo("OA");
        assertThat(sentToMock.headers.getFirst("X-Mock-Api")).isEqualTo("OA_SETTLE_CREATE");
        assertThat(sentToMock.headers.getFirst("X-Business-Tag")).isEqualTo("settlement");
        assertThat(sentToMock.body).contains("SETTLE-42");

        OaNumberGateway feignGateway = application.getBean(OaNumberGateway.class);
        assertThat(feignGateway.queryNumber("REAL-OA-M0")).contains("M0_FIXED");
        RecordedRequest feignRequest = mockRequest.get();
        assertThat(feignRequest.path).isEqualTo("/api/tcl-cpms/cpmsAuditRestService/getAuditInfosNew");
        assertThat(feignRequest.headers.getFirst("Authorization")).isEqualTo("MockApp local-token-8");
        assertThat(feignRequest.headers.getFirst("Cookie")).isNull();
        assertThat(realCalls).hasValue(1);

        config.update(RoutingSnapshot.builderFrom(config.current())
                .configVersion(3)
                .defaultRoute(RouteConfig.real())
                .build());
        assertThat(gateway.createReview("SETTLE-43")).contains("FAKE_REAL");
        assertThat(realCalls).hasValue(2);

        RouteConfig resetRoute = RouteConfig.builder(MockMode.MOCK)
                .unavailablePolicy(com.xuntian.mock.client.core.model.UnavailablePolicy.FALLBACK_REAL)
                .allowRealHost("127.0.0.1")
                .build();
        config.update(RoutingSnapshot.builderFrom(config.current())
                .configVersion(4)
                .defaultRoute(resetRoute)
                .build());
        assertThatThrownBy(() -> gateway.resetProbe("rest-received"))
                .isInstanceOf(ResourceAccessException.class);
        assertThatThrownBy(() -> feignGateway.resetProbe("feign-received"))
                .isInstanceOf(FeignException.class);
        assertThat(realCalls).hasValue(2);
    }

    private HttpServer server(
            String response,
            AtomicReference<RecordedRequest> target,
            AtomicInteger calls,
            boolean resetEndpoint) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", new RecordingHandler(response, target, calls));
        if (resetEndpoint) {
            server.createContext("/__m0/reset", new ResetHandler(target, calls));
        }
        server.start();
        return server;
    }

    private static final class RecordingHandler implements HttpHandler {
        private final byte[] response;
        private final AtomicReference<RecordedRequest> target;
        private final AtomicInteger calls;

        private RecordingHandler(
                String response,
                AtomicReference<RecordedRequest> target,
                AtomicInteger calls) {
            this.response = response.getBytes(StandardCharsets.UTF_8);
            this.target = target;
            this.calls = calls;
        }

        @Override
        public void handle(HttpExchange exchange) throws IOException {
            calls.incrementAndGet();
            target.set(new RecordedRequest(
                    exchange.getRequestURI().getPath(),
                    exchange.getRequestHeaders(),
                    read(exchange.getRequestBody())));
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        }

        private String read(InputStream input) throws IOException {
            byte[] buffer = new byte[1024];
            StringBuilder body = new StringBuilder();
            int read;
            while ((read = input.read(buffer)) != -1) {
                body.append(new String(buffer, 0, read, StandardCharsets.UTF_8));
            }
            return body.toString();
        }
    }

    private static final class ResetHandler implements HttpHandler {
        private final AtomicReference<RecordedRequest> target;
        private final AtomicInteger calls;

        private ResetHandler(AtomicReference<RecordedRequest> target, AtomicInteger calls) {
            this.target = target;
            this.calls = calls;
        }

        @Override
        public void handle(HttpExchange exchange) throws IOException {
            calls.incrementAndGet();
            target.set(new RecordedRequest(
                    exchange.getRequestURI().getPath(),
                    exchange.getRequestHeaders(),
                    readBody(exchange.getRequestBody())));
            exchange.close();
        }

        private String readBody(InputStream input) throws IOException {
            byte[] buffer = new byte[1024];
            StringBuilder body = new StringBuilder();
            int read;
            while ((read = input.read(buffer)) != -1) {
                body.append(new String(buffer, 0, read, StandardCharsets.UTF_8));
            }
            return body.toString();
        }
    }

    private static final class RecordedRequest {
        private final String path;
        private final Headers headers;
        private final String body;

        private RecordedRequest(String path, Headers headers, String body) {
            this.path = path;
            this.headers = headers;
            this.body = body;
        }
    }
}
