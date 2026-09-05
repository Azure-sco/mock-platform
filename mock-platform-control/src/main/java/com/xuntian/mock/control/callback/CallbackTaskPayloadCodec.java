package com.xuntian.mock.control.callback;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

@Component
public final class CallbackTaskPayloadCodec {

    private final Map<String, byte[]> keys;
    private final String currentKeyId;
    private final SecureRandom secureRandom = new SecureRandom();

    public CallbackTaskPayloadCodec(@Value("${MOCK_RUNTIME_DATA_KEYS:}") String configuredKeys) {
        this.keys = parse(configuredKeys);
        this.currentKeyId = firstVersion(configuredKeys);
    }

    public ProtectedValue encrypt(byte[] plaintext) {
        if (currentKeyId == null) {
            throw new CallbackDispatcher.PreparationException("Callback data key is unavailable");
        }
        try {
            byte[] iv = new byte[12];
            secureRandom.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(keys.get(currentKeyId), "AES"),
                    new GCMParameterSpec(128, iv));
            byte[] encrypted = cipher.doFinal(plaintext);
            return new ProtectedValue(currentKeyId, ByteBuffer.allocate(1 + iv.length + encrypted.length)
                    .put((byte) iv.length).put(iv).put(encrypted).array());
        } catch (GeneralSecurityException failure) {
            throw new CallbackDispatcher.PreparationException("Callback payload encryption failed", failure);
        }
    }

    public byte[] decrypt(String keyId, byte[] ciphertext) {
        byte[] key = keys.get(keyId);
        if (key == null || ciphertext == null || ciphertext.length < 14) {
            throw new CallbackDispatcher.PreparationException("Callback data key is unavailable");
        }
        try {
            ByteBuffer stored = ByteBuffer.wrap(ciphertext);
            int ivLength = Byte.toUnsignedInt(stored.get());
            if (ivLength != 12 || stored.remaining() <= ivLength) {
                throw new CallbackDispatcher.PreparationException("Callback encrypted payload is malformed");
            }
            byte[] iv = new byte[ivLength];
            stored.get(iv);
            byte[] encrypted = new byte[stored.remaining()];
            stored.get(encrypted);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, iv));
            return cipher.doFinal(encrypted);
        } catch (GeneralSecurityException failure) {
            throw new CallbackDispatcher.PreparationException("Callback encrypted payload authentication failed", failure);
        }
    }

    private static Map<String, byte[]> parse(String source) {
        Map<String, byte[]> result = new LinkedHashMap<>();
        if (source == null || source.isBlank()) return Map.of();
        for (String item : source.split(",")) {
            int separator = item.indexOf(':');
            if (separator < 1) throw new IllegalArgumentException("MOCK_RUNTIME_DATA_KEYS is invalid");
            String version = item.substring(0, separator).trim();
            byte[] key;
            try { key = Base64.getDecoder().decode(item.substring(separator + 1).trim()); }
            catch (IllegalArgumentException failure) {
                throw new IllegalArgumentException("MOCK_RUNTIME_DATA_KEYS contains invalid Base64", failure);
            }
            if (key.length != 32 || result.putIfAbsent(version, key) != null) {
                throw new IllegalArgumentException("MOCK_RUNTIME_DATA_KEYS must contain unique AES-256 keys");
            }
        }
        return Map.copyOf(result);
    }

    private static String firstVersion(String source) {
        if (source == null || source.isBlank()) return null;
        String first = source.split(",", 2)[0];
        int separator = first.indexOf(':');
        return separator < 1 ? null : first.substring(0, separator).trim();
    }

    public record ProtectedValue(String keyId, byte[] ciphertext) {
        public ProtectedValue {
            ciphertext = ciphertext.clone();
        }
    }
}
