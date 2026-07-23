package com.hezhangjian.ontology.agent.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.web.server.SecurityWebFilterChain;

@Configuration
public class ResourceServerSecurity {
    @Bean
    SecurityWebFilterChain apiSecurity(
            ServerHttpSecurity http,
            @Value("${security.local-user-enabled:false}") boolean localUserEnabled) {
        http.csrf(csrf -> csrf.disable());
        if (localUserEnabled) {
            http.authorizeExchange(exchanges -> exchanges.anyExchange().permitAll());
        } else {
            http.authorizeExchange(exchanges -> exchanges
                    .pathMatchers("/actuator/health/**").permitAll()
                    .anyExchange().authenticated());
            http.oauth2ResourceServer(oauth2 -> oauth2.jwt(jwt -> { }));
        }
        return http.build();
    }
}
