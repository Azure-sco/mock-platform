package com.xuntian.mock.control.release;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.env.MockEnvironment;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPairGenerator;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LocalRsaRuntimeSnapshotSignerTest {
    @TempDir Path directory;

    @Test
    void persistentKeysVerifyReleasesAfterRestartAndRejectTampering() throws Exception {
        var generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        var keys = generator.generateKeyPair();
        Path privateFile = directory.resolve("private.der");
        Path publicFile = directory.resolve("public.der");
        Files.write(privateFile, keys.getPrivate().getEncoded());
        Files.write(publicFile, keys.getPublic().getEncoded());
        var env = new MockEnvironment().withProperty("spring.profiles.active", "local,local-published");
        var first = new LocalRsaRuntimeSnapshotSigner(env, privateFile.toString(), publicFile.toString());
        var signed = first.sign(new byte[]{1, 2, 3});
        var restarted = new LocalRsaRuntimeSnapshotSigner(env, privateFile.toString(), publicFile.toString());
        restarted.verify(new byte[]{1, 2, 3}, signed.signature(), signed.keyId(), signed.algorithm());
        assertThatThrownBy(() -> restarted.verify(new byte[]{9}, signed.signature(), signed.keyId(), signed.algorithm()))
                .hasMessageContaining("signature is invalid");
        Files.write(publicFile, generator.generateKeyPair().getPublic().getEncoded());
        assertThatThrownBy(() -> new LocalRsaRuntimeSnapshotSigner(env, privateFile.toString(), publicFile.toString()))
                .hasMessageContaining("signature is invalid");
        assertThatThrownBy(() -> new LocalRsaRuntimeSnapshotSigner(env, "", ""))
                .hasMessageContaining("persistent RSA");
    }
}
