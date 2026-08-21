package com.xuntian.mock.control;

import com.xuntian.mock.control.identity.OperatorContextHolder;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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

    @Test
    void exposesPublicPhaseZeroHealthEnvelope() throws Exception {
        mockMvc.perform(get("/api/platform/health"))
                .andExpect(status().isOk())
                .andExpect(header().exists("X-Request-Id"))
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.service").value("mock-platform-control"))
                .andExpect(jsonPath("$.data.phase").value("M0"));
    }

    @Test
    void rejectsManagementRequestWithoutLocalIdentity() throws Exception {
        mockMvc.perform(get("/api/dashboard/summary"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }

    @Test
    void enforcesRoleAndCleansOperatorContextAfterRequest() throws Exception {
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
}
