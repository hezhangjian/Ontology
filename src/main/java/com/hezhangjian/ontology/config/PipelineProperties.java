package com.hezhangjian.ontology.config;

import java.nio.file.Path;
import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "ontology.pipelines")
public record PipelineProperties(
        Path jobJar,
        Duration workloadTtl,
        String platformTopic,
        Duration workloadCleanupInterval,
        String workloadKey,
        String controlTopic) {
}
