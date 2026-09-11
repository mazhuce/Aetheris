package com.aetheris.workflow.service;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.convert.ApplicationConversionService;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SparkBatchExecutionAdapterContextTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withInitializer(context -> context.getBeanFactory()
                    .setConversionService(ApplicationConversionService.getSharedInstance()))
            .withBean(RestClient.Builder.class, RestClient::builder)
            .withPropertyValues("aetheris.spark-runner.enabled=false")
            .withUserConfiguration(AdapterConfiguration.class);

    @Test
    void applicationContextSelectsTheProductionConstructor() {
        contextRunner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(SparkBatchExecutionAdapter.class);
            assertThat(context.getBean(SparkBatchExecutionAdapter.class).health().status())
                    .isEqualTo("DISABLED");
        });
    }

    @Test
    void runnerEndpointRejectsCredentialsPathsQueriesAndNonHttpSchemes() {
        assertEquals("http://runner.local:18081",
                SparkBatchExecutionAdapter.normalizeBaseUrl("HTTP://RUNNER.LOCAL:18081/"));
        assertThrows(IllegalArgumentException.class,
                () -> SparkBatchExecutionAdapter.normalizeBaseUrl("http://user:secret@runner:18081"));
        assertThrows(IllegalArgumentException.class,
                () -> SparkBatchExecutionAdapter.normalizeBaseUrl("http://runner:18081/jobs"));
        assertThrows(IllegalArgumentException.class,
                () -> SparkBatchExecutionAdapter.normalizeBaseUrl("file:///tmp/runner"));
    }

    @Configuration(proxyBeanMethods = false)
    @Import(SparkBatchExecutionAdapter.class)
    static class AdapterConfiguration {
    }
}
