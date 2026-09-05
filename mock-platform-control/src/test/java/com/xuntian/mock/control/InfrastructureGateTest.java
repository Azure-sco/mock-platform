package com.xuntian.mock.control;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xuntian.mock.common.RedisKeys;
import com.xuntian.mock.control.approval.ApprovalService;
import com.xuntian.mock.control.api.ApiService;
import com.xuntian.mock.control.contract.ContractService;
import com.xuntian.mock.control.callback.CallbackMapper;
import com.xuntian.mock.control.flow.FlowInstanceMapper;
import com.xuntian.mock.control.identity.OperatorContext;
import com.xuntian.mock.control.infrastructure.InfrastructureProbeMapper;
import com.xuntian.mock.control.provider.ProviderService;
import com.xuntian.mock.control.release.ActiveReleaseRecord;
import com.xuntian.mock.control.release.LocalRsaRuntimeSnapshotSigner;
import com.xuntian.mock.control.release.ReleaseCompatibilityPort;
import com.xuntian.mock.control.release.ReleaseOutboxProjector;
import com.xuntian.mock.control.release.ReleaseSecurityPolicyGate;
import com.xuntian.mock.control.release.ReleaseService;
import com.xuntian.mock.control.release.RuntimeActivationAckService;
import com.xuntian.mock.control.release.RuntimeNodeDiscoveryPort;
import com.xuntian.mock.control.release.RuntimeReleaseProjectionPort;
import com.xuntian.mock.control.release.RuntimeSnapshotSigner;
import com.xuntian.mock.control.requestlog.RequestLogPartitionMaintenance;
import com.xuntian.mock.control.scenario.ScenarioScopeAuthorizer;
import com.xuntian.mock.control.scenario.ScenarioService;
import com.xuntian.mock.control.audit.AuditFilter;
import com.xuntian.mock.control.audit.AuditMapper;
import com.xuntian.mock.control.web.DashboardMapper;
import org.junit.jupiter.api.Test;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.io.IOException;
import java.lang.reflect.Proxy;
import java.time.Duration;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(
        classes = MockControlApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                "spring.flyway.enabled=true",
                "management.health.redis.enabled=false",
                "mock.release.outbox.fixed-delay-ms=600000",
                "mock.release.activation-monitor.fixed-delay-ms=600000",
                "mock.config.outbox.fixed-delay-ms=600000",
                "mock.admission.lease.fixed-delay-ms=600000",
                "mock.callback.worker.enabled=false",
                "mock.flow.timer.enabled=false"
        })
@Import(InfrastructureGateTest.GatePorts.class)
class InfrastructureGateTest {

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0.36")
            .withDatabaseName("mock_platform")
            .withUsername("mock")
            .withPassword("mock-test-only");

    @Container
    static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7.2-alpine")
            .withExposedPorts(6379);

    @DynamicPropertySource
    static void infrastructure(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
    }

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @Autowired
    private InfrastructureProbeMapper infrastructureProbeMapper;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private ProviderService providerService;

    @Autowired
    private ApiService apiService;

    @Autowired
    private ContractService contractService;

    @Autowired
    private ScenarioService scenarioService;

    @Autowired
    private ApprovalService approvalService;

    @Autowired
    private ReleaseService releaseService;

    @Autowired
    private ReleaseOutboxProjector releaseOutboxProjector;

    @Autowired
    private RuntimeActivationAckService runtimeActivationAckService;

    @Autowired
    private RuntimeReleaseProjectionPort runtimeReleaseProjection;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private CallbackMapper callbackMapper;

    @Autowired
    private FlowInstanceMapper flowInstanceMapper;

    @Autowired
    private DashboardMapper dashboardMapper;

    @Autowired
    private AuditMapper auditMapper;

    @Test
    void migratesMysqlThroughM3AndEnforcesSchemaAndTransactions() {
        Integer tables = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM information_schema.tables "
                        + "WHERE table_schema = DATABASE() AND table_name IN ("
                        + "'mock_platform_bootstrap','mock_audit_log','mock_provider','mock_api',"
                        + "'mock_contract_version','mock_request_log','mock_request_metric_minute',"
                        + "'mock_scenario','mock_scenario_version','mock_approval_request',"
                        + "'mock_approval_decision','mock_release','mock_release_item',"
                        + "'mock_active_release','mock_release_activation','mock_release_outbox',"
                        + "'mock_activation_target_node','mock_runtime_activation_ack')",
                Integer.class);

