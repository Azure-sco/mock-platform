package com.xuntian.mock.control.release;

public interface RuntimeSnapshotSigner {

    SignatureValue sign(byte[] canonicalSnapshot);

    void verify(byte[] canonicalSnapshot, byte[] signature, String keyId, String algorithm);

    record SignatureValue(byte[] signature, String keyId, String algorithm) {
        public SignatureValue {
            signature = signature.clone();
        }

        @Override
        public byte[] signature() {
            return signature.clone();
        }
    }
}
