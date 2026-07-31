package com.hezhangjian.ontology.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

class ComponentPropertiesTest {
    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withInitializer(new ConfigDataApplicationContextInitializer())
            .withPropertyValues(
                    "APPLICATION_TOKEN_SECRET=ontology-development-token-secret",
                    "CONNECTION_MASTER_KEY=MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=",
                    "FLINK_WORKLOAD_KEY=MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=",
                    "MINIO_ROOT_PASSWORD=ontology123",
                    "MINIO_ROOT_USER=ontology")
            .withUserConfiguration(PropertiesConfiguration.class);

    @Test
    void bindsComponentConfigurationWithLocalDevelopmentDefaults() {
        contextRunner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(ActionProperties.class).outboxIntervalMs()).isEqualTo(1000);
            assertThat(context.getBean(AgentProperties.class).coreUrl()).isEqualTo("http://localhost:4242");
            assertThat(context.getBean(FlinkProperties.class).url()).hasToString("http://localhost:8081");
            assertThat(context.getBean(HugeGraphProperties.class))
                    .extracting(HugeGraphProperties::host, HugeGraphProperties::port)
                    .containsExactly("localhost", 8080);
            assertThat(context.getBean(MinioProperties.class).url()).hasToString("http://localhost:9000");
            assertThat(context.getBean(OpenSearchProperties.class).url()).hasToString("http://localhost:9200");
            assertThat(context.getBean(PulsarProperties.class).url()).hasToString("pulsar://localhost:6650");
            assertThat(context.getBean(WebProperties.class).multipartMaxParts()).isEqualTo(100);
        });
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties({
            ActionProperties.class,
            AgentProperties.class,
            ConnectionProperties.class,
            DashboardProperties.class,
            ExplorerProperties.class,
            FlinkProperties.class,
            HugeGraphProperties.class,
            MinioProperties.class,
            OpenSearchProperties.class,
            PipelineProperties.class,
            ProjectionProperties.class,
            PulsarProperties.class,
            WebProperties.class
    })
    static class PropertiesConfiguration {
    }
}
