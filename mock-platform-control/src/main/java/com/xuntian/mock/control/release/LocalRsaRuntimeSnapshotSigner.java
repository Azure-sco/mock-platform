package com.xuntian.mock.control.release;

import com.xuntian.mock.common.ErrorCode;
import com.xuntian.mock.common.PlatformException;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;

import java.nio.file.Files;
import java.nio.file.Path;
import java.io.IOException;
import java.security.KeyFactory;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;

import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;

@Component
@Profile({"local", "test"})
public final class LocalRsaRuntimeSnapshotSigner implements RuntimeSnapshotSigner {

    public static final String ALGORITHM = "SHA256withRSA";
    private final String keyId;
    private final KeyPair keyPair;

    public LocalRsaRuntimeSnapshotSigner() {
        this.keyId = "local-ephemeral-rsa-2048";
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            this.keyPair = generator.generateKeyPair();
        } catch (GeneralSecurityException failure) {
            throw new IllegalStateException("Local RSA signer cannot be initialized", failure);
        }
    }

    @Autowired
    public LocalRsaRuntimeSnapshotSigner(
            Environment environment,
            @Value("${mock.local-published.private-key-file:}") String privateKeyFile,
            @Value("${mock.local-published.public-key-file:}") String publicKeyFile) {
        if (!environment.acceptsProfiles(Profiles.of("local-published"))) {
            LocalRsaRuntimeSnapshotSigner ephemeral = new LocalRsaRuntimeSnapshotSigner();
            this.keyPair = ephemeral.keyPair;
            this.keyId = ephemeral.keyId;
            return;
        }
        this.keyId = "local-published-rsa-2048";
        if (privateKeyFile.isBlank() || publicKeyFile.isBlank()) {
            throw new IllegalStateException("Local publication requires persistent RSA key files");
        }
        try {
            KeyFactory factory = KeyFactory.getInstance("RSA");
            this.keyPair = new KeyPair(
                    factory.generatePublic(new X509EncodedKeySpec(Files.readAllBytes(Path.of(publicKeyFile)))),
                    factory.generatePrivate(new PKCS8EncodedKeySpec(Files.readAllBytes(Path.of(privateKeyFile)))));
            if (((java.security.interfaces.RSAPublicKey) keyPair.getPublic()).getModulus().bitLength() < 2048) {
                throw new IllegalStateException("Local publication requires RSA keys of at least 2048 bits");
            }
            byte[] probe = "local-publication-key-check".getBytes(java.nio.charset.StandardCharsets.UTF_8);
            SignatureValue signed = sign(probe);
            verify(probe, signed.signature(), signed.keyId(), signed.algorithm());
        } catch (IOException | GeneralSecurityException failure) {
            throw new IllegalStateException("Local publication RSA key files cannot be loaded", failure);
        }
    }

    @Override
    public SignatureValue sign(byte[] canonicalSnapshot) {
        try {
            Signature signer = Signature.getInstance(ALGORITHM);
            signer.initSign(keyPair.getPrivate());
            signer.update(canonicalSnapshot);
            return new SignatureValue(signer.sign(), keyId, ALGORITHM);
        } catch (GeneralSecurityException failure) {
            throw new PlatformException(ErrorCode.INTERNAL_ERROR, "Runtime Snapshot signing failed", failure);
        }
    }

    @Override
    public void verify(byte[] canonicalSnapshot, byte[] signature, String keyId, String algorithm) {
        if (!this.keyId.equals(keyId) || !ALGORITHM.equals(algorithm)) {
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
