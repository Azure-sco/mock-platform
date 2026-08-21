package com.xuntian.mock.runtime.web;

import com.xuntian.mock.common.ErrorCode;
import com.xuntian.mock.common.PlatformException;
import com.xuntian.mock.runtime.capture.RuntimeRequestCapture;
import com.xuntian.mock.runtime.dispatch.FixedMockResponse;
import com.xuntian.mock.runtime.dispatch.M0FixedResponseDispatcher;
import com.xuntian.mock.runtime.identity.MockAppVerifier;
import com.xuntian.mock.runtime.identity.RuntimeIdentity;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.util.UUID;

@RestController
public final class MockRuntimeController {

    static final int MAX_BODY_BYTES = 1024 * 1024;
    private final MockAppVerifier verifier;
    private final RuntimeRequestCapture capture;
    private final M0FixedResponseDispatcher dispatcher;

    public MockRuntimeController(
            MockAppVerifier verifier,
            RuntimeRequestCapture capture,
            M0FixedResponseDispatcher dispatcher) {
        this.verifier = verifier;
        this.capture = capture;
        this.dispatcher = dispatcher;
    }

    @RequestMapping("/**")
    public Mono<ResponseEntity<Object>> handle(ServerHttpRequest request) {
        RuntimeIdentity identity = verifier.verify(request.getHeaders());
        String provider = requiredHeader(request, "X-Mock-Provider");
        String api = requiredHeader(request, "X-Mock-Api");
        String requestId = request.getHeaders().getFirst("X-Mock-Request-Id");
        if (requestId == null || requestId.trim().isEmpty()) {
            requestId = "mr-" + UUID.randomUUID();
        }
        String finalRequestId = requestId;
        return readBody(request).map(body -> {
            capture.record(identity, request, provider, api, finalRequestId, body.length);
            FixedMockResponse response = dispatcher.dispatch(provider, api, finalRequestId);
            return ResponseEntity.status(response.status())
                    .contentType(MediaType.parseMediaType(response.contentType()))
                    .header("X-Mock-Request-Id", finalRequestId)
                    .body(response.body());
        });
    }

    static Mono<byte[]> readBody(ServerHttpRequest request) {
        return DataBufferUtils.join(request.getBody(), MAX_BODY_BYTES)
                .map(MockRuntimeController::readAndRelease)
                .defaultIfEmpty(new byte[0]);
    }

    private static byte[] readAndRelease(DataBuffer buffer) {
        byte[] bytes = new byte[buffer.readableByteCount()];
        buffer.read(bytes);
        DataBufferUtils.release(buffer);
        return bytes;
    }

    private String requiredHeader(ServerHttpRequest request, String name) {
        String value = request.getHeaders().getFirst(name);
        if (value == null || value.trim().isEmpty()) {
            throw new PlatformException(ErrorCode.INVALID_REQUEST, name + " is required");
        }
        return value;
    }
}
