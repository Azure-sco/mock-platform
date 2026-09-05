package com.xuntian.mock.runtime.release;

public record SignedRuntimeSnapshotEnvelope(
        PublishedSnapshotDefinition snapshot,
        String checksum,
        String signature,
        String signatureKeyId,
        String signatureAlgorithm) {
}
