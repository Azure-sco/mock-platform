package com.xuntian.mock.control.callback;

import java.net.URI;
import java.util.Map;

public interface CallbackDispatcher {

    PreparedCallback prepare(CallbackTaskRecord task);

    SendOutcome send(PreparedCallback callback);

    record PreparedCallback(
            String taskId,
            String deliveryId,
            URI url,
            String method,
            Map<String, String> headers,
            byte[] payload) {
        public PreparedCallback {
            headers = Map.copyOf(headers);
            payload = payload.clone();
        }
        @Override public byte[] payload() { return payload.clone(); }
    }

    record SendOutcome(
            Integer httpStatus,
            boolean success,
            String result,
            String certainty,
            String errorMasked,
            long durationMs) {
        public static SendOutcome confirmed(int status, long durationMs) {
            return new SendOutcome(status, status >= 200 && status < 300,
                    status >= 200 && status < 300 ? "HTTP_SUCCESS" : "HTTP_ERROR",
                    "CONFIRMED_RESPONSE", status >= 200 && status < 300 ? null : "Callback returned HTTP " + status,
                    durationMs);
        }
        public static SendOutcome unknown(String error, long durationMs) {
            return new SendOutcome(null, false, "TRANSPORT_UNKNOWN", "UNKNOWN", error, durationMs);
        }
    }

    final class PreparationException extends RuntimeException {
        public PreparationException(String message) { super(message); }
        public PreparationException(String message, Throwable cause) { super(message, cause); }
    }
}