        assertThat(tables).isEqualTo(18);
        assertThat(jdbcTemplate.queryForList(
                "SELECT version FROM flyway_schema_history WHERE success = 1 ORDER BY installed_rank",
                String.class)).containsExactly("1", "2", "3");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM information_schema.tables "
                        + "WHERE table_schema = DATABASE() AND table_name IN ("
                        + "'mock_flow_definition','mock_flow_definition_version','mock_admin_operation',"
                        + "'mock_flow_instance','mock_request_execution','mock_flow_event',"
                        + "'mock_callback_task','mock_callback_attempt')",
                Integer.class)).isEqualTo(8);
        assertM1Schema();
        assertM2Schema();
        assertV4Schema();
        assertThat(AopUtils.isAopProxy(providerService)).isTrue();
        assertThat(AopUtils.isAopProxy(apiService)).isTrue();
        assertThat(AopUtils.isAopProxy(contractService)).isTrue();
        assertThat(infrastructureProbeMapper.selectOne()).isEqualTo(1);
        assertThat(callbackMapper.lockClaimable(Instant.now(), 1)).isEmpty();
        assertThat(flowInstanceMapper.lockNextDue(Instant.now())).isNull();
        jdbcTemplate.update("""
                INSERT INTO mock_request_metric_minute (
                    bucket_start, request_count, matched_count,
                    latency_6_10_count, latency_11_25_count, max_duration_ms
                ) VALUES (TIMESTAMP(DATE_FORMAT(CURRENT_TIMESTAMP, '%Y-%m-%d %H:%i:00')), 2, 2, 1, 1, 11)
                """);
        DashboardMapper.DashboardMetrics metrics = dashboardMapper.selectMetrics();
        assertThat(metrics.requests()).isEqualTo(2);
        assertThat(metrics.matchedRequests()).isEqualTo(2);
        assertThat(metrics.p95DurationMs()).isEqualTo(11);
        assertThat(auditMapper.selectPage(
                new AuditFilter(null, null, null, null, null, null, null), 20, 0))
                .singleElement()
                .satisfies(record -> {
                    assertThat(record.requestId()).isEqualTo("req-1");
                    assertThat(record.operator()).isEqualTo("admin");
                });

        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        assertThatThrownBy(() -> transaction.executeWithoutResult(status -> {
            jdbcTemplate.update(
                    "INSERT INTO mock_platform_bootstrap (id, schema_version) VALUES (?, ?)",
                    2L,
                    "ROLLBACK-PROBE");
            throw new IllegalStateException("rollback probe");
        })).isInstanceOf(IllegalStateException.class);
        Integer rolledBackRows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM mock_platform_bootstrap WHERE id = 2",
                Integer.class);
        assertThat(rolledBackRows).isZero();

        OperatorContext operator = new OperatorContext("admin", Set.of("MOCK_ADMIN"), "req-rollback");
        assertThatThrownBy(() -> transaction.executeWithoutResult(status -> {
            providerService.create(
                    new ProviderService.CreateCommand("CPS", "CPS", "team-b", "ENABLED"),
                    operator);
            throw new IllegalStateException("rollback provider and audit");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM mock_provider WHERE provider_code = 'CPS'",
                Integer.class)).isZero();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM mock_audit_log WHERE request_id = 'req-rollback'",
                Integer.class)).isZero();
    }

    @Test
    void maintainsAndDropsRequestLogPartitionsOnMysql8() {
        List<String> before = jdbcTemplate.queryForList(
                "SELECT partition_name FROM information_schema.partitions "
                        + "WHERE table_schema=DATABASE() AND table_name='mock_request_log' "
                        + "ORDER BY partition_ordinal_position",
                String.class);
        assertThat(before).contains("p_legacy", "p20260824", "p20261001", "p_future");

        insertRequestLog("partition-old-legacy", "2026-08-23 12:00:00");
        insertRequestLog("partition-old-day", "2026-08-24 12:00:00");
        jdbcTemplate.update("""
                INSERT INTO mock_request_metric_minute (bucket_start, request_count)
                VALUES ('2026-08-23 12:00:00', 1)
                """);
        RequestLogPartitionMaintenance maintenance = new RequestLogPartitionMaintenance(
                jdbcTemplate,
                Clock.fixed(Instant.parse("2026-09-02T00:00:00Z"), ZoneOffset.UTC),
                31,
                7);

        RequestLogPartitionMaintenance.MaintenanceResult result = maintenance.maintainNow();

        assertThat(result.lockAcquired()).isTrue();
        assertThat(result.addedPartitions()).isEqualTo(2);
        assertThat(result.droppedPartitions()).isEqualTo(2);
        assertThat(result.legacyTruncated()).isTrue();
        assertThat(result.deletedMetricBuckets()).isEqualTo(1);
        List<String> after = jdbcTemplate.queryForList(
                "SELECT partition_name FROM information_schema.partitions "
                        + "WHERE table_schema=DATABASE() AND table_name='mock_request_log'",
                String.class);
        assertThat(after)
                .doesNotContain("p20260824", "p20260825")
                .contains("p20261002", "p20261003", "p_future");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM mock_request_log WHERE id LIKE 'partition-old-%'",
                Integer.class)).isZero();
    }

    @Test
    void connectsToRedisAndHonorsTtl() {
        String gateKey = RedisKeys.key("m0", "gate");
        redisTemplate.opsForValue().set(gateKey, "ok", Duration.ofMinutes(1));

        assertThat(gateKey).startsWith("third-party-mock:");
        assertThat(redisTemplate.opsForValue().get(gateKey)).isEqualTo("ok");
        assertThat(redisTemplate.getExpire(gateKey)).isPositive();
    }

    @Test
    void completesScenarioApprovalReleaseProjectionAndRuntimeAckOnRealMysqlAndRedis() throws Exception {
        OperatorContext admin = operator("gate-admin", "gate-provider");
        var provider = providerService.create(
                new ProviderService.CreateCommand("GATE", "M2 Gate Provider", "gate-team", "ENABLED"),
                admin);
        var api = apiService.create(
                new ApiService.CreateCommand(
                        provider.id(), "QUERY", "Gate Query", "POST", "/gate/query",
                        "application/json", "gate-team", "ENABLED"),
                operator("gate-admin", "gate-api"));

        JsonNode requestSchema = objectMapper.readTree("""
                {"type":"object"}
                """);
        JsonNode responseSchema = objectMapper.readTree("""
                {"type":"object","properties":{"ok":{"type":"boolean"}},"required":["ok"]}
                """);
        var contract = contractService.create(
                api.id(),
                new ContractService.CreateCommand(
                        requestSchema, responseSchema, null, null, null, null, "MANUAL", null),
                operator("gate-admin", "gate-contract-create"));
        contractService.validate(contract.id(), operator("gate-admin", "gate-contract-validate"));
        contract = contractService.publish(contract.id(), operator("gate-admin", "gate-contract-publish"));
        assertThat(contract.status()).isEqualTo("PUBLISHED");

        var scenario = scenarioService.create(
                new ScenarioService.CreateCommand(
                        "gate-success", "Gate Success", provider.id(), api.id()),
                operator("gate-admin", "gate-scenario-create"));
        JsonNode scope = objectMapper.readTree("""
                {"environments":["TEST"],"apps":["gate-app"],"tenants":[],"testAccounts":[]}
                """);
        JsonNode rules = objectMapper.readTree("[]");
        JsonNode response = objectMapper.readTree("""
                {"httpStatus":200,"headers":{"Content-Type":"application/json"},
                 "bodyTemplate":"{\\\"ok\\\":true}","variableDefaults":{}}
                """);
        var scenarioVersion = scenarioService.createVersion(
                scenario.id(),
                new ScenarioService.CreateVersionCommand(
                        contract.id(), null, 100, null, null, scope, rules, response,
                        objectMapper.createArrayNode()),
                operator("gate-admin", "gate-scenario-version"));
        scenarioVersion = scenarioService.validate(
                scenarioVersion.id(), operator("gate-admin", "gate-scenario-validate"));
        assertThat(scenarioVersion.status()).isEqualTo("VALIDATED");
        assertThat(scenarioVersion.validationStatus()).isEqualTo("VALID");

        var approval = scenarioService.submitApproval(
                scenarioVersion.id(), operator("gate-admin", "gate-approval-submit"));
        approval = approvalService.decide(
                approval.id(), "APPROVE", "verified by integration gate",
                operator("gate-reviewer", "gate-approval-decision"));
        assertThat(approval.status()).isEqualTo("APPROVED");

        var release = releaseService.create(
                new ReleaseService.CreateCommand(
                        "gate-release-v1", "TEST", "gate-app",
                        List.of(scenarioVersion.id()), "real infrastructure gate"),
                operator("gate-admin", "gate-release-create"));
        assertThat(release.release().status()).isEqualTo("READY");
        byte[] snapshotBytes = runtimeReleaseProjection.readImmutableSnapshot(release.release().id());
        assertThat(snapshotBytes).isNotEmpty();
        JsonNode envelope = objectMapper.readTree(snapshotBytes);
        assertThat(envelope.path("checksum").asText()).isEqualTo(release.release().checksum());
        assertThat(envelope.path("snapshot").path("schemaVersion").asText()).isEqualTo("2");

        var activation = releaseService.publish(
                release.release().id(), 0L, operator("gate-admin", "gate-release-publish"));
        assertThat(activation.activation().status()).isEqualTo("PENDING");
        assertThat(activation.targets()).extracting("runtimeNodeId").containsExactly("runtime-gate-1");

        releaseOutboxProjector.projectOne();
        String pointerJson = redisTemplate.opsForValue().get("mock:active-release:TEST:gate-app");
        assertThat(pointerJson).isNotBlank();
        JsonNode pointer = objectMapper.readTree(pointerJson);
        assertThat(pointer.path("releaseId").asText()).isEqualTo(release.release().id());
        assertThat(pointer.path("activationVersion").asLong()).isEqualTo(1L);
        assertThat(pointer.path("snapshotChecksum").asText()).isEqualTo(release.release().checksum());
        assertThat(pointer.path("signatureKeyId").asText()).isEqualTo(release.release().signatureKeyId());

        var ack = runtimeActivationAckService.acknowledge(
                new RuntimeActivationAckService.AckCommand(
                        "TEST", "gate-app", "runtime-gate-1", release.release().id(),
                        1L, "READY", null));
        assertThat(ack.activationStatus()).isEqualTo("APPLIED");
        var applied = releaseService.activation(activation.activation().id());
        assertThat(applied.activation().status()).isEqualTo("APPLIED");
        assertThat(applied.targets()).singleElement().satisfies(target -> {
            assertThat(target.required()).isTrue();
            assertThat(target.status()).isEqualTo("READY");
        });
        ActiveReleaseRecord active = releaseService.active("TEST", "gate-app");
        assertThat(active.releaseId()).isEqualTo(release.release().id());
        assertThat(active.activationVersion()).isEqualTo(1L);
        assertThat(active.state()).isEqualTo("APPLIED");
        assertThat(releaseService.find(release.release().id()).release().status()).isEqualTo("PUBLISHED");
        assertThat(scenarioService.requireVersion(scenarioVersion.id()).status()).isEqualTo("PUBLISHED");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM mock_runtime_activation_ack "
                        + "WHERE environment='TEST' AND app_code='gate-app' "
                        + "AND runtime_node_id='runtime-gate-1' AND activation_version=1",
                Integer.class)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM mock_release_outbox WHERE activation_id=? AND status='PROJECTED'",
                Integer.class, activation.activation().id())).isEqualTo(1);
    }

    private OperatorContext operator(String operator, String requestId) {
        return new OperatorContext(operator, Set.of("MOCK_ADMIN", "MOCK_APPROVER"), requestId);
    }

    private void insertRequestLog(String id, String createdAt) {
        jdbcTemplate.update(
                "INSERT INTO mock_request_log "
                        + "(id, mock_request_id, environment, app_code, provider_code, api_code, http_method, path, "
                        + "match_result, duration_ms, expire_at, created_at) "
                        + "VALUES (?, ?, 'TEST', 'partition-gate', 'GATE', 'PARTITION', 'GET', '/partition', "
                        + "'MATCHED', 1, DATE_ADD(?, INTERVAL 7 DAY), ?)",
                id,
                id,
                createdAt,
                createdAt);
    }

    private void assertM1Schema() {
        List<String> auditColumns = jdbcTemplate.queryForList(
                "SELECT column_name FROM information_schema.columns "
                        + "WHERE table_schema = DATABASE() AND table_name = 'mock_audit_log'",
                String.class);
        assertThat(auditColumns).contains(
                "operator",
                "action",
                "object_type",
                "object_id",
                "object_checksum",
                "before_json_masked",
                "after_json_masked",
                "result",
                "reason");

        List<String> primaryKey = jdbcTemplate.queryForList(
                "SELECT column_name FROM information_schema.statistics "
                        + "WHERE table_schema = DATABASE() AND table_name = 'mock_request_log' "
                        + "AND index_name = 'PRIMARY' ORDER BY seq_in_index",
                String.class);
        assertThat(primaryKey).containsExactly("created_at", "id", "created_day");
        Integer idIndex = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM information_schema.statistics "
                        + "WHERE table_schema = DATABASE() AND table_name = 'mock_request_log' "
                        + "AND index_name = 'idx_request_log_id' AND column_name = 'id'",
                Integer.class);
        assertThat(idIndex).isEqualTo(1);

        jdbcTemplate.update(
                "INSERT INTO mock_provider "
                        + "(provider_code, provider_name, owner, status, created_by, updated_by) "
                        + "VALUES ('OA', 'Office Automation', 'team-a', 'ENABLED', 'admin', 'admin')");
        Long providerId = jdbcTemplate.queryForObject(
                "SELECT id FROM mock_provider WHERE provider_code = 'OA'",
                Long.class);
        jdbcTemplate.update(
                "INSERT INTO mock_api "
                        + "(provider_id, api_code, api_name, http_method, path, content_type, owner, status, created_by, updated_by) "
                        + "VALUES (?, 'CREATE', 'Create', 'POST', '/oa/create', 'application/json', 'team-a', 'ENABLED', 'admin', 'admin')",
                providerId);
        Long apiId = jdbcTemplate.queryForObject(
                "SELECT id FROM mock_api WHERE provider_id = ? AND api_code = 'CREATE'",
                Long.class,
                providerId);
        jdbcTemplate.update(
                "INSERT INTO mock_contract_version "
                        + "(api_id, version_no, status, request_schema_json, response_schema_json, source_type, checksum, created_by) "
                        + "VALUES (?, 1, 'DRAFT', JSON_OBJECT('type','object'), JSON_OBJECT('type','object'), 'MANUAL', ?, 'admin')",
                apiId,
                "a".repeat(64));
        assertThatThrownBy(() -> jdbcTemplate.update(
                "INSERT INTO mock_contract_version "
                        + "(api_id, version_no, status, request_schema_json, response_schema_json, source_type, checksum, created_by) "
                        + "VALUES (?, 2, 'DRAFT', JSON_OBJECT('type','object'), JSON_OBJECT('type','object'), 'MANUAL', ?, 'admin')",
                apiId,
                "a".repeat(64)))
                .isInstanceOf(DataIntegrityViolationException.class);

        jdbcTemplate.update(
                "INSERT INTO mock_audit_log "
                        + "(request_id, `operator`, `action`, object_type, object_id, result) "
                        + "VALUES ('req-1', 'admin', 'PROVIDER_CREATE', 'PROVIDER', '1', 'SUCCESS')");
        assertThatThrownBy(() -> jdbcTemplate.update(
                "INSERT INTO mock_audit_log "
                        + "(request_id, `operator`, `action`, object_type, object_id, result) "
                        + "VALUES ('req-1', 'admin', 'PROVIDER_CREATE', 'PROVIDER', '1', 'SUCCESS')"))
                .isInstanceOf(DataIntegrityViolationException.class);

        jdbcTemplate.update(
                "INSERT INTO mock_request_log "
                        + "(id, mock_request_id, environment, app_code, provider_code, api_code, http_method, path, "
                        + "match_result, duration_ms, expire_at, created_at) "
                        + "VALUES ('log-1', 'mr-1', 'TEST', 'sample', 'OA', 'CREATE', 'POST', '/oa/create', "
                        + "'MATCHED', 10, '2026-09-07 00:00:00', '2026-08-31 00:00:00')");
        jdbcTemplate.update(
                "INSERT INTO mock_request_log "
                        + "(id, mock_request_id, environment, app_code, provider_code, api_code, http_method, path, "
                        + "match_result, duration_ms, expire_at, created_at) "
                        + "VALUES ('log-1', 'mr-1', 'TEST', 'sample', 'OA', 'CREATE', 'POST', '/oa/create', "
                        + "'MATCHED', 11, '2026-09-08 00:00:00', '2026-09-01 00:00:00')");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM mock_request_log WHERE id = 'log-1'",
                Integer.class)).isEqualTo(2);
    }

    private void assertM2Schema() {
        List<String> releaseColumns = jdbcTemplate.queryForList(
                "SELECT column_name FROM information_schema.columns "
                        + "WHERE table_schema = DATABASE() AND table_name = 'mock_release'",
                String.class);
        assertThat(releaseColumns).contains(
                "snapshot_json", "snapshot_bytes", "checksum", "signature",
                "signature_key_id", "signature_algorithm");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT data_type FROM information_schema.columns "
                        + "WHERE table_schema = DATABASE() AND table_name = 'mock_release' "
                        + "AND column_name = 'snapshot_bytes'",
                String.class)).isEqualTo("longblob");

        List<String> activePrimaryKey = jdbcTemplate.queryForList(
                "SELECT column_name FROM information_schema.statistics "
                        + "WHERE table_schema = DATABASE() AND table_name = 'mock_active_release' "
                        + "AND index_name = 'PRIMARY' ORDER BY seq_in_index",
                String.class);
        assertThat(activePrimaryKey).containsExactly("environment", "app_code");

        List<String> outboxColumns = jdbcTemplate.queryForList(
                "SELECT column_name FROM information_schema.columns "
                        + "WHERE table_schema = DATABASE() AND table_name = 'mock_release_outbox'",
                String.class);
        assertThat(outboxColumns).contains(
                "payload_bytes", "lease_owner", "lease_until", "fencing_token", "attempt_count");

        List<String> ackUnique = jdbcTemplate.queryForList(
                "SELECT column_name FROM information_schema.statistics "
                        + "WHERE table_schema = DATABASE() AND table_name = 'mock_runtime_activation_ack' "
                        + "AND index_name = 'uk_runtime_activation_ack' ORDER BY seq_in_index",
                String.class);
        assertThat(ackUnique).containsExactly(
                "environment", "app_code", "runtime_node_id", "activation_version");
    }

    private void assertV4Schema() {
        Integer tables = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = DATABASE() "
                        + "AND table_name IN ('mock_security_policy_version','mock_security_policy_binding',"
                        + "'mock_config_publish_outbox','mock_runtime_policy_ack','mock_sdk_config_envelope',"
                        + "'mock_sdk_config_activation','mock_sdk_config_target_instance',"
                        + "'mock_active_sdk_config','mock_sdk_config_event')",
                Integer.class);
        assertThat(tables).isEqualTo(9);
    }

    @TestConfiguration
    static class GatePorts {

        @Bean
        @Primary
        @ConditionalOnProperty(name = "mock.integration.redis-container-exec-transport", havingValue = "true")
        StringRedisTemplate gateStringRedisTemplate() {
            return new ContainerExecStringRedisTemplate(REDIS);
        }

        @Bean
        @Primary
        RuntimeSnapshotSigner gateRuntimeSnapshotSigner() {
            return new LocalRsaRuntimeSnapshotSigner();
        }

        @Bean
        @Primary
        RuntimeNodeDiscoveryPort gateRuntimeNodeDiscovery() {
            return new RuntimeNodeDiscoveryPort() {
                @Override
                public List<RuntimeNode> registeredReadyNodes(String environment, String app) {
                    return List.of(new RuntimeNode("runtime-gate-1"));
                }

                @Override
                public boolean continuouslyDeregistered(
                        String environment, String app, String nodeId, Duration duration) {
                    return false;
                }
            };
        }

        @Bean
        @Primary
        ReleaseCompatibilityPort gateReleaseCompatibility() {
            return (environment, app, releaseId) -> { };
        }

        @Bean
        @Primary
        ReleaseSecurityPolicyGate gateReleaseSecurityPolicy() {
            return (environment, app) -> { };
        }

        @Bean
        @Primary
        ScenarioScopeAuthorizer gateScenarioScopeAuthorizer() {
            return (operator, tenants, testAccounts) -> { };
        }
    }

    /**
     * Codex-hosted Windows JVMs cannot open Netty's internal selector loopback. This opt-in test
     * transport still runs every command, including the production projection Lua script, against
     * the real Redis container. Normal CI does not set the property and continues to use Lettuce.
     */
    static final class ContainerExecStringRedisTemplate extends StringRedisTemplate {

        private final GenericContainer<?> redis;
        private final ValueOperations<String, String> values;

        @SuppressWarnings("unchecked")
        ContainerExecStringRedisTemplate(GenericContainer<?> redis) {
            this.redis = redis;
            this.values = (ValueOperations<String, String>) Proxy.newProxyInstance(
                    ValueOperations.class.getClassLoader(),
                    new Class<?>[]{ValueOperations.class},
                    (proxy, method, args) -> invokeValueOperation(method.getName(), args));
        }

        @Override
        public void afterPropertiesSet() {
            // All supported operations are dispatched through redis-cli inside the real container.
        }

        @Override
        public ValueOperations<String, String> opsForValue() {
            return values;
        }

        @Override
        public Long getExpire(String key) {
            return Long.valueOf(command("TTL", key));
        }

        @Override
        public Long convertAndSend(String channel, Object message) {
            return Long.valueOf(command("PUBLISH", channel, String.valueOf(message)));
        }

        @Override
        @SuppressWarnings("unchecked")
        public <T> T execute(RedisScript<T> script, List<String> keys, Object... args) {
            List<String> command = new ArrayList<>();
            command.add("EVAL");
            command.add(script.getScriptAsString());
            command.add(Integer.toString(keys.size()));
            command.addAll(keys);
            Arrays.stream(args).map(String::valueOf).forEach(command::add);
            String raw = command(command.toArray(String[]::new));
            if (script.getResultType() == Long.class) {
                return (T) Long.valueOf(raw);
            }
            if (script.getResultType() == Boolean.class) {
                return (T) Boolean.valueOf(!"0".equals(raw));
            }
            return (T) raw;
        }

        private Object invokeValueOperation(String method, Object[] args) {
            return switch (method) {
                case "set" -> {
                    if (args.length == 2) {
                        command("SET", String.valueOf(args[0]), String.valueOf(args[1]));
                    } else if (args.length == 3 && args[2] instanceof Duration duration) {
                        command("SET", String.valueOf(args[0]), String.valueOf(args[1]),
                                "PX", Long.toString(duration.toMillis()));
                    } else if (args.length == 4 && args[3] instanceof TimeUnit unit) {
                        long millis = unit.toMillis(((Number) args[2]).longValue());
                        command("SET", String.valueOf(args[0]), String.valueOf(args[1]),
                                "PX", Long.toString(millis));
                    } else {
                        throw unsupported(method, args);
                    }
                    yield null;
                }
                case "setIfAbsent" -> {
                    if (args.length != 2) {
                        throw unsupported(method, args);
                    }
                    yield "OK".equals(command(
                            "SET", String.valueOf(args[0]), String.valueOf(args[1]), "NX"));
                }
                case "get" -> {
                    String value = command("GET", String.valueOf(args[0]));
                    yield value.isEmpty() ? null : value;
                }
                case "getOperations" -> this;
                case "toString" -> getClass().getSimpleName() + ".ValueOperations";
                case "hashCode" -> System.identityHashCode(values);
                case "equals" -> values == args[0];
                default -> throw unsupported(method, args);
            };
        }

        private UnsupportedOperationException unsupported(String method, Object[] args) {
            return new UnsupportedOperationException(
                    "Redis integration bridge does not implement " + method + "/" + args.length);
        }

        private String command(String... args) {
            String[] redisCommand = new String[args.length + 2];
            redisCommand[0] = "redis-cli";
            redisCommand[1] = "--raw";
            System.arraycopy(args, 0, redisCommand, 2, args.length);
            try {
                var result = redis.execInContainer(redisCommand);
                if (result.getExitCode() != 0) {
                    throw new IllegalStateException("Redis command failed: " + result.getStderr().strip());
                }
                return result.getStdout().stripTrailing();
            } catch (IOException e) {
                throw new IllegalStateException("Failed to execute Redis integration command", e);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Redis integration command was interrupted", e);
            }
        }
    }
}
