package com.xuntian.mock.common;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ApiResponseTest {

    @Test
    void exposesStableSuccessAndErrorEnvelope() {
        ApiResponse<String> success = ApiResponse.success("ok", "req-1");
        ApiResponse<Void> failure = ApiResponse.failure(ErrorCode.FORBIDDEN, "denied", "req-2");

        assertThat(success.isSuccess()).isTrue();
        assertThat(success.getCode()).isEqualTo("OK");
        assertThat(success.getData()).isEqualTo("ok");
        assertThat(failure.isSuccess()).isFalse();
        assertThat(failure.getCode()).isEqualTo("FORBIDDEN");
        assertThat(failure.getRequestId()).isEqualTo("req-2");
    }
}
