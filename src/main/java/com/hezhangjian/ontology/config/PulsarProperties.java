package com.hezhangjian.ontology.config;

import java.net.URI;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "ontology.pulsar")
public record PulsarProperties(String host, int port, URI url, String listenerName) {
}
