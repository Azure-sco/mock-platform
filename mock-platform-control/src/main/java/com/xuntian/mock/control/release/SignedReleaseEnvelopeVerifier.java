package com.xuntian.mock.control.release;

import org.springframework.stereotype.Component;

@Component
public final class SignedReleaseEnvelopeVerifier implements ReleaseEnvelopeVerifier {

    private final ReleaseSnapshotCompiler compiler;

    public SignedReleaseEnvelopeVerifier(ReleaseSnapshotCompiler compiler) {
        this.compiler = compiler;
    }

    @Override
    public void verify(byte[] envelopeBytes, String expectedReleaseId, String expectedChecksum) {
        compiler.verifyEnvelope(envelopeBytes, expectedReleaseId, expectedChecksum);
    }
}
