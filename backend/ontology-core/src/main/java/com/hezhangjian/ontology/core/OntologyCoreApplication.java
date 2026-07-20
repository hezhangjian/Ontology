package com.hezhangjian.ontology.core;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class OntologyCoreApplication {
    public static void main(String[] args) {
        SpringApplication.run(OntologyCoreApplication.class, args);
    }
}
