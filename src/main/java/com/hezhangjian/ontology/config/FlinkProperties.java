package com.hezhangjian.ontology.config;

import java.net.URI;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "ontology.flink")
public record FlinkProperties(String host, int port) {
    public URI url() {
        return URI.create("http://" + host + ":" + port);
    }
}
