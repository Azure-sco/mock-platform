package com.xuntian.mock.control.flow;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xuntian.mock.control.api.ApiRecord;
import com.xuntian.mock.control.api.ApiService;
import com.xuntian.mock.control.contract.ContractVersionMapper;
import com.xuntian.mock.control.contract.ContractVersionRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class FlowDefinitionValidatorTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private ApiService apiService;
    private ContractVersionMapper contractMapper;
    private FlowDefinitionValidator validator;

    @BeforeEach
    void setUp() {
        apiService = mock(ApiService.class);
        contractMapper = mock(ContractVersionMapper.class);
        validator = new FlowDefinitionValidator(objectMapper, apiService, contractMapper);
        when(contractMapper.selectById(101)).thenReturn(contract(101, 11, "PUBLISHED"));
        when(contractMapper.selectById(102)).thenReturn(contract(102, 12, "PUBLISHED"));
        when(contractMapper.selectById(103)).thenReturn(contract(103, 13, "PUBLISHED"));
        when(apiService.require(11)).thenReturn(api(11, "CREATE_API"));
        when(apiService.require(12)).thenReturn(api(12, "QUERY_API"));
        when(apiService.require(13)).thenReturn(api(13, "COMMAND_API"));
    }

    @Test
    void validatesAcyclicFlowWithAllMvpTriggerTypes() throws Exception {
        FlowDefinitionValidator.ValidationOutcome outcome = validator.validate(
                definition(), version(
                        participants(),
                        "[{\"name\":\"amount\",\"type\":\"NUMBER\",\"required\":false,\"initialValue\":{\"source\":\"LITERAL\",\"value\":0}}]",
                        "["
                                + "{\"transitionId\":\"queried\",\"priority\":100,\"from\":\"PROCESSING\",\"to\":\"CHECKED\",\"trigger\":{\"type\":\"QUERY_COUNT\",\"threshold\":2},\"assignments\":[{\"type\":\"INCREMENT_NUMBER\",\"variable\":\"amount\",\"increment\":1}]},"
                                + "{\"transitionId\":\"approved\",\"priority\":90,\"from\":\"CHECKED\",\"to\":\"APPROVED\",\"trigger\":{\"type\":\"REQUEST_FIELD\",\"source\":\"JSON_BODY\",\"path\":\"$.action\",\"operator\":\"EQ\",\"value\":\"approve\"},\"assignments\":[]},"
                                + "{\"transitionId\":\"timed\",\"priority\":80,\"from\":\"APPROVED\",\"to\":\"CALLBACK_PENDING\",\"trigger\":{\"type\":\"TIME\",\"delaySeconds\":1},\"assignments\":[]},"
                                + "{\"transitionId\":\"manual\",\"priority\":70,\"from\":\"CALLBACK_PENDING\",\"to\":\"DONE\",\"trigger\":{\"type\":\"MANUAL\"},\"assignments\":[]}"
                                + "]"));

        assertThat(outcome.valid()).isTrue();
        assertThat(outcome.errors()).isEmpty();
        assertThat(outcome.compiled().path("compilerVersion").asText()).isEqualTo("flow-v1");
    }

    @Test
    void rejectsCyclesAndStaticTransitionConflicts() throws Exception {
        FlowDefinitionValidator.ValidationOutcome outcome = validator.validate(
                definition(), version(
                        participants(),
                        "[]",
                        "["
                                + "{\"transitionId\":\"a\",\"priority\":100,\"from\":\"PROCESSING\",\"to\":\"DONE\",\"trigger\":{\"type\":\"MANUAL\"},\"assignments\":[]},"
                                + "{\"transitionId\":\"b\",\"priority\":100,\"from\":\"PROCESSING\",\"to\":\"FAILED\",\"trigger\":{\"type\":\"MANUAL\"},\"assignments\":[]},"
                                + "{\"transitionId\":\"c\",\"priority\":90,\"from\":\"DONE\",\"to\":\"PROCESSING\",\"trigger\":{\"type\":\"MANUAL\"},\"assignments\":[]}"
                                + "]"));

        assertThat(outcome.valid()).isFalse();
        assertThat(outcome.errors()).extracting(FlowDefinitionValidator.ValidationIssue::code)
                .contains("TRANSITION_CONFLICT", "FLOW_CYCLE");
    }

    @Test
    void rejectsSensitiveBusinessKeyAndUnpublishedContract() throws Exception {
        when(contractMapper.selectById(101)).thenReturn(contract(101, 11, "DRAFT"));
        String invalidParticipants = "["
                + "{\"apiCode\":\"CREATE_API\",\"contractVersionId\":101,\"role\":\"CREATE\",\"createIfAbsent\":true,"
                + "\"businessKeyExtractor\":{\"source\":\"HEADER\",\"path\":\"Authorization\",\"required\":true,\"normalize\":\"TRIM\"}}]";

        FlowDefinitionValidator.ValidationOutcome outcome = validator.validate(
                definition(), version(invalidParticipants, "[]", "[]"));

        assertThat(outcome.valid()).isFalse();
        assertThat(outcome.errors()).extracting(FlowDefinitionValidator.ValidationIssue::code)
                .contains("CONTRACT_NOT_PUBLISHED", "EXTRACTOR_SENSITIVE_HEADER");
    }

    private FlowDefinitionRecord definition() {
        return new FlowDefinitionRecord(
                1, 7, "contract.flow", "Contract Flow", 1, "ENABLED",
                "author", Instant.EPOCH, "author", Instant.EPOCH);
    }

    private FlowDefinitionVersionRecord version(String participants, String variables, String transitions) {
        return new FlowDefinitionVersionRecord(
                21, 1, 1, "DRAFT", "PROCESSING", 86_400,
                participants, variables, transitions, null, "a".repeat(64), "NOT_VALIDATED", null,
                null, null, null, null, "author", Instant.EPOCH);
    }

    private String participants() {
        return "["
                + participant("CREATE_API", 101, "CREATE", true, "JSON_BODY", "$.businessNo") + ","
                + participant("QUERY_API", 102, "QUERY", false, "QUERY", "businessNo") + ","
                + participant("COMMAND_API", 103, "COMMAND", false, "JSON_BODY", "$.businessNo")
                + "]";
    }

    private String participant(
            String apiCode,
            long contractVersionId,
            String role,
            boolean create,
            String source,
            String path) {
        return "{\"apiCode\":\"" + apiCode + "\",\"contractVersionId\":" + contractVersionId
                + ",\"role\":\"" + role + "\",\"createIfAbsent\":" + create
                + ",\"businessKeyExtractor\":{\"source\":\"" + source + "\",\"path\":\""
                + path + "\",\"required\":true,\"normalize\":\"TRIM\"}}";
    }

    private ContractVersionRecord contract(long id, long apiId, String status) {
        return new ContractVersionRecord(
                id, apiId, 1, status, null, "{}", "[]", "[]", "{}", "{}",
                "MANUAL", null, "b".repeat(64), "author", Instant.EPOCH, "publisher", Instant.EPOCH);
    }

    private ApiRecord api(long id, String code) {
        return new ApiRecord(
                id, 7, code, code, "POST", "/" + code.toLowerCase(Locale.ROOT), "application/json",
                "owner", "ENABLED", "author", Instant.EPOCH, "author", Instant.EPOCH);
    }
}
