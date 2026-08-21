package com.xuntian.mock.fakereal.capture;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicInteger;

@Component
public final class FakeRealRequestCapture {

    private final AtomicReference<CapturedRealRequest> last = new AtomicReference<>();
    private final AtomicInteger count = new AtomicInteger();

    public void record(HttpServletRequest request) {
        count.incrementAndGet();
        last.set(new CapturedRealRequest(
                request.getRequestURI(),
                request.getHeader("Authorization"),
                request.getHeader("Cookie"),
                request.getHeader("X-Signature"),
                request.getHeader("domain")));
    }

    public CapturedRealRequest last() {
        return last.get();
    }

    public int count() {
        return count.get();
    }
}
