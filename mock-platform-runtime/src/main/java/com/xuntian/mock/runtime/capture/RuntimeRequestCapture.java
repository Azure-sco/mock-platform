package com.xuntian.mock.runtime.capture;

import com.xuntian.mock.runtime.identity.RuntimeIdentity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

@Component
public final class RuntimeRequestCapture {

    private final AtomicReference<CapturedRequest> latest = new AtomicReference<>();

    public void record(
            RuntimeIdentity identity,
            ServerHttpRequest request,
            String provider,
            String api,
            String requestId,
            int bodyBytes) {
        Set<String> names = Collections.unmodifiableSet(new LinkedHashSet<>(request.getHeaders().keySet()));
        latest.set(new CapturedRequest(
                identity.appCode(),
                identity.environment(),
                request.getMethod().name(),
                request.getURI().getRawPath(),
                request.getURI().getRawQuery(),
                provider,
                api,
                requestId,
                bodyBytes,
                authorizationScheme(request.getHeaders()),
                names));
    }

    public CapturedRequest last() {
        return latest.get();
    }

    private String authorizationScheme(HttpHeaders headers) {
        String authorization = headers.getFirst(HttpHeaders.AUTHORIZATION);
        if (authorization == null) {
            return null;
        }
        int separator = authorization.indexOf(' ');
        return separator < 0 ? authorization : authorization.substring(0, separator);
    }
}
