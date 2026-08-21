package com.xuntian.mock.client.core.failure;

import java.net.ConnectException;
import java.net.UnknownHostException;

public final class FailureClassifier {

    private FailureClassifier() {
    }

    public static FailureClassification classify(Throwable failure) {
        Throwable current = failure;
        while (current != null) {
            if (current instanceof UnknownHostException || current instanceof ConnectException) {
                return FailureClassification.BEFORE_CONNECT;
            }
            current = current.getCause();
        }
        return FailureClassification.POSSIBLY_DELIVERED;
    }
}
