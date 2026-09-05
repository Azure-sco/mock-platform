package com.xuntian.mock.runtime.release;

public record ActiveReleasePointer(
        String releaseId,
        long activationVersion,
        String snapshotChecksum,
        String signatureKeyId) {

    public ActiveReleasePointer {
        requireSafe(releaseId, 64, "releaseId");
        if (activationVersion < 1) {
            throw new IllegalArgumentException("activationVersion must be positive");
        }
        if (snapshotChecksum == null || !snapshotChecksum.matches("[a-f0-9]{64}")) {
            throw new IllegalArgumentException("snapshotChecksum must be a lowercase SHA-256 value");
        }
        requireSafe(signatureKeyId, 128, "signatureKeyId");
    }

    private static void requireSafe(String value, int maxLength, String name) {
        if (value == null || value.length() > maxLength
                || !value.matches("[A-Za-z0-9][A-Za-z0-9._:-]*")) {
            throw new IllegalArgumentException(name + " is invalid");
        }
    }
}
