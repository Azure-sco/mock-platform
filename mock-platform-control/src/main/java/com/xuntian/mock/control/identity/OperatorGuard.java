package com.xuntian.mock.control.identity;

import com.xuntian.mock.common.ErrorCode;
import com.xuntian.mock.common.PlatformException;
import org.springframework.stereotype.Component;

import java.util.Arrays;

@Component
public final class OperatorGuard {

    public OperatorContext requireAnyRole(String... acceptedRoles) {
        OperatorContext context = OperatorContextHolder.current()
                .orElseThrow(() -> new PlatformException(ErrorCode.UNAUTHORIZED, "Operator identity is required"));
        boolean allowed = Arrays.stream(acceptedRoles).anyMatch(context.roles()::contains);
        if (!allowed) {
            throw new PlatformException(ErrorCode.FORBIDDEN, "Operator role is not allowed");
        }
        return context;
    }
}
