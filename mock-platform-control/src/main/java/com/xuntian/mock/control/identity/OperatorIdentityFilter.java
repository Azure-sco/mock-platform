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
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
@Component
public final class OperatorIdentityFilter extends OncePerRequestFilter {

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

        if (isPublicPath(request.getRequestURI())) {
            filterChain.doFilter(request, response);
            return;
        }
        OperatorContext operator;
        try {
            operator = identityVerifier.verify(request, requestId);
        } catch (PlatformException failure) {
            writeFailure(response, failure.errorCode(), failure.getMessage(), requestId);
            return;
        }

        OperatorContextHolder.set(operator);
        try {
            filterChain.doFilter(request, response);
        } finally {
            OperatorContextHolder.clear();
        }
    }

    private boolean isPublicPath(String requestUri) {
        return "/api/platform/health".equals(requestUri)
                || requestUri.startsWith("/api/internal/v1/")
                || "/actuator".equals(requestUri)
                || requestUri.startsWith("/actuator/");
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
