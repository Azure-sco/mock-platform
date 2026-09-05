package com.xuntian.mock.runtime.web;

import com.xuntian.mock.common.ErrorCode;
import com.xuntian.mock.common.PlatformException;
import com.xuntian.mock.runtime.RuntimeProperties;
import com.xuntian.mock.runtime.admission.AdmissionAuthorizer;
import com.xuntian.mock.runtime.capture.RuntimeRequestCapture;
import com.xuntian.mock.runtime.engine.RuntimeExecution;
import com.xuntian.mock.runtime.engine.RuntimeFault;
import com.xuntian.mock.runtime.engine.RuntimeRequest;
import com.xuntian.mock.runtime.flow.RuntimeRequestExecutor;
import com.xuntian.mock.runtime.identity.MockAppVerifier;
import com.xuntian.mock.runtime.identity.RuntimeIdentity;
import com.xuntian.mock.runtime.requestlog.RequestLogEntry;
import com.xuntian.mock.runtime.requestlog.RequestLogWriter;
import com.xuntian.mock.runtime.release.PinnedRuntimeSnapshot;
import com.xuntian.mock.runtime.snapshot.RuntimeSnapshot;
import com.xuntian.mock.runtime.snapshot.RuntimeSnapshotRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.time.Duration;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
public final class MockRuntimeController {

    public static final String MOCK_REQUEST_ID_ATTRIBUTE =
            MockRuntimeController.class.getName() + ".mockRequestId";
    public static final String TRACE_ID_ATTRIBUTE = MockRuntimeController.class.getName() + ".traceId";
    public static final String RUNTIME_FAULT_ATTRIBUTE =
            MockRuntimeController.class.getName() + ".runtimeFault";
    static final int MAX_BODY_BYTES = 1024 * 1024;

    private static final Logger LOG = LoggerFactory.getLogger(MockRuntimeController.class);
    private final MockAppVerifier verifier;
    private final AdmissionAuthorizer admissionAuthorizer;
    private final RuntimeRequestCapture capture;
    private final RuntimeSnapshotRepository snapshots;
    private final RuntimeRequestExecutor executionService;
    private final RequestLogWriter requestLogWriter;
    private final RuntimeProperties properties;

    public MockRuntimeController(
            MockAppVerifier verifier,
            AdmissionAuthorizer admissionAuthorizer,
            RuntimeRequestCapture capture,
            RuntimeSnapshotRepository snapshots,
            RuntimeRequestExecutor executionService,
            RequestLogWriter requestLogWriter,
            RuntimeProperties properties) {
        this.verifier = verifier;
        this.admissionAuthorizer = admissionAuthorizer;
        this.capture = capture;
        this.snapshots = snapshots;
        this.executionService = executionService;
        this.requestLogWriter = requestLogWriter;
        this.properties = properties;
    }

    @RequestMapping("/**")
    public Mono<ResponseEntity<byte[]>> handle(ServerWebExchange exchange) {
        ServerHttpRequest request = exchange.getRequest();
        String mockRequestId = headerOrGenerate(request, "X-Mock-Request-Id", "mr-");
        String traceId = headerOrGenerate(request, "X-Trace-Id", "trace-");
        exchange.getAttributes().put(MOCK_REQUEST_ID_ATTRIBUTE, mockRequestId);
        exchange.getAttributes().put(TRACE_ID_ATTRIBUTE, traceId);
        return Mono.defer(() -> {
            RuntimeIdentity identity = verifier.verify(request.getHeaders());
            String provider = requiredHeader(request, "X-Mock-Provider");
            String api = requiredHeader(request, "X-Mock-Api");
            return readBody(request).flatMap(body -> process(
                    exchange, request, identity, provider, api, mockRequestId, traceId, body));
        });
    }

