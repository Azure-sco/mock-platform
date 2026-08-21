package com.xuntian.mock.control.identity;

import java.util.Set;

public record OperatorContext(String operatorId, Set<String> roles, String requestId) {

    public OperatorContext {
        roles = Set.copyOf(roles);
    }
}
