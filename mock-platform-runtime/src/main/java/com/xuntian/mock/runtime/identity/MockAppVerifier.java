package com.xuntian.mock.runtime.identity;

import org.springframework.http.HttpHeaders;

public interface MockAppVerifier {

    RuntimeIdentity verify(HttpHeaders headers);
}
