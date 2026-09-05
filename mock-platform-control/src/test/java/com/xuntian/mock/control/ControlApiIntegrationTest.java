package com.xuntian.mock.control;

import com.xuntian.mock.control.api.ApiMapper;
import com.xuntian.mock.control.api.ApiRecord;
import com.xuntian.mock.control.audit.AuditMapper;
import com.xuntian.mock.control.approval.ApprovalMapper;
import com.xuntian.mock.control.contract.ContractVersionRecord;
import com.xuntian.mock.control.contract.ContractVersionMapper;
import com.xuntian.mock.control.flow.FlowDefinitionMapper;
import com.xuntian.mock.control.flow.FlowInstanceMapper;
import com.xuntian.mock.control.identity.OperatorContextHolder;
import com.xuntian.mock.control.provider.ProviderMapper;
import com.xuntian.mock.control.provider.ProviderRecord;
import com.xuntian.mock.control.requestlog.RequestLogMapper;
import com.xuntian.mock.control.release.ReleaseMapper;
import com.xuntian.mock.control.callback.CallbackMapper;
import com.xuntian.mock.control.scenario.ScenarioMapper;
import com.xuntian.mock.control.outbox.ConfigPublishOutboxMapper;
import com.xuntian.mock.control.sdkconfig.SdkConfigMapper;
import com.xuntian.mock.control.securitypolicy.SecurityPolicyMapper;
import com.xuntian.mock.control.web.DashboardMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.mock.web.MockMultipartFile;

