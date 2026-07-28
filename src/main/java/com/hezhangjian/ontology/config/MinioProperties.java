package com.hezhangjian.ontology.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "ontology.minio")
public record MinioProperties(String host, int port, String accessKey, String secretKey) {
}
