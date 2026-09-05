package com.xuntian.mock.control.release;

import com.xuntian.mock.common.ErrorCode;
import com.xuntian.mock.common.PlatformException;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Component
@Profile("!local & !test")
public final class FailClosedRuntimeAckIdentityVerifier implements RuntimeAckIdentityVerifier {

    @Override
    public String verify(HttpServletRequest request) {
        throw new PlatformException(
                ErrorCode.UNAUTHORIZED,
                "Production Runtime service-identity verifier is not configured");
    }
}
