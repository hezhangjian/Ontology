package com.hezhangjian.ontology.core.explorer;

import java.net.URI;
import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "explorer")
public record ExplorerProperties(URI hugegraphUrl, URI opensearchUrl, URI minioUrl,
                                 String minioAccessKey, String minioSecretKey,
                                 String tokenSecret, Duration tokenTtl,
                                 Duration exportTtl, int exportLimit) { }
