package com.hezhangjian.ontology.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "ontology.dashboards")
public record DashboardProperties(
        String tokenSecret,
        Duration tokenTtl,
        Duration cacheTtl,
        Duration editLockTtl,
        int suppressionThreshold) {
}
