package com.xuntian.mock.runtime;

import com.xuntian.mock.common.ErrorCode;
import com.xuntian.mock.common.PlatformException;
import com.xuntian.mock.runtime.identity.FailClosedMockAppVerifier;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FailClosedMockAppVerifierTest {

    @Test
    void rejectsConfiguredLookingTokenOutsideLocalAndTestProfiles() {
        FailClosedMockAppVerifier verifier = new FailClosedMockAppVerifier();

        assertThatThrownBy(() -> verifier.verify(HttpHeaders.readOnlyHttpHeaders(new HttpHeaders())))
                .isInstanceOfSatisfying(PlatformException.class, failure ->
                        assertThat(failure.errorCode()).isEqualTo(ErrorCode.MOCK_APP_UNAUTHORIZED));
    }
}
