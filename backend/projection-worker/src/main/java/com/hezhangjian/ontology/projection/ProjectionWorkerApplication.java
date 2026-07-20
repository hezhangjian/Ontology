package com.hezhangjian.ontology.projection;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

import com.hezhangjian.ontology.projection.config.ProjectionProperties;

@SpringBootApplication
@EnableConfigurationProperties(ProjectionProperties.class)
public class ProjectionWorkerApplication {
    public static void main(String[] args) {
        SpringApplication application = new SpringApplication(ProjectionWorkerApplication.class);
        application.setWebApplicationType(WebApplicationType.NONE);
        application.run(args);
    }
}
