package com.xuntian.mock.control.outbox;

public interface ConfigPublisherAdapter {

    /**
     * Atomically publishes the supplied bytes without regenerating or re-signing them. Implementations must be
     * idempotent by aggregateId and complete within the configured Outbox lease (30 seconds by default).
     */
    void publish(String targetType, String targetNamespace, String aggregateId, byte[] canonicalPayload, String checksum);
}
