package com.hezhangjian.ontology.core;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.embedded.tomcat.TomcatServletWebServerFactory;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
class MultipartConfiguration {
    @Bean
    WebServerFactoryCustomizer<TomcatServletWebServerFactory> multipartPartLimit(
            @Value("${server.multipart-max-parts:100}") int maxParts) {
        return factory -> factory.addConnectorCustomizers(connector -> connector.setMaxPartCount(maxParts));
    }
}