import java.time.Instant;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@SpringBootTest(
        classes = MockControlApplication.class,
        properties = {
                "spring.flyway.enabled=false",
                "management.health.redis.enabled=false",
                "spring.autoconfigure.exclude="
                        + "org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration,"
                        + "org.mybatis.spring.boot.autoconfigure.MybatisAutoConfiguration,"
                        + "org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration,"
                        + "org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration"
        })
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ControlApiIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private ProviderMapper providerMapper;

    @MockBean
    private ApiMapper apiMapper;

    @MockBean
    private ContractVersionMapper contractVersionMapper;

    @MockBean
    private RequestLogMapper requestLogMapper;

    @MockBean
    private AuditMapper auditMapper;

    @MockBean
    private ApprovalMapper approvalMapper;

    @MockBean
    private ScenarioMapper scenarioMapper;

    @MockBean
    private FlowDefinitionMapper flowDefinitionMapper;

    @MockBean
    private FlowInstanceMapper flowInstanceMapper;

    @MockBean
    private ReleaseMapper releaseMapper;

    @MockBean
    private SecurityPolicyMapper securityPolicyMapper;

    @MockBean
    private SdkConfigMapper sdkConfigMapper;

    @MockBean
    private ConfigPublishOutboxMapper configPublishOutboxMapper;

    @MockBean
    private CallbackMapper callbackMapper;

    @MockBean
    private DashboardMapper dashboardMapper;

    @Test
    void exposesPublicPhaseZeroHealthEnvelope() throws Exception {
        mockMvc.perform(get("/api/platform/health"))
                .andExpect(status().isOk())
                .andExpect(header().exists("X-Request-Id"))
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.service").value("mock-platform-control"))
                .andExpect(jsonPath("$.data.phase").value("M1"));
    }

    @Test
    void rejectsManagementRequestWithoutLocalIdentity() throws Exception {
        mockMvc.perform(get("/api/dashboard/summary"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }

    @Test
    void enforcesRoleAndCleansOperatorContextAfterRequest() throws Exception {
        when(dashboardMapper.selectMetrics()).thenReturn(new DashboardMapper.DashboardMetrics(
                0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0));
        mockMvc.perform(get("/api/dashboard/summary")
                        .header("X-Operator-Id", "tester-01")
                        .header("X-Operator-Roles", "MOCK_VIEWER"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.operator").value("tester-01"))
                .andExpect(jsonPath("$.data.providers").value(0));
        assertThat(OperatorContextHolder.current()).isEmpty();

        mockMvc.perform(get("/api/dashboard/summary")
                        .header("X-Operator-Id", "tester-02")
                        .header("X-Operator-Roles", "UNRELATED"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
        assertThat(OperatorContextHolder.current()).isEmpty();

    }

    @Test
    void requiresAdminRoleForProviderWritesAndReturnsCreatedEnvelope() throws Exception {
        ProviderRecord created = new ProviderRecord(
                11L,
                "OA",
                "Office Automation",
                "team-a",
                "ENABLED",
                "admin-01",
                Instant.parse("2026-08-31T00:00:00Z"),
                "admin-01",
                Instant.parse("2026-08-31T00:00:00Z"));
        when(providerMapper.selectByCode("OA")).thenReturn(created);

        mockMvc.perform(post("/api/admin/v1/providers")
                        .header("X-Operator-Id", "viewer-01")
                        .header("X-Operator-Roles", "MOCK_VIEWER")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"providerCode":"OA","providerName":"Office Automation","owner":"team-a"}
                                """))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
        verify(providerMapper, never()).insert(any(), any(), any(), any(), any());

        mockMvc.perform(post("/api/admin/v1/providers")
                        .header("X-Operator-Id", "admin-01")
                        .header("X-Operator-Roles", "MOCK_ADMIN")
                        .header("X-Request-Id", "req-admin-create")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"providerCode":"OA","providerName":"Office Automation","owner":"team-a"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.id").value(11))
                .andExpect(jsonPath("$.data.providerCode").value("OA"))
                .andExpect(jsonPath("$.requestId").value("req-admin-create"));
        verify(providerMapper).insert("OA", "Office Automation", "team-a", "ENABLED", "admin-01");
        verify(auditMapper).insert(
                org.mockito.ArgumentMatchers.eq("req-admin-create"),
                org.mockito.ArgumentMatchers.eq("admin-01"),
                org.mockito.ArgumentMatchers.eq("PROVIDER_CREATE"),
                org.mockito.ArgumentMatchers.eq("PROVIDER"),
                org.mockito.ArgumentMatchers.eq("11"),
                org.mockito.ArgumentMatchers.isNull(),
                org.mockito.ArgumentMatchers.isNull(),
                any());
        assertThat(OperatorContextHolder.current()).isEmpty();
    }

    @Test
    void mapsDuplicateProviderAndPublishedContractStateToConflict() throws Exception {
        org.mockito.Mockito.doThrow(new DuplicateKeyException("duplicate"))
                .when(providerMapper)
                .insert("OA", "Office Automation", "team-a", "ENABLED", "admin-01");

        mockMvc.perform(post("/api/admin/v1/providers")
                        .header("X-Operator-Id", "admin-01")
                        .header("X-Operator-Roles", "MOCK_ADMIN")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"providerCode":"OA","providerName":"Office Automation","owner":"team-a"}
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONFLICT"));

        Instant now = Instant.parse("2026-08-31T00:00:00Z");
        when(contractVersionMapper.selectById(7L)).thenReturn(new ContractVersionRecord(
                7L,
                11L,
                1,
                "PUBLISHED",
                "{\"type\":\"object\"}",
                "{\"type\":\"object\"}",
                null,
                null,
                null,
                null,
                "MANUAL",
                null,
                "a".repeat(64),
                "admin-01",
                now,
                "admin-01",
                now));

        mockMvc.perform(post("/api/admin/v1/contracts/7/validate")
                        .header("X-Operator-Id", "admin-01")
                        .header("X-Operator-Roles", "MOCK_ADMIN"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVALID_STATE"));
    }

    @Test
    void createsApiForExistingProviderAndAuditsTheWrite() throws Exception {
        Instant now = Instant.parse("2026-08-31T00:00:00Z");
        when(providerMapper.selectById(11L)).thenReturn(new ProviderRecord(
                11L,
                "OA",
                "Office Automation",
                "team-a",
                "ENABLED",
                "admin-01",
                now,
                "admin-01",
                now));
        when(apiMapper.selectByProviderAndCode(11L, "CREATE")).thenReturn(new ApiRecord(
                21L,
                11L,
                "CREATE",
                "Create document",
                "POST",
                "/oa/documents",
                "application/json",
                "team-a",
                "ENABLED",
                "admin-01",
                now,
                "admin-01",
                now));

        mockMvc.perform(post("/api/admin/v1/apis")
                        .header("X-Operator-Id", "admin-01")
                        .header("X-Operator-Roles", "MOCK_ADMIN")
                        .header("X-Request-Id", "req-api-create")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "providerId":11,
                                  "apiCode":"CREATE",
                                  "apiName":"Create document",
                                  "httpMethod":"POST",
                                  "path":"/oa/documents",
                                  "contentType":"application/json",
                                  "owner":"team-a"
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.id").value(21))
                .andExpect(jsonPath("$.data.httpMethod").value("POST"));
        verify(apiMapper).insert(
                11L,
                "CREATE",
                "Create document",
                "POST",
                "/oa/documents",
                "application/json",
                "team-a",
                "ENABLED",
                "admin-01");
        verify(auditMapper).insert(
                org.mockito.ArgumentMatchers.eq("req-api-create"),
                org.mockito.ArgumentMatchers.eq("admin-01"),
                org.mockito.ArgumentMatchers.eq("API_CREATE"),
                org.mockito.ArgumentMatchers.eq("API"),
                org.mockito.ArgumentMatchers.eq("21"),
                org.mockito.ArgumentMatchers.isNull(),
                org.mockito.ArgumentMatchers.isNull(),
                any());
    }

    @Test
    void importsOpenApiUploadIntoAContractDraft() throws Exception {
        AtomicReference<String> requestSchema = new AtomicReference<>();
        AtomicReference<String> responseSchema = new AtomicReference<>();
        AtomicReference<String> examples = new AtomicReference<>();
        AtomicReference<String> sourceType = new AtomicReference<>();
        AtomicReference<String> sourceHash = new AtomicReference<>();
        AtomicReference<String> checksum = new AtomicReference<>();
        when(apiMapper.lockById(21L)).thenReturn(21L);
        when(contractVersionMapper.nextVersionNo(21L)).thenReturn(1);
        org.mockito.Mockito.doAnswer(invocation -> {
            requestSchema.set(invocation.getArgument(2));
            responseSchema.set(invocation.getArgument(3));
            examples.set(invocation.getArgument(4));
            sourceType.set(invocation.getArgument(8));
            sourceHash.set(invocation.getArgument(9));
            checksum.set(invocation.getArgument(10));
            return 1;
        }).when(contractVersionMapper).insert(
                org.mockito.ArgumentMatchers.eq(21L),
                org.mockito.ArgumentMatchers.eq(1),
                any(), any(), any(), any(), any(), any(), any(), any(), any(),
                org.mockito.ArgumentMatchers.eq("admin-01"));
        when(contractVersionMapper.selectByApiAndVersion(21L, 1)).thenAnswer(ignored ->
                new ContractVersionRecord(
                        31L,
                        21L,
                        1,
                        "DRAFT",
                        requestSchema.get(),
                        responseSchema.get(),
                        examples.get(),
                        null,
                        null,
                        null,
                        sourceType.get(),
                        sourceHash.get(),
                        checksum.get(),
                        "admin-01",
                        Instant.parse("2026-08-31T00:00:00Z"),
                        null,
                        null));
        MockMultipartFile file = new MockMultipartFile(
                "file",
                "health.yaml",
                "application/yaml",
                """
                        openapi: 3.0.3
                        paths:
                          /health:
                            get:
                              responses:
                                '200':
                                  content:
                                    application/json:
                                      schema:
                                        type: object
                                        properties:
                                          status: {type: string}
                        """.getBytes(StandardCharsets.UTF_8));

        mockMvc.perform(multipart("/api/admin/v1/apis/21/contracts/import")
                        .file(file)
                        .header("X-Operator-Id", "admin-01")
                        .header("X-Operator-Roles", "MOCK_ADMIN")
                        .header("X-Request-Id", "req-contract-import"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.id").value(31))
                .andExpect(jsonPath("$.data.status").value("DRAFT"))
                .andExpect(jsonPath("$.data.sourceType").value("OPENAPI"))
                .andExpect(jsonPath("$.data.sourceFileHash").value(
                        org.hamcrest.Matchers.matchesPattern("[a-f0-9]{64}")))
                .andExpect(jsonPath("$.data.responseSchema.properties.status.type").value("string"))
                .andExpect(jsonPath("$.requestId").value("req-contract-import"));
        assertThat(sourceType).hasValue("OPENAPI");
        assertThat(sourceHash.get()).matches("[a-f0-9]{64}");
    }

    @Test
    void internalRuntimeAckUsesServiceIdentityInsteadOfBrowserIdentity() throws Exception {
        mockMvc.perform(post("/api/internal/v1/runtime-activation-acks")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"environment":"TEST","app":"sample","runtimeNodeId":"node-1",
                                 "releaseId":"rel-1","activationVersion":1,"status":"READY"}
                                """))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
        verify(releaseMapper, never()).selectActivationByVersion(
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyLong());
    }
}
