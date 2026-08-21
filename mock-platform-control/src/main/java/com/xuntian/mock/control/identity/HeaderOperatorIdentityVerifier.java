package com.xuntian.mock.control.identity;

import com.xuntian.mock.common.ErrorCode;
import com.xuntian.mock.common.PlatformException;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.stream.Collectors;

@Component
@Profile({"local", "test"})
public final class HeaderOperatorIdentityVerifier implements OperatorIdentityVerifier {

    private static final String OPERATOR_ID_HEADER = "X-Operator-Id";
    private static final String OPERATOR_ROLES_HEADER = "X-Operator-Roles";

    @Override
    public OperatorContext verify(HttpServletRequest request, String requestId) {
        String operatorId = request.getHeader(OPERATOR_ID_HEADER);
        if (operatorId == null || operatorId.isBlank()) {
            throw new PlatformException(ErrorCode.UNAUTHORIZED, "Operator identity is required");
        }
        return new OperatorContext(operatorId.trim(), roles(request), requestId);
    }

    private Set<String> roles(HttpServletRequest request) {
        String rawRoles = request.getHeader(OPERATOR_ROLES_HEADER);
        if (rawRoles == null || rawRoles.isBlank()) {
            return Set.of();
        }
        return Arrays.stream(rawRoles.split(","))
                .map(String::trim)
                .filter(role -> !role.isEmpty())
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }
}
