package com.xuntian.mock.control.security;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;

class LocalPayloadSignerTest {

    @Test
    void signsWithJdk8CompatibleRsaAlgorithm() throws Exception {
        LocalPayloadSigner signer = new LocalPayloadSigner();
        byte[] payload = "canonical-payload".getBytes(StandardCharsets.UTF_8);

        PayloadSigner.SignatureValue signed = signer.sign(payload);

        assertThat(signed.algorithm()).isEqualTo("SHA256withRSA");
        assertThat(signed.keyId()).isEqualTo("local-ephemeral-rsa-2048");
        Signature verifier = Signature.getInstance(signed.algorithm());
        verifier.initVerify(KeyFactory.getInstance("RSA").generatePublic(
                new X509EncodedKeySpec(signer.publicKey())));
        verifier.update(payload);
        assertThat(verifier.verify(Base64.getDecoder().decode(signed.signature()))).isTrue();
    }
}
