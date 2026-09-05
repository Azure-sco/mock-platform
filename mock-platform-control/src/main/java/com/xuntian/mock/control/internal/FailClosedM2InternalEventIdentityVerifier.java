package com.xuntian.mock.control.internal;

import com.xuntian.mock.common.ErrorCode;
import com.xuntian.mock.common.PlatformException;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Component
@Profile("!local & !test")
public final class FailClosedM2InternalEventIdentityVerifier implements M2InternalEventIdentityVerifier {

    @Override
    public void verify(HttpServletRequest request, String bodyChecksum) {
        throw new PlatformException(ErrorCode.UNAUTHORIZED, "Production internal service identity is not configured");
    }
}
