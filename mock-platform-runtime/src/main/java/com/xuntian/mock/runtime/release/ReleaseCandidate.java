package com.xuntian.mock.runtime.release;

public record ReleaseCandidate(ActiveReleasePointer pointer, byte[] envelopeBytes) {

    public ReleaseCandidate {
        if (pointer == null || envelopeBytes == null) {
            throw new IllegalArgumentException("Release candidate fields are required");
        }
        envelopeBytes = envelopeBytes.clone();
    }

    @Override
    public byte[] envelopeBytes() {
        return envelopeBytes.clone();
    }
}
