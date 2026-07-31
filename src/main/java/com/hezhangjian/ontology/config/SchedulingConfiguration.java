package com.hezhangjian.ontology.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.task.ThreadPoolTaskSchedulerBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

@Configuration(proxyBeanMethods = false)
public class SchedulingConfiguration {
    @Bean
    @ConditionalOnMissingBean(TaskScheduler.class)
    ThreadPoolTaskScheduler taskScheduler(ThreadPoolTaskSchedulerBuilder builder) {
        return builder.build();
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnProperty(prefix = "ontology.scheduling", name = "enabled",
            havingValue = "true", matchIfMissing = true)
    @EnableScheduling
    static class BackgroundSchedulingConfiguration {
    }
}
