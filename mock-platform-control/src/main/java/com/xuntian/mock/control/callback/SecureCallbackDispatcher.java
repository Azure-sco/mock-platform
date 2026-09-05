package com.xuntian.mock.control.callback;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xuntian.mock.common.Checksum;
import com.xuntian.mock.control.security.ProtectedPayloadCodec;
import com.xuntian.mock.control.securitypolicy.SecurityPolicyMapper;
import com.xuntian.mock.control.securitypolicy.SecurityPolicyVersionRecord;
import org.apache.hc.client5.http.DnsResolver;
import org.apache.hc.client5.http.classic.methods.HttpUriRequestBase;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.CloseableHttpResponse;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.core5.http.ContentType;
import org.apache.hc.core5.http.io.entity.ByteArrayEntity;
import org.apache.hc.core5.util.Timeout;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.InetAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Component
public final class SecureCallbackDispatcher implements CallbackDispatcher {

    private static final TypeReference<Map<String, String>> HEADER_MAP = new TypeReference<>() { };
    private final CallbackTaskPayloadCodec taskCodec;
    private final CallbackSecretResolver secrets;
    private final SecurityPolicyMapper policyMapper;
    private final ProtectedPayloadCodec policyCodec;
    private final ObjectMapper mapper;
    private final boolean allowLoopback;

    public SecureCallbackDispatcher(
            CallbackTaskPayloadCodec taskCodec,
            CallbackSecretResolver secrets,
            SecurityPolicyMapper policyMapper,
            ProtectedPayloadCodec policyCodec,
            ObjectMapper mapper,
            @Value("${mock.callback.allow-loopback:false}") boolean allowLoopback) {
        this.taskCodec = taskCodec;
        this.secrets = secrets;
        this.policyMapper = policyMapper;
        this.policyCodec = policyCodec;
        this.mapper = mapper.copy();
        this.allowLoopback = allowLoopback;
    }

    @Override
    public PreparedCallback prepare(CallbackTaskRecord task) {
        try {
            URI uri = URI.create(new String(taskCodec.decrypt(
                    task.encryptionKeyId(), task.callbackUrlEncrypted()), StandardCharsets.UTF_8));
            Map<String, String> headers = mapper.readValue(
                    taskCodec.decrypt(task.encryptionKeyId(), task.headersJsonEncrypted()), HEADER_MAP);
            byte[] payload = taskCodec.decrypt(task.encryptionKeyId(), task.payloadEncrypted());
            if (!Checksum.sha256Hex(payload).equals(task.renderedPayloadHash())) {
                throw new PreparationException("Callback payload checksum verification failed");
            }
            JsonNode allowlist = policy(task.callbackAllowlistPolicyVersionId(), "CALLBACK_ALLOWLIST");
            requireAllowed(uri, allowlist.path("allowedBaseUrls"));
            InetAddress pinned = resolveAndValidate(uri.getHost());
            JsonNode signature = policy(task.callbackSignaturePolicyVersionId(), "CALLBACK_SIGNATURE");
            Map<String, String> signedHeaders = new LinkedHashMap<>(headers);
            signedHeaders.put("X-Mock-Delivery-Id", task.deliveryId());
            signedHeaders.put("X-Mock-Signature", sign(
                    signature.path("algorithm").asText(),
                    secrets.resolve(signature.path("secretRef").asText()),
                    task.deliveryId(), payload));
            signedHeaders.put("X-Mock-Signature-Timestamp", Long.toString(Instant.now().getEpochSecond()));
            signedHeaders.put("X-Mock-Pinned-Ip", pinned.getHostAddress());
            return new PreparedCallback(
                    task.taskId(), task.deliveryId(), uri, task.httpMethod(), signedHeaders, payload);
        } catch (PreparationException failure) {
            throw failure;
        } catch (Exception failure) {
            throw new PreparationException("Callback preparation failed", failure);
        }
    }

    @Override
    public SendOutcome send(PreparedCallback callback) {
        long started = System.nanoTime();
        String pinnedText = callback.headers().get("X-Mock-Pinned-Ip");
        try {
            InetAddress pinned = InetAddress.getByName(pinnedText);
            DnsResolver resolver = new DnsResolver() {
                @Override
                public InetAddress[] resolve(String host) throws java.net.UnknownHostException {
                    if (!host.equalsIgnoreCase(callback.url().getHost())) {
                        throw new java.net.UnknownHostException("Callback attempted to resolve an unapproved host");
                    }
                    return new InetAddress[]{pinned};
                }

                @Override
                public String resolveCanonicalHostname(String host) throws java.net.UnknownHostException {
                    if (!host.equalsIgnoreCase(callback.url().getHost())) {
                        throw new java.net.UnknownHostException("Callback attempted to resolve an unapproved host");
                    }
                    return callback.url().getHost();
                }
            };
            var manager = PoolingHttpClientConnectionManagerBuilder.create()
                    .setDnsResolver(resolver)
                    .build();
            RequestConfig requestConfig = RequestConfig.custom()
                    .setConnectionRequestTimeout(Timeout.ofSeconds(2))
                    .setConnectTimeout(Timeout.ofSeconds(3))
                    .setResponseTimeout(Timeout.ofSeconds(5))
                    .build();
            try (CloseableHttpClient client = HttpClients.custom()
                    .setConnectionManager(manager)
                    .setDefaultRequestConfig(requestConfig)
                    .disableRedirectHandling()
                    .build()) {
                HttpUriRequestBase request = new HttpUriRequestBase(callback.method(), callback.url());
                callback.headers().forEach((name, value) -> {
                    if (!"X-Mock-Pinned-Ip".equalsIgnoreCase(name)) request.setHeader(name, value);
                });
                request.setEntity(new ByteArrayEntity(callback.payload(), ContentType.APPLICATION_JSON));
                try (CloseableHttpResponse response = client.execute(request)) {
                    return SendOutcome.confirmed(response.getCode(), elapsed(started));
                }
            }
        } catch (Exception failure) {
            return SendOutcome.unknown(mask(failure), elapsed(started));
        }
    }

