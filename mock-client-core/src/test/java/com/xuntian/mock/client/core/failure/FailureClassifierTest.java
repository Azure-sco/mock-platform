package com.xuntian.mock.client.core.failure;

import org.junit.jupiter.api.Test;

import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;

import static org.assertj.core.api.Assertions.assertThat;

class FailureClassifierTest {

    @Test
    void classifiesOnlyProvableConnectionFailuresAsBeforeConnect() {
        assertThat(FailureClassifier.classify(new UnknownHostException("runtime.invalid")))
                .isEqualTo(FailureClassification.BEFORE_CONNECT);
        assertThat(FailureClassifier.classify(new IllegalStateException(new ConnectException("refused"))))
                .isEqualTo(FailureClassification.BEFORE_CONNECT);
    }

    @Test
    void treatsReadTimeoutAndUnknownFailuresAsPossiblyDelivered() {
        assertThat(FailureClassifier.classify(new SocketTimeoutException("read timed out")))
                .isEqualTo(FailureClassification.POSSIBLY_DELIVERED);
        assertThat(FailureClassifier.classify(new IllegalStateException("unknown")))
                .isEqualTo(FailureClassification.POSSIBLY_DELIVERED);
    }
}
