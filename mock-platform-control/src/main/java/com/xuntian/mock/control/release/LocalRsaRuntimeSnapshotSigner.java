package com.xuntian.mock.control.release;

import com.xuntian.mock.common.ErrorCode;
import com.xuntian.mock.common.PlatformException;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;

@Component
@Profile({"local", "test"})
public final class LocalRsaRuntimeSnapshotSigner implements RuntimeSnapshotSigner {

    public static final String ALGORITHM = "SHA256withRSA";
    private static final String KEY_ID = "local-ephemeral-rsa-2048";
    private final KeyPair keyPair;

    public LocalRsaRuntimeSnapshotSigner() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            this.keyPair = generator.generateKeyPair();
        } catch (GeneralSecurityException failure) {
            throw new IllegalStateException("Local RSA signer cannot be initialized", failure);
        }
    }

    @Override
    public SignatureValue sign(byte[] canonicalSnapshot) {
        try {
            Signature signer = Signature.getInstance(ALGORITHM);
            signer.initSign(keyPair.getPrivate());
            signer.update(canonicalSnapshot);
            return new SignatureValue(signer.sign(), KEY_ID, ALGORITHM);
        } catch (GeneralSecurityException failure) {
            throw new PlatformException(ErrorCode.INTERNAL_ERROR, "Runtime Snapshot signing failed", failure);
        }
    }

    @Override
    public void verify(byte[] canonicalSnapshot, byte[] signature, String keyId, String algorithm) {
        if (!KEY_ID.equals(keyId) || !ALGORITHM.equals(algorithm)) {
            throw new PlatformException(ErrorCode.MOCK_RELEASE_UNAVAILABLE, "Snapshot signing key is unknown");
        }
        try {
            Signature verifier = Signature.getInstance(ALGORITHM);
            verifier.initVerify(keyPair.getPublic());
            verifier.update(canonicalSnapshot);
            if (!verifier.verify(signature)) {
                throw new PlatformException(ErrorCode.MOCK_RELEASE_UNAVAILABLE, "Snapshot signature is invalid");
            }
        } catch (GeneralSecurityException failure) {
            throw new PlatformException(ErrorCode.MOCK_RELEASE_UNAVAILABLE, "Snapshot verification failed", failure);
        }
    }
}
