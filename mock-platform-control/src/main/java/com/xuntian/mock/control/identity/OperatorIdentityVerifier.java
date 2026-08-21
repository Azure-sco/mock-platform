package com.xuntian.mock.control.identity;

import jakarta.servlet.http.HttpServletRequest;

public interface OperatorIdentityVerifier {

    OperatorContext verify(HttpServletRequest request, String requestId);
}
