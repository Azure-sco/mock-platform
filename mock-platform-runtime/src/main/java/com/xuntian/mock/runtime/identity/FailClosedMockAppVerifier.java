package com.xuntian.mock.runtime.identity;

import com.xuntian.mock.common.ErrorCode;
import com.xuntian.mock.common.PlatformException;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;

@Component
@Profile("!local & !test")
public final class FailClosedMockAppVerifier implements MockAppVerifier {

    @Override
    public RuntimeIdentity verify(HttpHeaders headers) {
        throw new PlatformException(
                ErrorCode.MOCK_APP_UNAUTHORIZED,
                "No production Mock application identity verifier is configured");
    }
}
