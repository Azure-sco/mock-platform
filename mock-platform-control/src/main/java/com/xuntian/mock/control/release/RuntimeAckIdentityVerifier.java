package com.xuntian.mock.control.release;

import jakarta.servlet.http.HttpServletRequest;

public interface RuntimeAckIdentityVerifier {

    String verify(HttpServletRequest request);
}
