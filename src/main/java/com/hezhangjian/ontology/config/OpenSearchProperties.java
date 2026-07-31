package com.hezhangjian.ontology.config;

import java.net.URI;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "ontology.opensearch")
public record OpenSearchProperties(String host, int port) {
    public URI url() {
        return URI.create("http://" + host + ":" + port);
    }
}
