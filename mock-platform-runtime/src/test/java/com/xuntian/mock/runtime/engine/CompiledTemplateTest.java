package com.xuntian.mock.runtime.engine;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xuntian.mock.common.ErrorCode;
import com.xuntian.mock.common.PlatformException;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CompiledTemplateTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void escapesStringsInsertsTypedJsonAndFixesTimeAndUuidPerRequest() throws Exception {
        CompiledTemplate template = CompiledTemplate.compile(
                "{\"name\":\"${request.header.X-Name}\",\"amount\":${json:request.body.$.amount},"
                        + "\"at1\":\"${now.iso}\",\"at2\":\"${now.iso}\","
                        + "\"u1\":\"${uuid}\",\"u2\":\"${uuid}\"}",
                Map.of(), mapper);
        RuntimeRequest request = request(
                Map.of("X-Name", List.of("a\"b\\c")),
                "{\"amount\":12.5}".getBytes());
        Instant now = Instant.parse("2026-08-31T01:02:03Z");
        UUID uuid = UUID.fromString("11111111-2222-3333-4444-555555555555");

        CompiledTemplate.RenderedTemplate rendered = template.render(new TemplateContext(
                request, mapper.readTree(request.body()), null, Map.of(), null, now, uuid));

        assertThat(rendered.json().path("name").asText()).isEqualTo("a\"b\\c");
        assertThat(rendered.json().path("amount").decimalValue()).isEqualByComparingTo("12.5");
        assertThat(rendered.json().path("at1")).isEqualTo(rendered.json().path("at2"));
        assertThat(rendered.json().path("u1")).isEqualTo(rendered.json().path("u2"));
    }

    @Test
    void rejectsMissingVariableAndSensitiveHeaderToken() {
        CompiledTemplate template = CompiledTemplate.compile(
                "{\"missing\":\"${request.query.absent}\"}", Map.of(), mapper);

        assertThatThrownBy(() -> template.render(new TemplateContext(
                request(Map.of(), new byte[0]), mapper.nullNode(), null, Map.of(), null,
                Instant.EPOCH, UUID.randomUUID())))
                .isInstanceOfSatisfying(PlatformException.class, failure -> assertThat(failure.errorCode())
                        .isEqualTo(ErrorCode.MOCK_TEMPLATE_VARIABLE_MISSING));
        assertThatThrownBy(() -> CompiledTemplate.compile(
                "{\"secret\":\"${request.header.Authorization}\"}", Map.of(), mapper))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void runtimeRequestStripsCredentialHeadersBeforeMatchingOrRendering() {
        RuntimeRequest request = request(Map.of(
                "Authorization", List.of("MockApp secret-token"),
                "Cookie", List.of("sid=secret"),
                "X-Signature", List.of("signature"),
                "X-App-Secret", List.of("app-secret"),
                "X-Business-Tag", List.of("settlement")), new byte[0]);

        assertThat(request.headers()).containsOnlyKeys("X-Business-Tag");
        assertThat(request.firstHeader("Authorization")).isEmpty();
    }

    private RuntimeRequest request(Map<String, List<String>> headers, byte[] body) {
        return new RuntimeRequest(
                "TEST", "sample-jdk17", null, null, "CPS_EQB", "API", "POST", "/path",
                "application/json", headers, Map.of(), body, "mr-1", "trace-1");
    }
}
