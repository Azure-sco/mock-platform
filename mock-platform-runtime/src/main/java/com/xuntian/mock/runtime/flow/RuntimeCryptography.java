package com.xuntian.mock.runtime.flow;

import com.xuntian.mock.common.ErrorCode;
import com.xuntian.mock.common.PlatformException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.context.annotation.Profile;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Runtime data-at-rest protection and versioned Flow HMAC material.
 * Key strings use {@code version:base64Key[,version:base64Key...]}; the first key is current.
 */
@Component
@Profile("!test")
public final class RuntimeCryptography {

    private static final int GCM_IV_BYTES = 12;
    private static final int GCM_TAG_BITS = 128;

    private final LinkedHashMap<String, byte[]> encryptionKeys;
    private final LinkedHashMap<String, byte[]> hmacKeys;
    private final SecureRandom random = new SecureRandom();

    public RuntimeCryptography(
            @Value("${MOCK_RUNTIME_DATA_KEYS:}") String encryptionKeys,
            @Value("${MOCK_FLOW_HMAC_KEYS:}") String hmacKeys) {
        this.encryptionKeys = parse(encryptionKeys, true, "MOCK_RUNTIME_DATA_KEYS");
        this.hmacKeys = parse(hmacKeys, false, "MOCK_FLOW_HMAC_KEYS");
    }

    RuntimeCryptography(String encryptionVersion, byte[] encryptionKey, String hmacVersion, byte[] hmacKey) {
        this.encryptionKeys = new LinkedHashMap<>(Map.of(encryptionVersion, encryptionKey.clone()));
        this.hmacKeys = new LinkedHashMap<>(Map.of(hmacVersion, hmacKey.clone()));
    }

    public ProtectedValue encrypt(byte[] plaintext) {
        Map.Entry<String, byte[]> current = current(encryptionKeys, "Runtime data encryption key is not configured");
        try {
            byte[] iv = new byte[GCM_IV_BYTES];
            random.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(current.getValue(), "AES"),
                    new GCMParameterSpec(GCM_TAG_BITS, iv));
            byte[] encrypted = cipher.doFinal(plaintext);
            ByteBuffer stored = ByteBuffer.allocate(1 + iv.length + encrypted.length);
            stored.put((byte) iv.length).put(iv).put(encrypted);
            return new ProtectedValue(current.getKey(), stored.array());
        } catch (GeneralSecurityException failure) {
            throw securityFailure("Runtime data encryption failed", failure);
        }
    }

    public byte[] decrypt(String keyId, byte[] ciphertext) {
        byte[] key = encryptionKeys.get(keyId);
        if (key == null || ciphertext == null || ciphertext.length <= 1 + GCM_IV_BYTES) {
            throw securityFailure("Runtime encrypted data cannot be decrypted", null);
        }
        try {
            ByteBuffer stored = ByteBuffer.wrap(ciphertext);
            int ivLength = Byte.toUnsignedInt(stored.get());
            if (ivLength != GCM_IV_BYTES || stored.remaining() <= ivLength) {
                throw securityFailure("Runtime encrypted data is malformed", null);
            }
            byte[] iv = new byte[ivLength];
            stored.get(iv);
            byte[] encrypted = new byte[stored.remaining()];
            stored.get(encrypted);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"),
                    new GCMParameterSpec(GCM_TAG_BITS, iv));
            return cipher.doFinal(encrypted);
        } catch (GeneralSecurityException failure) {
            throw securityFailure("Runtime encrypted data authentication failed", failure);
        }
    }

    public List<FlowTransitionService.FlowKey> flowKeys(String scope, String businessNo) {
        if (businessNo == null || businessNo.isBlank()) {
            throw new PlatformException(ErrorCode.MOCK_BUSINESS_KEY_MISSING, "Flow business key is missing");
        }
        if (hmacKeys.isEmpty()) {
            throw securityFailure("Flow HMAC key is not configured", null);
        }
        List<FlowTransitionService.FlowKey> result = new ArrayList<>();
        hmacKeys.forEach((version, key) -> {
            String businessHmac = hmac(key, businessNo);
            String flowKey = hmac(key, scope + '\u0000' + businessHmac);
            result.add(new FlowTransitionService.FlowKey(
                    flowKey, businessHmac, version, mask(businessNo)));
        });
        return List.copyOf(result);
    }

    private static String hmac(byte[] key, String value) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            byte[] digest = mac.doFinal(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte item : digest) hex.append(String.format("%02x", item & 0xff));
            return hex.toString();
        } catch (GeneralSecurityException failure) {
            throw securityFailure("Flow HMAC calculation failed", failure);
        }
    }

    private static LinkedHashMap<String, byte[]> parse(String source, boolean aes, String property) {
        LinkedHashMap<String, byte[]> result = new LinkedHashMap<>();
        if (source == null || source.isBlank()) return result;
        for (String item : source.split(",")) {
            int separator = item.indexOf(':');
            if (separator < 1 || separator == item.length() - 1) {
                throw new IllegalArgumentException(property + " must use version:base64Key entries");
            }
            String version = item.substring(0, separator).trim();
            if (!version.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,31}") || result.containsKey(version)) {
                throw new IllegalArgumentException(property + " contains an invalid or duplicate version");
            }
            byte[] key;
            try {
                key = Base64.getDecoder().decode(item.substring(separator + 1).trim());
            } catch (IllegalArgumentException failure) {
                throw new IllegalArgumentException(property + " contains invalid Base64", failure);
            }
            if ((aes && key.length != 32) || (!aes && key.length < 32)) {
                throw new IllegalArgumentException(property + (aes
                        ? " AES-256 keys must be 32 bytes" : " HMAC keys must be at least 32 bytes"));
            }
            result.put(version, key);
        }
        return result;
    }

    private static Map.Entry<String, byte[]> current(
            LinkedHashMap<String, byte[]> keys,
            String message) {
        return keys.entrySet().stream().findFirst().orElseThrow(() -> securityFailure(message, null));
    }

    private static String mask(String value) {
        if (value.length() <= 4) return "****";
        return value.substring(0, 2) + "***" + value.substring(value.length() - 2);
    }

    private static PlatformException securityFailure(String message, Throwable cause) {
        return cause == null
                ? new PlatformException(ErrorCode.MOCK_RELEASE_UNAVAILABLE, message)
                : new PlatformException(ErrorCode.MOCK_RELEASE_UNAVAILABLE, message, cause);
    }

    public record ProtectedValue(String keyId, byte[] ciphertext) {
        public ProtectedValue {
            ciphertext = ciphertext.clone();
        }

        @Override
        public byte[] ciphertext() {
            return ciphertext.clone();
        }
    }
}
