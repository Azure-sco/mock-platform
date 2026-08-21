package com.xuntian.mock.control.identity;

import com.xuntian.mock.common.ErrorCode;
import com.xuntian.mock.common.PlatformException;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Component
@Profile("!local & !test")
public final class FailClosedOperatorIdentityVerifier implements OperatorIdentityVerifier {

    @Override
    public OperatorContext verify(HttpServletRequest request, String requestId) {
        throw new PlatformException(
                ErrorCode.UNAUTHORIZED,
                "Management identity provider is not configured");
    }
}
