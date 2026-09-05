package com.xuntian.mock.control.release;

public interface ReleaseEnvelopeVerifier {
    void verify(byte[] envelopeBytes, String expectedReleaseId, String expectedChecksum);
}