    private Mono<ResponseEntity<byte[]>> process(
            ServerWebExchange exchange,
            ServerHttpRequest serverRequest,
            RuntimeIdentity identity,
            String provider,
            String api,
            String mockRequestId,
            String traceId,
            byte[] body) {
        long started = System.nanoTime();
        Instant requestTime = Instant.now();
        admissionAuthorizer.authorize(identity, provider, api, requestTime);
        capture.record(identity, serverRequest, provider, api, mockRequestId, body.length);
        RuntimeRequest request = new RuntimeRequest(
                identity.environment(), identity.appCode(), identity.tenantCode(), identity.testAccount(), provider, api,
                serverRequest.getMethod().name(),
                serverRequest.getURI().getRawPath(),
                serverRequest.getHeaders().getFirst(HttpHeaders.CONTENT_TYPE),
                copy(serverRequest.getHeaders()),
                copy(serverRequest.getQueryParams()),
                body, mockRequestId, traceId);
        return Mono.defer(() -> {
            PinnedRuntimeSnapshot pinned = snapshots.requirePinned(
                    identity.environment(), identity.appCode(), requestTime);
            return executionService.execute(pinned, request, requestTime, UUID.randomUUID());
        }).flatMap(result -> {
            RuntimeExecution execution = result.execution();
            RequestLogEntry entry = RequestLogEntry.success(
                    request, execution, elapsedMillis(started), properties, requestTime);
            exchange.getAttributes().put(RUNTIME_FAULT_ATTRIBUTE, execution.fault());
            Mono<ResponseEntity<byte[]>> response = Mono.fromSupplier(() -> toResponse(
                    execution, result.activationVersion(), mockRequestId, traceId));
            if (execution.delayMs() > 0) {
                response = Mono.delay(Duration.ofMillis(execution.delayMs())).then(response);
            }
            return safeWrite(entry).then(response);
        }).onErrorResume(failure -> {
            RequestLogEntry entry = RequestLogEntry.failure(
                    request, failure, elapsedMillis(started), properties, Instant.now());
            return safeWrite(entry).then(Mono.error(failure));
        });
    }

    static Mono<byte[]> readBody(ServerHttpRequest request) {
        return DataBufferUtils.join(request.getBody(), MAX_BODY_BYTES)
                .map(MockRuntimeController::readAndRelease)
                .defaultIfEmpty(new byte[0]);
    }

    private Mono<Void> safeWrite(RequestLogEntry entry) {
        return requestLogWriter.write(entry)
                .doOnError(failure -> LOG.error(
                        "Request Log write failed: mockRequestId={}, traceId={}",
                        entry.mockRequestId(), entry.traceId(), failure))
                .onErrorResume(failure -> Mono.empty());
    }

    private ResponseEntity<byte[]> toResponse(
            RuntimeExecution execution,
            long activationVersion,
            String mockRequestId,
            String traceId) {
        HttpHeaders headers = new HttpHeaders();
        execution.headers().forEach(headers::set);
        headers.set("X-Mock-Request-Id", mockRequestId);
        headers.set("X-Mock-Scenario-Id", execution.scenarioId());
        headers.set("X-Mock-Release-Id", execution.releaseId());
        headers.set("X-Mock-Activation-Version", Long.toString(activationVersion));
        headers.set("X-Trace-Id", traceId);
        return ResponseEntity.status(execution.status()).headers(headers).body(execution.body());
    }

    private static byte[] readAndRelease(DataBuffer buffer) {
        byte[] bytes = new byte[buffer.readableByteCount()];
        buffer.read(bytes);
        DataBufferUtils.release(buffer);
        return bytes;
    }

    private static String requiredHeader(ServerHttpRequest request, String name) {
        String value = request.getHeaders().getFirst(name);
        if (value == null || value.trim().isEmpty()) {
            throw new PlatformException(ErrorCode.INVALID_REQUEST, name + " is required");
        }
        return value;
    }

    private static String headerOrGenerate(ServerHttpRequest request, String name, String prefix) {
        String value = request.getHeaders().getFirst(name);
        if (value == null || value.isBlank()) {
            return prefix + UUID.randomUUID();
        }
        if (value.length() > 64 || !value.matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,63}")) {
            throw new PlatformException(ErrorCode.INVALID_REQUEST, name + " is invalid");
        }
        return value;
    }

    private static Map<String, List<String>> copy(Map<String, ? extends List<String>> source) {
        java.util.LinkedHashMap<String, List<String>> result = new java.util.LinkedHashMap<>();
        source.forEach((key, values) -> result.put(key, List.copyOf(values)));
        return result;
    }

    private static long elapsedMillis(long started) {
        return Math.max(0, (System.nanoTime() - started) / 1_000_000);
    }
}
