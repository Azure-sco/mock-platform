package com.xuntian.mock.control.callback;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xuntian.mock.common.Checksum;
import com.xuntian.mock.control.security.ProtectedPayloadCodec;
import com.xuntian.mock.control.securitypolicy.SecurityPolicyMapper;
import com.xuntian.mock.control.securitypolicy.SecurityPolicyVersionRecord;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Assumptions;

import java.net.InetAddress;
import java.net.ServerSocket;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SecureCallbackDispatcherTest {

    @Test
    void pinsLoopbackDnsSignsStableDeliveryAndSendsWithoutLeakingPinHeader() throws Exception {
        Assumptions.assumeTrue(Boolean.getBoolean("mock.integration.loopback"),
                "loopback sockets are unavailable in the filesystem sandbox");
        AtomicReference<String> delivery = new AtomicReference<>();
        AtomicReference<String> signature = new AtomicReference<>();
        AtomicReference<String> pinHeader = new AtomicReference<>();
        try (ServerSocket server = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
            Thread receiver = new Thread(() -> receive(server, delivery, signature, pinHeader), "callback-test-server");
            receiver.start();
            ObjectMapper mapper = new ObjectMapper();
            SecurityPolicyMapper policies = mock(SecurityPolicyMapper.class);
            String base = "http://127.0.0.1:" + server.getLocalPort() + "/callback";
            when(policies.selectVersionById(11)).thenReturn(policy(
                    11, "CALLBACK_ALLOWLIST", "{\"allowedBaseUrls\":[\"" + base + "\"]}"));
            when(policies.selectVersionById(12)).thenReturn(policy(
                    12, "CALLBACK_SIGNATURE", "{\"algorithm\":\"HMAC_SHA256\",\"secretRef\":\"local-key\"}"));
            ProtectedPayloadCodec policyCodec = new ProtectedPayloadCodec() {
                @Override public String protect(byte[] plaintext) { return Base64.getEncoder().encodeToString(plaintext); }
                @Override public byte[] unprotect(String value) { return value.getBytes(StandardCharsets.UTF_8); }
            };
            byte[] dataKey = new byte[32];
            java.util.Arrays.fill(dataKey, (byte) 3);
            CallbackTaskPayloadCodec codec = new CallbackTaskPayloadCodec(
                    "v1:" + Base64.getEncoder().encodeToString(dataKey));
            byte[] payload = "{\"state\":\"SUCCESS\"}".getBytes(StandardCharsets.UTF_8);
            CallbackTaskRecord task = task(codec, base, payload);
            SecureCallbackDispatcher dispatcher = new SecureCallbackDispatcher(
                    codec, secretRef -> "callback-secret-32-bytes-minimum".getBytes(StandardCharsets.UTF_8),
                    policies, policyCodec, mapper, true);

            CallbackDispatcher.PreparedCallback prepared = dispatcher.prepare(task);
            CallbackDispatcher.SendOutcome outcome = dispatcher.send(prepared);

            assertThat(outcome.success()).isTrue();
            assertThat(outcome.certainty()).isEqualTo("CONFIRMED_RESPONSE");
            assertThat(delivery.get()).isEqualTo("delivery-1");
            assertThat(signature.get()).isNotBlank();
            assertThat(pinHeader.get()).isNull();
            receiver.join(2_000);
        }
    }

    @Test
    void rejectsHttpLoopbackUnlessExplicitlyEnabled() {
        ObjectMapper mapper = new ObjectMapper();
        SecurityPolicyMapper policies = mock(SecurityPolicyMapper.class);
        when(policies.selectVersionById(11)).thenReturn(policy(
                11, "CALLBACK_ALLOWLIST", "{\"allowedBaseUrls\":[\"http://127.0.0.1:18080/\"]}"));
        when(policies.selectVersionById(12)).thenReturn(policy(
                12, "CALLBACK_SIGNATURE", "{\"algorithm\":\"HMAC_SHA256\",\"secretRef\":\"local-key\"}"));
        byte[] dataKey = new byte[32];
        CallbackTaskPayloadCodec codec = new CallbackTaskPayloadCodec(
                "v1:" + Base64.getEncoder().encodeToString(dataKey));
        ProtectedPayloadCodec plain = new ProtectedPayloadCodec() {
            @Override public String protect(byte[] plaintext) { return new String(plaintext, StandardCharsets.UTF_8); }
            @Override public byte[] unprotect(String value) { return value.getBytes(StandardCharsets.UTF_8); }
        };
        SecureCallbackDispatcher dispatcher = new SecureCallbackDispatcher(
                codec, ignored -> new byte[32], policies, plain, mapper, false);

        assertThatThrownBy(() -> dispatcher.prepare(task(
                codec, "http://127.0.0.1:18080/", "{}".getBytes(StandardCharsets.UTF_8))))
                .isInstanceOf(CallbackDispatcher.PreparationException.class)
                .hasMessageContaining("HTTPS");
    }

    private static CallbackTaskRecord task(
            CallbackTaskPayloadCodec codec,
            String url,
            byte[] payload) {
        var encryptedUrl = codec.encrypt(url.getBytes(StandardCharsets.UTF_8));
        var encryptedHeaders = codec.encrypt("{\"Content-Type\":\"application/json\"}"
                .getBytes(StandardCharsets.UTF_8));
        var encryptedBody = codec.encrypt(payload);
        Instant now = Instant.parse("2026-08-31T08:00:00Z");
        return new CallbackTaskRecord(
                1, "task-1", "delivery-1", 2, 3, 1, "release", "0".repeat(64),
                "callback-1", 0, "provider", "api", encryptedUrl.ciphertext(), "POST",
                encryptedHeaders.ciphertext(), encryptedBody.ciphertext(), Checksum.sha256Hex(payload),
                encryptedUrl.keyId(), 12, 11, "NEW", now, 0, 2, "[100]",
                0, 3, 0, 0, null, null, 0, null, null,
                now.plusSeconds(60), now, now);
    }

    private static SecurityPolicyVersionRecord policy(long id, String type, String json) {
        Instant now = Instant.parse("2026-08-31T08:00:00Z");
        return new SecurityPolicyVersionRecord(
                id, "policy-" + id, type, "local", 1, json, "0".repeat(64),
                "PUBLISHED", null, null, null, null, null, "admin", now, "admin", now);
    }

    private static void receive(
            ServerSocket server,
            AtomicReference<String> delivery,
            AtomicReference<String> signature,
            AtomicReference<String> pinHeader) {
        try (var socket = server.accept();
             var reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.US_ASCII))) {
            String line;
            while ((line = reader.readLine()) != null && !line.isEmpty()) {
                int separator = line.indexOf(':');
                if (separator < 1) continue;
                String name = line.substring(0, separator);
                String value = line.substring(separator + 1).trim();
                if ("X-Mock-Delivery-Id".equalsIgnoreCase(name)) delivery.set(value);
                if ("X-Mock-Signature".equalsIgnoreCase(name)) signature.set(value);
                if ("X-Mock-Pinned-Ip".equalsIgnoreCase(name)) pinHeader.set(value);
            }
            socket.getOutputStream().write(("HTTP/1.1 204 No Content\r\n"
                    + "Content-Length: 0\r\nConnection: close\r\n\r\n")
                    .getBytes(StandardCharsets.US_ASCII));
            socket.getOutputStream().flush();
        } catch (Exception failure) {
            throw new AssertionError(failure);
        }
    }
}
