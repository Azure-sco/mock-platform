package com.xuntian.mock.control;

import com.xuntian.mock.common.RedisKeys;
import com.xuntian.mock.control.infrastructure.InfrastructureProbeMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(
        classes = MockControlApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                "spring.flyway.enabled=true",
                "management.health.redis.enabled=false"
        })
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

    @Test
    void migratesMysqlAndConnectsToRedis() {
        Integer tables = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM information_schema.tables "
                        + "WHERE table_schema = DATABASE() AND table_name = 'mock_platform_bootstrap'",
                Integer.class);
        String gateKey = RedisKeys.key("m0", "gate");
        redisTemplate.opsForValue().set(gateKey, "ok", Duration.ofMinutes(1));

        assertThat(tables).isEqualTo(1);
        assertThat(infrastructureProbeMapper.selectOne()).isEqualTo(1);
        assertThat(gateKey).startsWith("third-party-mock:");
        assertThat(redisTemplate.opsForValue().get(gateKey)).isEqualTo("ok");
        assertThat(redisTemplate.getExpire(gateKey)).isPositive();

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
    }
}
