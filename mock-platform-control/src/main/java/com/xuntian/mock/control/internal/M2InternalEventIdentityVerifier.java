package com.xuntian.mock.control.internal;

import jakarta.servlet.http.HttpServletRequest;

public interface M2InternalEventIdentityVerifier {

    void verify(HttpServletRequest request, String bodyChecksum);
}
