package com.xuntian.mock.client.core.security;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class HeaderSanitizerTest {

    @Test
    void mockCopyKeepsAllowedBusinessHeadersAndRemovesCredentialsCaseInsensitively() {
        Map<String, List<String>> original = new LinkedHashMap<String, List<String>>();
        original.put("Authorization", Collections.singletonList("Bearer real-secret"));
        original.put("cookie", Collections.singletonList("session=real-secret"));
        original.put("X-App-Secret", Collections.singletonList("real-secret"));
        original.put("X-Signature", Collections.singletonList("real-signature"));
        original.put("Content-Type", Collections.singletonList("application/json"));
        original.put("domain", Collections.singletonList("settlement"));
        original.put("X-Unlisted", Collections.singletonList("must-not-leak"));

        Map<String, List<String>> sanitized = HeaderSanitizer.sanitize(
                original,
                Arrays.asList("domain", "X-App-Secret"),
                Collections.singletonList("X-Provider-Credential"));

        assertThat(sanitized).containsEntry("Content-Type", Collections.singletonList("application/json"));
        assertThat(sanitized).containsEntry("domain", Collections.singletonList("settlement"));
        assertThat(sanitized.keySet()).doesNotContain("Authorization", "cookie", "X-App-Secret", "X-Signature", "X-Unlisted");
        assertThat(original).containsEntry("Authorization", Collections.singletonList("Bearer real-secret"));
    }

    @Test
    void providerDenylistWinsOverBusinessAllowlist() {
        Map<String, List<String>> original = Collections.singletonMap(
                "X-Provider-Credential", Collections.singletonList("secret"));

        Map<String, List<String>> sanitized = HeaderSanitizer.sanitize(
                original,
                Collections.singletonList("X-Provider-Credential"),
                Collections.singletonList("x-provider-credential"));

        assertThat(sanitized).isEmpty();
    }
}
