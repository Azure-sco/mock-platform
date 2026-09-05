package com.xuntian.mock.runtime.engine;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xuntian.mock.common.ErrorCode;
import com.xuntian.mock.common.PlatformException;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CompiledContractTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void ignoresContentTypeParametersAndValidatesBasicJsonSchema() throws Exception {
        CompiledContract contract = contract();
        RuntimeRequest request = request("/orders/42", "application/json; charset=UTF-8", "{\"amount\":12}");

        CompiledContract.ContractMatch match = contract.validate(request, mapper);

        assertThat(match.body().path("amount").asInt()).isEqualTo(12);
    }

    @Test
    void rejectsWrongMethodPathContentTypeAndSchema() {
        CompiledContract contract = contract();

        assertMismatch(() -> contract.validate(request("/orders/42", "text/plain", "{}"), mapper));
        assertMismatch(() -> contract.validate(request("/orders/42/extra", "application/json", "{}"), mapper));
        assertMismatch(() -> contract.validate(request("/orders/42", "application/json", "{\"amount\":\"12\"}"), mapper));
        RuntimeRequest wrongMethod = new RuntimeRequest(
                "TEST", "app", null, null, "P", "A", "GET", "/orders/42", "application/json",
                Map.of(), Map.of(), "{\"amount\":12}".getBytes(), "mr", "trace");
        assertMismatch(() -> contract.validate(wrongMethod, mapper));
    }

    private CompiledContract contract() {
        return new CompiledContract(
                "POST",
                CompiledPathTemplate.compile("/orders/{id}"),
                Set.of("application/json"),
                CompiledJsonSchema.compile(read("""
                        {"type":"object","required":["amount"],"properties":{"amount":{"type":"integer","minimum":1}}}
                        """)),
                CompiledJsonSchema.compile(null),
                null);
    }

    private RuntimeRequest request(String path, String contentType, String body) {
        return new RuntimeRequest(
                "TEST", "app", null, null, "P", "A", "POST", path, contentType,
                Map.of(), Map.of("q", List.of("v")), body.getBytes(), "mr", "trace");
    }

    private com.fasterxml.jackson.databind.JsonNode read(String json) {
        try {
            return mapper.readTree(json);
        } catch (Exception failure) {
            throw new AssertionError(failure);
        }
    }

    private void assertMismatch(org.assertj.core.api.ThrowableAssert.ThrowingCallable action) {
        assertThatThrownBy(action).isInstanceOfSatisfying(PlatformException.class,
                failure -> assertThat(failure.errorCode()).isEqualTo(ErrorCode.MOCK_CONTRACT_MISMATCH));
    }
}
