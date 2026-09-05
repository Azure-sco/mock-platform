package com.xuntian.mock.runtime.admission;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.xuntian.mock.runtime.release.SnapshotSignatureKeyProvider;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.PublicKey;
import java.security.Signature;
import java.security.interfaces.RSAPublicKey;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.StreamSupport;

@Component
public final class AdmissionEnvelopeVerifier {

    static final int MAX_ENVELOPE_BYTES = 1024 * 1024;
    static final String SIGNATURE_ALGORITHM = "SHA256withRSA";
    private static final Pattern CODE = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}");

    private final ObjectMapper mapper;
    private final SnapshotSignatureKeyProvider keys;

    public AdmissionEnvelopeVerifier(ObjectMapper mapper, SnapshotSignatureKeyProvider keys) {
        this.mapper = mapper.copy()
                .enable(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY)
                .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);
        this.keys = keys;
    }

    public VerifiedAdmissionSnapshot verify(
            byte[] envelopeBytes,
            AdmissionScope expectedScope,
            Instant now) {
        if (envelopeBytes == null || envelopeBytes.length == 0 || envelopeBytes.length > MAX_ENVELOPE_BYTES) {
            throw failure(AdmissionVerificationException.Reason.INVALID_ENVELOPE, "Admission envelope size is invalid");
        }
        ObjectNode envelope = readObject(envelopeBytes);
        if (!Arrays.equals(envelopeBytes, canonical(envelope))) {
            throw failure(AdmissionVerificationException.Reason.INVALID_ENVELOPE, "Admission envelope is not canonical JSON");
        }
        String checksum = requiredText(envelope, "checksum", 64);
        String signature = requiredText(envelope, "signature", 8192);
        String keyId = requiredText(envelope, "signatureKeyId", 128);
        if (!SIGNATURE_ALGORITHM.equals(requiredText(envelope, "signatureAlgorithm", 64))) {
            throw failure(AdmissionVerificationException.Reason.SIGNATURE_INVALID, "Admission signature algorithm is unsupported");
        }
        if (!checksum.matches("[a-f0-9]{64}")) {
            throw failure(AdmissionVerificationException.Reason.INVALID_ENVELOPE, "Admission checksum is invalid");
        }
        ObjectNode content = envelope.deepCopy();
        content.remove(List.of("checksum", "signature", "signatureKeyId", "signatureAlgorithm"));
        byte[] canonicalContent = canonical(content);
        if (!constantTimeEquals(sha256(canonicalContent), checksum)) {
            throw failure(AdmissionVerificationException.Reason.CHECKSUM_MISMATCH, "Admission checksum validation failed");
        }
        PublicKey key = keys.find(keyId).orElseThrow(() -> failure(
                AdmissionVerificationException.Reason.UNKNOWN_KEY, "Admission signature key is unknown"));
        if (!(key instanceof RSAPublicKey rsaKey) || rsaKey.getModulus().bitLength() < 2048) {
            throw failure(AdmissionVerificationException.Reason.SIGNATURE_INVALID, "Admission signature key is invalid");
        }
        verifySignature(key, canonicalContent, signature);

        String environment = requiredCode(content, "environment");
        String appCode = requiredCode(content, "appCode");
        if (!expectedScope.environment().equals(environment) || !expectedScope.appCode().equals(appCode)) {
            throw failure(AdmissionVerificationException.Reason.SCOPE_MISMATCH, "Admission scope does not match Runtime scope");
        }
        Instant issuedAt = instant(content, "issuedAt");
        Instant notAfter = instant(content, "notAfter");
        if (issuedAt.isAfter(now.plusSeconds(5))) {
            throw failure(AdmissionVerificationException.Reason.CLOCK_SKEW, "Admission lease is issued in the future");
        }
        Duration lease = Duration.between(issuedAt, notAfter);
        if (lease.isNegative() || lease.isZero() || lease.compareTo(Duration.ofSeconds(60)) > 0) {
            throw failure(AdmissionVerificationException.Reason.PAYLOAD_INVALID, "Admission lease duration is invalid");
        }
        if (!notAfter.isAfter(now)) {
            throw failure(AdmissionVerificationException.Reason.EXPIRED, "Admission lease is expired");
        }
        long policyVersionId = positiveLong(content, "policyVersionId");
        long bindingVersion = positiveLong(content, "bindingVersion");
        ObjectNode payload = object(content, "payload");
        if (!environment.equals(requiredCode(payload, "environment"))
                || !appCode.equals(requiredCode(payload, "appCode"))) {
            throw failure(AdmissionVerificationException.Reason.SCOPE_MISMATCH, "Admission payload scope is inconsistent");
        }
        String policyChecksum = requiredText(content, "policyChecksum", 64);
        if (!policyChecksum.matches("[a-f0-9]{64}")
                || !constantTimeEquals(sha256(canonical(payload)), policyChecksum)) {
            throw failure(AdmissionVerificationException.Reason.CHECKSUM_MISMATCH, "Admission policy checksum is invalid");
        }
        return new VerifiedAdmissionSnapshot(
                requiredCode(content, "bindingId"), expectedScope, policyVersionId, bindingVersion,
                checksum, keyId, issuedAt, notAfter, rules(payload));
    }

    private List<VerifiedAdmissionSnapshot.Rule> rules(ObjectNode payload) {
        JsonNode rawRules = payload.get("rules");
        if (rawRules == null || !rawRules.isArray() || rawRules.size() > 10_000) {
            throw failure(AdmissionVerificationException.Reason.PAYLOAD_INVALID, "Admission rules are invalid");
        }
        return StreamSupport.stream(rawRules.spliterator(), false)
                .map(this::rule)
                .toList();
    }

    private VerifiedAdmissionSnapshot.Rule rule(JsonNode value) {
        if (!value.isObject()) {
            throw failure(AdmissionVerificationException.Reason.PAYLOAD_INVALID, "Admission rule is invalid");
        }
        ObjectNode rule = (ObjectNode) value;
        return new VerifiedAdmissionSnapshot.Rule(
                requiredCode(rule, "providerCode"),
                stringSet(rule, "apiCodes"),
                stringSet(rule, "tenantCodes"),
                stringSet(rule, "testAccounts"));
    }

    private Set<String> stringSet(ObjectNode object, String field) {
        JsonNode values = object.get(field);
        if (values == null || values.isNull()) return Set.of();
        if (!values.isArray() || values.size() > 10_000) {
            throw failure(AdmissionVerificationException.Reason.PAYLOAD_INVALID, "Admission rule list is invalid");
        }
        Set<String> result = new HashSet<>();
        for (JsonNode value : values) {
            if (!value.isTextual() || !CODE.matcher(value.textValue()).matches() || !result.add(value.textValue())) {
                throw failure(AdmissionVerificationException.Reason.PAYLOAD_INVALID, "Admission rule value is invalid");
            }
        }
        return Set.copyOf(result);
    }

    private ObjectNode readObject(byte[] bytes) {
        try {
            JsonNode value = mapper.readTree(bytes);
            if (!value.isObject()) throw new IOException("not an object");
            return (ObjectNode) value;
        } catch (IOException failure) {
            throw new AdmissionVerificationException(
                    AdmissionVerificationException.Reason.INVALID_ENVELOPE,
                    "Admission envelope is malformed", failure);
        }
    }

    private ObjectNode object(ObjectNode parent, String field) {
        JsonNode value = parent.get(field);
        if (value == null || !value.isObject()) {
            throw failure(AdmissionVerificationException.Reason.PAYLOAD_INVALID, field + " is required");
        }
        return (ObjectNode) value;
    }

    private String requiredCode(ObjectNode object, String field) {
        String value = requiredText(object, field, 128);
        if (!CODE.matcher(value).matches()) {
            throw failure(AdmissionVerificationException.Reason.PAYLOAD_INVALID, field + " is invalid");
        }
        return value;
    }

    private String requiredText(ObjectNode object, String field, int maxLength) {
        JsonNode value = object.get(field);
        if (value == null || !value.isTextual() || value.textValue().isBlank()
                || value.textValue().length() > maxLength) {
            throw failure(AdmissionVerificationException.Reason.INVALID_ENVELOPE, field + " is invalid");
        }
        return value.textValue();
    }

    private long positiveLong(ObjectNode object, String field) {
        JsonNode value = object.get(field);
        if (value == null || !value.isIntegralNumber() || !value.canConvertToLong() || value.longValue() <= 0) {
            throw failure(AdmissionVerificationException.Reason.PAYLOAD_INVALID, field + " is invalid");
        }
        return value.longValue();
    }

    private Instant instant(ObjectNode object, String field) {
        try {
            return Instant.parse(requiredText(object, field, 64));
        } catch (DateTimeParseException failure) {
            throw new AdmissionVerificationException(
                    AdmissionVerificationException.Reason.PAYLOAD_INVALID,
                    field + " is invalid", failure);
        }
    }

    private byte[] canonical(JsonNode value) {
        try {
            return mapper.writeValueAsBytes(canonicalize(value));
        } catch (IOException failure) {
            throw new AdmissionVerificationException(
                    AdmissionVerificationException.Reason.INVALID_ENVELOPE,
                    "Admission envelope cannot be canonicalized", failure);
        }
    }

    private JsonNode canonicalize(JsonNode input) {
        if (input.isObject()) {
            ObjectNode result = mapper.createObjectNode();
            StreamSupport.stream(((Iterable<String>) input::fieldNames).spliterator(), false)
                    .sorted()
                    .forEach(name -> result.set(name, canonicalize(input.get(name))));
            return result;
        }
        if (input.isArray()) {
            ArrayNode result = mapper.createArrayNode();
            input.forEach(item -> result.add(canonicalize(item)));
            return result;
        }
        return input.deepCopy();
    }

    private void verifySignature(PublicKey key, byte[] content, String encodedSignature) {
        try {
            byte[] bytes = Base64.getDecoder().decode(encodedSignature);
            Signature verifier = Signature.getInstance(SIGNATURE_ALGORITHM);
            verifier.initVerify(key);
            verifier.update(content);
            if (!verifier.verify(bytes)) {
                throw failure(AdmissionVerificationException.Reason.SIGNATURE_INVALID, "Admission signature is invalid");
            }
        } catch (IllegalArgumentException | GeneralSecurityException failure) {
            throw new AdmissionVerificationException(
                    AdmissionVerificationException.Reason.SIGNATURE_INVALID,
                    "Admission signature is invalid", failure);
        }
    }

    private static String sha256(byte[] value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
        } catch (GeneralSecurityException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    private static boolean constantTimeEquals(String left, String right) {
        return right != null && MessageDigest.isEqual(
                left.getBytes(StandardCharsets.US_ASCII), right.getBytes(StandardCharsets.US_ASCII));
    }

    private static AdmissionVerificationException failure(
            AdmissionVerificationException.Reason reason,
            String message) {
        return new AdmissionVerificationException(reason, message);
    }
}
