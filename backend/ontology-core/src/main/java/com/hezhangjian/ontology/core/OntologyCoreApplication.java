package com.hezhangjian.ontology.core;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@ConfigurationPropertiesScan
@EnableScheduling
public class OntologyCoreApplication {
    public static void main(String[] args) {
        SpringApplication.run(OntologyCoreApplication.class, args);
    }
}
