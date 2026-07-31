package com.hezhangjian.ontology.config;

import java.net.URI;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "ontology.minio")
public record MinioProperties(
        URI url,
        URI runtimeUrl,
        String accessKey,
        String secretKey,
        String importBucket,
        String warehouseBucket,
        String workloadBucket) {
}
