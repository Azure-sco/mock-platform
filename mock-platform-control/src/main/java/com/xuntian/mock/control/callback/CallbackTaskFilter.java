package com.xuntian.mock.control.callback;

import java.time.Instant;

public record CallbackTaskFilter(
        String status,
        String providerCode,
        String apiCode,
        Long flowInstanceId,
        Instant createdFrom,
        Instant createdTo) {
}
