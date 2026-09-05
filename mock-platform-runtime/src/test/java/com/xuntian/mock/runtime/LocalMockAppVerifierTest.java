package com.xuntian.mock.runtime;

import com.xuntian.mock.common.ErrorCode;
import com.xuntian.mock.common.PlatformException;
import com.xuntian.mock.runtime.identity.LocalMockAppVerifier;
import com.xuntian.mock.runtime.identity.RuntimeIdentity;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LocalMockAppVerifierTest {

    @Test
    void bindsLocalTenantAndTestAccountAfterTokenVerification() {
        LocalMockAppVerifier verifier = verifier();
        HttpHeaders headers = headers();
        headers.set("X-Mock-Tenant", "tenant-a");
        headers.set("X-Mock-Test-Account", "tester-01");

        RuntimeIdentity identity = verifier.verify(headers);

        assertThat(identity.tenantCode()).isEqualTo("tenant-a");
        assertThat(identity.testAccount()).isEqualTo("tester-01");
    }

    @Test
    void rejectsUnsafeSelfReportedContextEvenInLocalProfile() {
        LocalMockAppVerifier verifier = verifier();
        HttpHeaders headers = headers();
        headers.set("X-Mock-Tenant", "../other-tenant");

        assertThatThrownBy(() -> verifier.verify(headers))
                .isInstanceOfSatisfying(PlatformException.class,
                        failure -> assertThat(failure.errorCode()).isEqualTo(ErrorCode.MOCK_CONTEXT_INVALID));
    }

    private LocalMockAppVerifier verifier() {
        RuntimeProperties properties = new RuntimeProperties();
        properties.setEnvironment("TEST");
        properties.setLocalAppTokens(Map.of("token", "app"));
        return new LocalMockAppVerifier(properties);
    }

    private HttpHeaders headers() {
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.AUTHORIZATION, "MockApp token");
        return headers;
    }
}
