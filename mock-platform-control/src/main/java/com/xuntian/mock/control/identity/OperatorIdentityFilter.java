package com.xuntian.mock.control.identity;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xuntian.mock.common.ApiResponse;
import com.xuntian.mock.common.ErrorCode;
import com.xuntian.mock.common.PlatformException;
import com.xuntian.mock.common.RequestIds;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
@Component
public final class OperatorIdentityFilter extends OncePerRequestFilter {

    private static final Logger LOGGER = LoggerFactory.getLogger(OperatorIdentityFilter.class);
    public static final String REQUEST_ID_ATTRIBUTE = OperatorIdentityFilter.class.getName() + ".requestId";
    private static final String REQUEST_ID_HEADER = "X-Request-Id";
    private final OperatorIdentityVerifier identityVerifier;
    private final ObjectMapper objectMapper;

    public OperatorIdentityFilter(OperatorIdentityVerifier identityVerifier, ObjectMapper objectMapper) {
        this.identityVerifier = identityVerifier;
        this.objectMapper = objectMapper;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {
        String requestId = request.getHeader(REQUEST_ID_HEADER);
        if (requestId == null || requestId.isBlank()) {
            requestId = RequestIds.generate();
        }
        request.setAttribute(REQUEST_ID_ATTRIBUTE, requestId);
        response.setHeader(REQUEST_ID_HEADER, requestId);
        long started = System.nanoTime();
        String previousRequestId = MDC.get("requestId");
        MDC.put("requestId", safeLogValue(requestId));
        try {
            if (isPublicPath(request.getRequestURI())) {
                filterChain.doFilter(request, response);
                return;
            }
            OperatorContext operator;
            try {
                operator = identityVerifier.verify(request, requestId);
            } catch (PlatformException failure) {
                LOGGER.warn(
                        "Control request rejected requestId={} method={} path={} code={}",
                        safeLogValue(requestId), request.getMethod(), request.getRequestURI(),
                        failure.errorCode().name());
                writeFailure(response, failure.errorCode(), failure.getMessage(), requestId);
                return;
            }

            OperatorContextHolder.set(operator);
            try {
                filterChain.doFilter(request, response);
            } finally {
                OperatorContextHolder.clear();
            }
        } finally {
            logCompletion(request, response, requestId, started);
            if (previousRequestId == null) {
                MDC.remove("requestId");
            } else {
                MDC.put("requestId", previousRequestId);
            }
        }
    }

    private boolean isPublicPath(String requestUri) {
        return "/api/platform/health".equals(requestUri)
                || requestUri.startsWith("/api/internal/v1/")
                || "/actuator".equals(requestUri)
                || requestUri.startsWith("/actuator/");
    }

    private void logCompletion(
            HttpServletRequest request,
            HttpServletResponse response,
            String requestId,
            long started) {
        long durationMs = Math.max(0L, (System.nanoTime() - started) / 1_000_000L);
        String message = "Control request completed requestId={} method={} path={} status={} durationMs={}";
        LOGGER.debug(message, safeLogValue(requestId), request.getMethod(), request.getRequestURI(),
                response.getStatus(), durationMs);
    }

    private static String safeLogValue(String value) {
        String safe = value == null ? "" : value.replace('\r', '_').replace('\n', '_');
        return safe.length() <= 64 ? safe : safe.substring(0, 64);
    }

    private void writeFailure(
            HttpServletResponse response,
            ErrorCode errorCode,
            String message,
            String requestId) throws IOException {
        response.setStatus(errorCode.httpStatus());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        objectMapper.writeValue(response.getOutputStream(), ApiResponse.failure(errorCode, message, requestId));
    }
}
