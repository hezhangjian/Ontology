package com.hezhangjian.ontology.config;

import org.springframework.boot.web.embedded.tomcat.TomcatServletWebServerFactory;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
class MultipartConfiguration {
    @Bean
    WebServerFactoryCustomizer<TomcatServletWebServerFactory> multipartPartLimit(
            WebProperties properties) {
        return factory -> factory.addConnectorCustomizers(
                connector -> connector.setMaxPartCount(properties.multipartMaxParts()));
    }
}
