package com.xuntian.mock.runtime.flow;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.aop.support.AopUtils;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.dao.annotation.PersistenceExceptionTranslationPostProcessor;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class JdbcFlowStoreContextTest {

    @Test
    void initializesRepositoryWithSpringExceptionTranslationProxy() {
        new ApplicationContextRunner()
                .withBean(PersistenceExceptionTranslationPostProcessor.class)
                .withBean(JdbcTemplate.class, () -> mock(JdbcTemplate.class))
                .withBean(ObjectMapper.class, ObjectMapper::new)
                .withUserConfiguration(JdbcFlowStore.class)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(AopUtils.isAopProxy(context.getBean(JdbcFlowStore.class))).isTrue();
                });
    }
}