    private JsonNode policy(long id, String expectedType) throws Exception {
        SecurityPolicyVersionRecord policy = policyMapper.selectVersionById(id);
        if (policy == null || !expectedType.equals(policy.policyType()) || !"PUBLISHED".equals(policy.status())) {
            throw new PreparationException("Fixed Callback policy Version is unavailable");
        }
        return mapper.readTree(policyCodec.unprotect(policy.configJsonEncrypted()));
    }

    private void requireAllowed(URI target, JsonNode values) {
        boolean loopback = allowLoopback && "http".equalsIgnoreCase(target.getScheme())
                && ("localhost".equalsIgnoreCase(target.getHost()) || "127.0.0.1".equals(target.getHost()));
        if (!("https".equalsIgnoreCase(target.getScheme()) || loopback) || target.getHost() == null
                || target.getUserInfo() != null || target.getFragment() != null) {
            throw new PreparationException("Callback target must be HTTPS or an explicitly enabled loopback URL");
        }
        for (JsonNode value : values) {
            URI base = URI.create(value.asText());
            int targetPort = effectivePort(target);
            int basePort = effectivePort(base);
            String basePath = normalizePath(base.getPath());
            String targetPath = normalizePath(target.getPath());
            if (target.getHost().equalsIgnoreCase(base.getHost()) && targetPort == basePort
                    && ("/".equals(basePath) || targetPath.equals(basePath)
                    || targetPath.startsWith(basePath.endsWith("/") ? basePath : basePath + "/"))) {
                return;
            }
        }
        throw new PreparationException("Callback target is outside the fixed Allowlist policy");
    }

    private InetAddress resolveAndValidate(String host) throws Exception {
        InetAddress[] values = InetAddress.getAllByName(host);
        if (values.length == 0) throw new PreparationException("Callback DNS returned no address");
        List<InetAddress> allowed = new ArrayList<>();
        for (InetAddress value : values) {
            if (isForbidden(value) && !(allowLoopback && value.isLoopbackAddress())) {
                throw new PreparationException("Callback DNS resolved to a forbidden address range");
            }
            allowed.add(value);
        }
        return allowed.get(0);
    }

    private boolean isForbidden(InetAddress address) {
        if (address.isAnyLocalAddress() || address.isLoopbackAddress() || address.isLinkLocalAddress()
                || address.isSiteLocalAddress() || address.isMulticastAddress()) return true;
        byte[] bytes = address.getAddress();
        if (bytes.length == 4) {
            int first = Byte.toUnsignedInt(bytes[0]);
            int second = Byte.toUnsignedInt(bytes[1]);
            return first == 0 || first == 127 || (first == 100 && second >= 64 && second <= 127);
        }
        return (bytes[0] & 0xfe) == 0xfc;
    }

    private String sign(String algorithm, byte[] secret, String deliveryId, byte[] payload) throws Exception {
        String jca = switch (algorithm.toUpperCase(Locale.ROOT)) {
            case "HMAC_SHA256" -> "HmacSHA256";
            case "HMAC_SHA512" -> "HmacSHA512";
            default -> throw new PreparationException("Callback signature algorithm is unsupported");
        };
        Mac mac = Mac.getInstance(jca);
        mac.init(new SecretKeySpec(secret, jca));
        mac.update(deliveryId.getBytes(StandardCharsets.UTF_8));
        mac.update((byte) '.');
        mac.update(payload);
        return Base64.getEncoder().encodeToString(mac.doFinal());
    }

    private static String normalizePath(String value) {
        return value == null || value.isBlank() ? "/" : value;
    }
    private static int effectivePort(URI value) {
        if (value.getPort() >= 0) return value.getPort();
        return "http".equalsIgnoreCase(value.getScheme()) ? 80 : 443;
    }
    private static long elapsed(long started) { return Math.max(0, (System.nanoTime() - started) / 1_000_000); }
    private static String mask(Throwable failure) {
        String type = failure.getClass().getSimpleName();
        return type.length() > 128 ? "Callback transport failure" : type;
    }
}
