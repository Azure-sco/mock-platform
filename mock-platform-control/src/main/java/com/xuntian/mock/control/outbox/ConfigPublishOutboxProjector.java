package com.xuntian.mock.control.outbox;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xuntian.mock.common.Checksum;
import com.xuntian.mock.common.ErrorCode;
import com.xuntian.mock.common.PlatformException;
import com.xuntian.mock.control.sdkconfig.ActiveSdkConfigRecord;
import com.xuntian.mock.control.sdkconfig.SdkConfigActivationRecord;
import com.xuntian.mock.control.sdkconfig.SdkConfigMapper;
import com.xuntian.mock.control.security.ProtectedPayloadCodec;
import com.xuntian.mock.control.securitypolicy.AdmissionLeasePublisher;
import com.xuntian.mock.control.securitypolicy.SecurityPolicyBindingRecord;
import com.xuntian.mock.control.securitypolicy.SecurityPolicyMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

@Component
public final class ConfigPublishOutboxProjector {

    private static final Logger LOGGER = LoggerFactory.getLogger(ConfigPublishOutboxProjector.class);
    private static final Duration LEASE = Duration.ofSeconds(30);
    private final String workerId = "config-projector-" + UUID.randomUUID();
    private final ConfigOutboxTransactionService transactions;
    private final ConfigPublisherAdapter configPublisher;
    private final AdmissionLeasePublisher admissionPublisher;
    private final ProtectedPayloadCodec payloadCodec;
    private final SecurityPolicyMapper securityPolicyMapper;
    private final SdkConfigMapper sdkConfigMapper;
    private final ObjectMapper objectMapper;

    public ConfigPublishOutboxProjector(
            ConfigOutboxTransactionService transactions,
            ConfigPublisherAdapter configPublisher,
            AdmissionLeasePublisher admissionPublisher,
            ProtectedPayloadCodec payloadCodec,
            SecurityPolicyMapper securityPolicyMapper,
            SdkConfigMapper sdkConfigMapper,
            ObjectMapper objectMapper) {
        this.transactions = transactions;
        this.configPublisher = configPublisher;
        this.admissionPublisher = admissionPublisher;
        this.payloadCodec = payloadCodec;
        this.securityPolicyMapper = securityPolicyMapper;
        this.sdkConfigMapper = sdkConfigMapper;
        this.objectMapper = objectMapper;
    }

    @Scheduled(fixedDelayString = "${mock.config.outbox.fixed-delay-ms:250}")
    public void projectOne() {
        ConfigPublishOutboxRecord outbox = transactions.claim(workerId, LEASE);
        if (outbox == null) return;
        try {
            byte[] canonicalBytes = payloadCodec.unprotect(outbox.payloadEncrypted());
            if (!Checksum.sha256Hex(canonicalBytes).equals(outbox.checksum())) {
                throw new PlatformException(ErrorCode.INTERNAL_ERROR, "Config outbox payload checksum mismatch");
            }
            JsonNode payload = parse(canonicalBytes);
            boolean current = switch (outbox.aggregateType()) {
                case "ADMISSION_BINDING" -> publishAdmissionIfCurrent(outbox, payload, canonicalBytes);
                case "SDK_CONFIG_ACTIVATION" -> publishSdkConfigIfCurrent(outbox, payload, canonicalBytes);
                default -> throw new PlatformException(
                        ErrorCode.INTERNAL_ERROR, "Unsupported config outbox aggregate type");
            };
            transactions.finish(outbox.id(), workerId, outbox.fencingToken(), current);
        } catch (RuntimeException failure) {
            transactions.fail(outbox.id(), workerId, outbox.fencingToken(), failure);
            LOGGER.error(
                    "Config Outbox projection failed outboxId={} aggregateType={} aggregateId={} attempt={} errorType={}",
                    outbox.id(), outbox.aggregateType(), outbox.aggregateId(), outbox.attemptCount(),
                    failure.getClass().getSimpleName());
        }
    }

