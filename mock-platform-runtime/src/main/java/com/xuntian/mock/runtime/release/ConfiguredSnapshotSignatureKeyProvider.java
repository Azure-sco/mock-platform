package com.xuntian.mock.runtime.release;

import com.xuntian.mock.runtime.RuntimeProperties;
import org.springframework.stereotype.Component;

import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

@Component
public final class ConfiguredSnapshotSignatureKeyProvider implements SnapshotSignatureKeyProvider {

    private final Map<String, PublicKey> keys;

    public ConfiguredSnapshotSignatureKeyProvider(RuntimeProperties properties) {
        Map<String, PublicKey> parsed = new LinkedHashMap<>();
        properties.getSnapshotPublicKeys().forEach((keyId, encoded) -> {
            if (keyId == null || keyId.length() > 128
                    || !keyId.matches("[A-Za-z0-9][A-Za-z0-9._:-]*")) {
                throw new IllegalStateException("Configured Snapshot public key id is invalid");
            }
            parsed.put(keyId, parse(encoded));
        });
        this.keys = Map.copyOf(parsed);
    }

    @Override
    public Optional<PublicKey> find(String keyId) {
        return Optional.ofNullable(keys.get(keyId));
    }

    private static PublicKey parse(String encoded) {
        if (encoded == null || encoded.length() > 16384) {
            throw new IllegalStateException("Configured Snapshot public key is invalid");
        }
        String normalized = encoded
                .replace("-----BEGIN PUBLIC KEY-----", "")
                .replace("-----END PUBLIC KEY-----", "")
                .replaceAll("\\s", "");
        try {
            byte[] der = Base64.getDecoder().decode(normalized);
            return KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(der));
        } catch (IllegalArgumentException | GeneralSecurityException failure) {
            throw new IllegalStateException("Configured Snapshot public key is invalid", failure);
        }
    }
}
