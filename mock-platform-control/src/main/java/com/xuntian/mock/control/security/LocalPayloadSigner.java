package com.xuntian.mock.control.security;

import com.xuntian.mock.common.ErrorCode;
import com.xuntian.mock.common.PlatformException;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.util.Base64;

@Component
@Profile({"local", "test"})
public final class LocalPayloadSigner implements PayloadSigner {

    private static final String ALGORITHM = "SHA256withRSA";

    private final KeyPair keyPair;

    public LocalPayloadSigner() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            keyPair = generator.generateKeyPair();
        } catch (GeneralSecurityException failure) {
            throw new IllegalStateException("Local RSA signer cannot be initialized", failure);
        }
    }

    @Override
    public SignatureValue sign(byte[] canonicalPayload) {
        try {
            Signature signer = Signature.getInstance(ALGORITHM);
            signer.initSign(keyPair.getPrivate());
            signer.update(canonicalPayload);
            return new SignatureValue(
                    Base64.getEncoder().encodeToString(signer.sign()),
                    "local-ephemeral-rsa-2048",
                    ALGORITHM);
        } catch (GeneralSecurityException failure) {
            throw new PlatformException(ErrorCode.INTERNAL_ERROR, "Payload signing failed", failure);
        }
    }

    byte[] publicKey() {
        return keyPair.getPublic().getEncoded();
    }
}
