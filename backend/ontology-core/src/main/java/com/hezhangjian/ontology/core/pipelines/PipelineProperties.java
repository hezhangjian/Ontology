package com.hezhangjian.ontology.core.pipelines;

import java.nio.file.Path;
import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("pipelines")
public record PipelineProperties(String flinkUrl, String internalCoreUrl, Path jobJar,
                                 String workloadToken, Duration grantTtl, String platformTopic) {
}
