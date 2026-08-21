package com.xuntian.mock.client.core.security;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public final class HeaderSanitizer {

    private static final Set<String> ALWAYS_ALLOWED = lowerCaseSet(Arrays.asList("Content-Type", "Accept"));
    private static final Set<String> DEFAULT_DENIED = lowerCaseSet(Arrays.asList(
            "Authorization",
            "Proxy-Authorization",
            "Cookie",
            "Set-Cookie",
            "X-Api-Key",
            "X-App-Secret",
            "Signature",
            "X-Signature",
            "X-Third-Party-Signature"));

    private HeaderSanitizer() {
    }

    public static Map<String, List<String>> sanitize(
            Map<String, ? extends Collection<String>> original,
            Collection<String> allowedBusinessHeaders,
            Collection<String> additionalSensitiveHeaders) {
        Set<String> allowed = lowerCaseSet(allowedBusinessHeaders);
        allowed.addAll(ALWAYS_ALLOWED);
        Set<String> denied = lowerCaseSet(additionalSensitiveHeaders);
        denied.addAll(DEFAULT_DENIED);

        Map<String, List<String>> sanitized = new LinkedHashMap<String, List<String>>();
        for (Map.Entry<String, ? extends Collection<String>> entry : original.entrySet()) {
            String normalizedName = normalize(entry.getKey());
            if (allowed.contains(normalizedName) && !denied.contains(normalizedName)) {
                sanitized.put(entry.getKey(), new ArrayList<String>(entry.getValue()));
            }
        }
        return sanitized;
    }

    private static Set<String> lowerCaseSet(Collection<String> names) {
        Set<String> normalized = new HashSet<String>();
        for (String name : names) {
            normalized.add(normalize(name));
        }
        return normalized;
    }

    private static String normalize(String name) {
        return name.toLowerCase(Locale.ROOT);
    }
}
