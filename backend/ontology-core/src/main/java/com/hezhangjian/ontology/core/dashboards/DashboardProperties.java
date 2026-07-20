package com.hezhangjian.ontology.core.dashboards;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "dashboards")
public record DashboardProperties(String tokenSecret, Duration tokenTtl, Duration cacheTtl,
                                  Duration editLockTtl, int suppressionThreshold) { }
