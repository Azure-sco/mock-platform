package com.xuntian.mock.runtime.release;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.xuntian.mock.runtime.snapshot.RuntimeSnapshot;
import com.xuntian.mock.runtime.snapshot.RuntimeSnapshotCompiler;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.PublicKey;
import java.security.Signature;
import java.security.interfaces.RSAPublicKey;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;
import java.util.stream.StreamSupport;

@Component
public final class RuntimeSnapshotEnvelopeVerifier {

    static final int MAX_ENVELOPE_BYTES = 5 * 1024 * 1024;
    public static final String SIGNATURE_ALGORITHM = "SHA256withRSA";

    private final ObjectMapper mapper;
    private final RuntimeSnapshotCompiler compiler;
    private final SnapshotSignatureKeyProvider keys;

    public RuntimeSnapshotEnvelopeVerifier(
            ObjectMapper mapper,
            RuntimeSnapshotCompiler compiler,
            SnapshotSignatureKeyProvider keys) {
        this.mapper = mapper.copy()
                .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .enable(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY)
                .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);
        this.compiler = compiler;
        this.keys = keys;
    }

    public VerifiedReleaseSnapshot verify(
            byte[] envelopeBytes,
            ActiveReleasePointer pointer,
            ReleaseScope expectedScope) {
        if (envelopeBytes == null || envelopeBytes.length == 0 || envelopeBytes.length > MAX_ENVELOPE_BYTES) {
            throw failure(SnapshotVerificationException.Reason.INVALID_ENVELOPE,
                    "Snapshot envelope size is invalid");
        }
        SignedRuntimeSnapshotEnvelope envelope = read(envelopeBytes);
        if (!Arrays.equals(envelopeBytes, canonicalEnvelopeBytes(envelope))) {
            throw failure(SnapshotVerificationException.Reason.INVALID_ENVELOPE,
                    "Snapshot envelope is not canonical JSON");
        }
        PublishedSnapshotDefinition snapshot = envelope.snapshot();
        validateEnvelope(envelope, snapshot, pointer, expectedScope);
        byte[] canonical = canonicalSnapshotBytes(snapshot);
        String calculatedChecksum = sha256(canonical);
        if (!constantTimeEquals(calculatedChecksum, envelope.checksum())) {
            throw failure(SnapshotVerificationException.Reason.CHECKSUM_MISMATCH,
                    "Snapshot checksum validation failed");
        }
        PublicKey publicKey = keys.find(envelope.signatureKeyId()).orElseThrow(() -> failure(
                SnapshotVerificationException.Reason.UNKNOWN_KEY,
                "Snapshot signature key is unknown"));
        if (!(publicKey instanceof RSAPublicKey rsaKey) || rsaKey.getModulus().bitLength() < 2048) {
            throw failure(SnapshotVerificationException.Reason.SIGNATURE_INVALID,
                    "Snapshot signature key is invalid");
        }
        verifySignature(publicKey, canonical, envelope.signature());
        RuntimeSnapshot compiled;
        try {
            compiled = compiler.compile(snapshot);
        } catch (RuntimeException compileFailure) {
            throw new SnapshotVerificationException(
                    SnapshotVerificationException.Reason.COMPILE_FAILED,
                    "Snapshot compiled artifacts are invalid",
                    compileFailure);
        }
        return new VerifiedReleaseSnapshot(
                expectedScope,
                snapshot.releaseId(),
                envelope.checksum(),
                envelope.signatureKeyId(),
                compiled);
    }

    public byte[] canonicalSnapshotBytes(PublishedSnapshotDefinition snapshot) {
        if (snapshot == null) {
            throw failure(SnapshotVerificationException.Reason.INVALID_ENVELOPE,
                    "Snapshot payload is required");
        }
        try {
            return mapper.writeValueAsBytes(canonicalize(mapper.valueToTree(snapshot)));
        } catch (IOException failure) {
            throw new SnapshotVerificationException(
                    SnapshotVerificationException.Reason.INVALID_ENVELOPE,
                    "Snapshot payload cannot be canonicalized",
                    failure);
        }
    }

    public byte[] canonicalEnvelopeBytes(SignedRuntimeSnapshotEnvelope envelope) {
        if (envelope == null) {
            throw failure(SnapshotVerificationException.Reason.INVALID_ENVELOPE,
                    "Snapshot envelope is required");
        }
        return canonicalBytes(envelope, "Snapshot envelope cannot be canonicalized");
    }

    public String checksum(PublishedSnapshotDefinition snapshot) {
        return sha256(canonicalSnapshotBytes(snapshot));
    }

    private byte[] canonicalBytes(Object value, String errorMessage) {
        try {
            return mapper.writeValueAsBytes(canonicalize(mapper.valueToTree(value)));
        } catch (IOException failure) {
            throw new SnapshotVerificationException(
                    SnapshotVerificationException.Reason.INVALID_ENVELOPE,
                    errorMessage,
                    failure);
        }
    }

    private SignedRuntimeSnapshotEnvelope read(byte[] envelopeBytes) {
        try {
            return mapper.readValue(envelopeBytes, SignedRuntimeSnapshotEnvelope.class);
        } catch (IOException failure) {
            throw new SnapshotVerificationException(
                    SnapshotVerificationException.Reason.INVALID_ENVELOPE,
                    "Snapshot envelope is malformed",
                    failure);
        }
    }

    private void validateEnvelope(
            SignedRuntimeSnapshotEnvelope envelope,
            PublishedSnapshotDefinition snapshot,
            ActiveReleasePointer pointer,
            ReleaseScope expectedScope) {
        if (envelope == null || snapshot == null || pointer == null || expectedScope == null
                || envelope.checksum() == null || envelope.signature() == null
                || envelope.signatureKeyId() == null || envelope.signatureAlgorithm() == null) {
            throw failure(SnapshotVerificationException.Reason.INVALID_ENVELOPE,
                    "Snapshot envelope required fields are missing");
        }
        if (!PublishedSnapshotDefinition.CURRENT_SCHEMA_VERSION.equals(snapshot.schemaVersion())
                && !PublishedSnapshotDefinition.LEGACY_SCHEMA_VERSION.equals(snapshot.schemaVersion())) {
            throw failure(SnapshotVerificationException.Reason.UNSUPPORTED_SCHEMA,
                    "Snapshot schema version is unsupported");
        }
        if (snapshot.compiledArtifacts() == null
                || snapshot.compiledContracts() == null || snapshot.compiledContracts().isEmpty()
                || snapshot.compiledScenarios() == null || snapshot.compiledScenarios().isEmpty()) {
            throw failure(SnapshotVerificationException.Reason.INVALID_ENVELOPE,
                    "Snapshot required compiled artifacts are missing");
        }
        try {
            snapshot.compiledArtifacts().requireSupported(snapshot.supportsFlows());
        } catch (IllegalArgumentException failure) {
            throw new SnapshotVerificationException(
                    SnapshotVerificationException.Reason.UNSUPPORTED_SCHEMA,
                    "Snapshot compiled artifact versions are unsupported",
                    failure);
        }
        if (!pointer.releaseId().equals(snapshot.releaseId())
                || !expectedScope.environment().equals(snapshot.environment())
                || !expectedScope.app().equals(snapshot.app())) {
            throw failure(SnapshotVerificationException.Reason.SCOPE_MISMATCH,
                    "Snapshot identity or scope does not match Active Pointer");
        }
        if (!pointer.snapshotChecksum().equals(envelope.checksum())
                || !pointer.signatureKeyId().equals(envelope.signatureKeyId())) {
            throw failure(SnapshotVerificationException.Reason.POINTER_INVALID,
                    "Active Pointer metadata does not match Snapshot envelope");
        }
        if (!SIGNATURE_ALGORITHM.equals(envelope.signatureAlgorithm())) {
            throw failure(SnapshotVerificationException.Reason.UNSUPPORTED_SCHEMA,
                    "Snapshot signature algorithm is unsupported");
        }
        if (!envelope.checksum().matches("[a-f0-9]{64}") || envelope.signature().length() > 8192) {
            throw failure(SnapshotVerificationException.Reason.INVALID_ENVELOPE,
                    "Snapshot security metadata is invalid");
        }
    }

    private void verifySignature(PublicKey key, byte[] canonical, String encodedSignature) {
        try {
            byte[] signatureBytes = Base64.getDecoder().decode(encodedSignature);
            Signature verifier = Signature.getInstance(SIGNATURE_ALGORITHM);
            verifier.initVerify(key);
            verifier.update(canonical);
            if (!verifier.verify(signatureBytes)) {
                throw failure(SnapshotVerificationException.Reason.SIGNATURE_INVALID,
                        "Snapshot signature validation failed");
            }
        } catch (IllegalArgumentException | GeneralSecurityException failure) {
            throw new SnapshotVerificationException(
                    SnapshotVerificationException.Reason.SIGNATURE_INVALID,
                    "Snapshot signature validation failed",
                    failure);
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
            input.forEach(value -> result.add(canonicalize(value)));
            return result;
        }
        return input.deepCopy();
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (GeneralSecurityException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    private static boolean constantTimeEquals(String left, String right) {
        if (right == null) {
            return false;
        }
        return MessageDigest.isEqual(
                left.getBytes(java.nio.charset.StandardCharsets.US_ASCII),
                right.getBytes(java.nio.charset.StandardCharsets.US_ASCII));
    }

    private static SnapshotVerificationException failure(
            SnapshotVerificationException.Reason reason,
            String message) {
        return new SnapshotVerificationException(reason, message);
    }
}
