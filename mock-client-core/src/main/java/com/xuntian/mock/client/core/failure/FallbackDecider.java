package com.xuntian.mock.client.core.failure;

import java.util.Locale;
import java.util.Set;

public final class FallbackDecider {

    private FallbackDecider() {
    }

    public static boolean mayFallbackReal(
            String environment,
            String originalHost,
            Set<String> allowedRealHosts,
            boolean replayable,
            FailureClassification classification) {
        if ("PROD".equalsIgnoreCase(environment) || "PRODUCTION".equalsIgnoreCase(environment)) {
            return false;
        }
        if (!replayable || classification != FailureClassification.BEFORE_CONNECT || originalHost == null) {
            return false;
        }
        String normalizedHost = originalHost.toLowerCase(Locale.ROOT);
        for (String allowedHost : allowedRealHosts) {
            if (normalizedHost.equals(allowedHost.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }
}
