package com.xuntian.mock.runtime.flow;

import org.junit.jupiter.api.Test;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;

import static org.assertj.core.api.Assertions.assertThat;

class RuntimeCryptographyTest {

    @Test
    void encryptsRoundTripAndBuildsCanonicalVersionedFlowKey() throws Exception {
        byte[] dataKey = filled(32, (byte) 7);
        byte[] hmacKey = filled(32, (byte) 9);
        RuntimeCryptography cryptography = new RuntimeCryptography("data-v1", dataKey, "hmac-v1", hmacKey);

        RuntimeCryptography.ProtectedValue encrypted = cryptography.encrypt("secret".getBytes(StandardCharsets.UTF_8));
        assertThat(encrypted.keyId()).isEqualTo("data-v1");
        assertThat(cryptography.decrypt(encrypted.keyId(), encrypted.ciphertext()))
                .isEqualTo("secret".getBytes(StandardCharsets.UTF_8));

        String scope = String.join("\u0000", "TEST", "app", "provider", "flow", "tenant", "account");
        var key = cryptography.flowKeys(scope, "business-001").get(0);
        String businessHmac = hmac(hmacKey, "business-001");
        assertThat(key.businessNoHmac()).isEqualTo(businessHmac);
        assertThat(key.flowKey()).isEqualTo(hmac(hmacKey, scope + '\u0000' + businessHmac));
        assertThat(key.hmacKeyVersion()).isEqualTo("hmac-v1");
        assertThat(key.businessNoMasked()).doesNotContain("business-001");
    }

    private static String hmac(byte[] key, String value) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(key, "HmacSHA256"));
        return HexFormat.of().formatHex(mac.doFinal(value.getBytes(StandardCharsets.UTF_8)));
    }

    private static byte[] filled(int size, byte value) {
        byte[] result = new byte[size];
        java.util.Arrays.fill(result, value);
        return result;
    }
}