    private boolean publishAdmissionIfCurrent(
            ConfigPublishOutboxRecord outbox,
            JsonNode envelope,
            byte[] canonicalBytes) {
        if (!"REDIS_RUNTIME_ADMISSION".equals(outbox.targetType())) {
            throw new PlatformException(ErrorCode.INTERNAL_ERROR, "Admission Outbox target type is invalid");
        }
        ConfigOutboxTransactionService.AggregateVersion aggregate =
                ConfigOutboxTransactionService.admissionAggregate(outbox.aggregateId());
        String bindingId = requiredText(envelope, "bindingId");
        long bindingVersion = requiredLong(envelope, "bindingVersion");
        long policyVersionId = requiredLong(envelope, "policyVersionId");
        String environment = requiredText(envelope, "environment");
        String appCode = requiredText(envelope, "appCode");
        Instant issuedAt = instant(envelope, "issuedAt");
        Instant notAfter = instant(envelope, "notAfter");
        if (!bindingId.equals(aggregate.id()) || bindingVersion != aggregate.version()
                || !outbox.targetNamespace().equals(environment + ":" + appCode)
                || notAfter.isAfter(issuedAt.plusSeconds(60)) || !notAfter.isAfter(issuedAt)) {
            throw new PlatformException(ErrorCode.INTERNAL_ERROR, "Admission Outbox envelope is inconsistent");
        }
        SecurityPolicyBindingRecord binding = securityPolicyMapper.selectBindingById(bindingId);
        boolean current = binding != null && binding.bindingVersion() == bindingVersion
                && binding.desiredPolicyVersionId() == policyVersionId
                && "PUBLISHING".equals(binding.status());
        if (!current) return false;
        if (!admissionPublisher.publishIfNewer(
                environment, appCode, bindingVersion, issuedAt, notAfter, canonicalBytes)) {
            throw new PlatformException(ErrorCode.INTERNAL_ERROR, "Admission lease was not projected");
        }
        return true;
    }

    private boolean publishSdkConfigIfCurrent(
            ConfigPublishOutboxRecord outbox,
            JsonNode wrapper,
            byte[] canonicalBytes) {
        if (!"APOLLO".equals(outbox.targetType()) && !"NACOS".equals(outbox.targetType())) {
            throw new PlatformException(ErrorCode.INTERNAL_ERROR, "SDK Config Outbox target type is invalid");
        }
        JsonNode activationNode = wrapper.path("activation");
        String activationId = requiredText(activationNode, "activationId");
        long configVersion = requiredLong(activationNode, "configVersion");
        if (!outbox.aggregateId().equals(activationId)
                || !"1".equals(requiredText(activationNode, "schemaVersion"))) {
            throw new PlatformException(ErrorCode.INTERNAL_ERROR, "SDK Config Wrapper is inconsistent");
        }
        SdkConfigActivationRecord activation = sdkConfigMapper.selectActivation(activationId);
        if (activation == null) {
            throw new PlatformException(ErrorCode.INTERNAL_ERROR, "SDK config activation is missing");
        }
        ActiveSdkConfigRecord active = sdkConfigMapper.selectActive(activation.appCode(), activation.environment());
        boolean current = active != null && active.activationId().equals(activationId)
                && active.desiredConfigVersion() == configVersion
                && activation.toConfigVersion() == configVersion
                && "PENDING".equals(activation.status());
        if (!current) return false;
        configPublisher.publish(
                outbox.targetType(), outbox.targetNamespace(), activationId,
                canonicalBytes, outbox.checksum());
        return true;
    }

    private JsonNode parse(byte[] bytes) {
        try {
            JsonNode value = objectMapper.readTree(bytes);
            if (value == null || !value.isObject()) throw new IOException("not an object");
            return value;
        } catch (IOException failure) {
            throw new PlatformException(ErrorCode.INTERNAL_ERROR, "Config Outbox payload is invalid", failure);
        }
    }

    private String requiredText(JsonNode value, String field) {
        JsonNode node = value.get(field);
        if (node == null || !node.isTextual() || node.textValue().isBlank()) {
            throw new PlatformException(ErrorCode.INTERNAL_ERROR, "Config Outbox field is missing: " + field);
        }
        return node.textValue();
    }

    private long requiredLong(JsonNode value, String field) {
        JsonNode node = value.get(field);
        if (node == null || !node.isIntegralNumber() || !node.canConvertToLong()) {
            throw new PlatformException(ErrorCode.INTERNAL_ERROR, "Config Outbox field is invalid: " + field);
        }
        return node.longValue();
    }

    private Instant instant(JsonNode value, String field) {
        try {
            return Instant.parse(requiredText(value, field));
        } catch (RuntimeException failure) {
            throw new PlatformException(ErrorCode.INTERNAL_ERROR, "Config Outbox timestamp is invalid: " + field, failure);
        }
    }
}
