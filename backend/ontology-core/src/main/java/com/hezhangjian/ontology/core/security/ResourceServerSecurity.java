package com.hezhangjian.ontology.core.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.web.filter.OncePerRequestFilter;

@Configuration
@EnableMethodSecurity
public class ResourceServerSecurity {
    @Bean
    SecurityFilterChain apiSecurity(
            HttpSecurity http,
            ObjectProvider<JdbcTemplate> jdbcTemplates,
            ObjectProvider<JwtDecoder> jwtDecoders,
            @Value("${security.local-user-enabled:true}") boolean localUserEnabled) throws Exception {
        LocalUserFilter localUser = new LocalUserFilter(localUserEnabled);
        http.csrf(csrf -> csrf.disable())
                .authorizeHttpRequests(requests -> requests
                        .requestMatchers("/actuator/health/**").permitAll()
                        .anyRequest().authenticated())
                .addFilterBefore(localUser, AnonymousAuthenticationFilter.class);
        JdbcTemplate jdbc = jdbcTemplates.getIfAvailable();
        if (jdbc != null) {
            http.addFilterAfter(new OntologyAccessFilter(jdbc), LocalUserFilter.class);
        }
        JwtDecoder decoder = jwtDecoders.getIfAvailable();
        if (decoder != null) {
            http.oauth2ResourceServer(oauth2 -> oauth2.jwt(jwt -> jwt.decoder(decoder)));
        }
        return http.build();
    }

    private static final class LocalUserFilter extends OncePerRequestFilter {
        private static final List<GrantedAuthority> AUTHORITIES = List.of(
                new SimpleGrantedAuthority("ROLE_Admin"),
                new SimpleGrantedAuthority("ROLE_Builder"),
                new SimpleGrantedAuthority("ROLE_Viewer"));
        private final boolean enabled;

        private LocalUserFilter(boolean enabled) {
            this.enabled = enabled;
        }

        @Override
        protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
                throws ServletException, IOException {
            if (enabled && SecurityContextHolder.getContext().getAuthentication() == null) {
                SecurityContextHolder.getContext().setAuthentication(
                        new UsernamePasswordAuthenticationToken("local-user", null, AUTHORITIES));
            }
            chain.doFilter(request, response);
        }
    }

    private static final class OntologyAccessFilter extends OncePerRequestFilter {
        private static final Pattern SCOPED_PATH = Pattern.compile("^/v1/ontologies/([^/]+)(/.*)?$");
        private static final Set<String> WRITE_ROLES =
                Set.of("ADMINISTRATOR", "BUILDER", "OPERATOR", "OWNER");
        private final JdbcTemplate jdbc;

        private OntologyAccessFilter(JdbcTemplate jdbc) {
            this.jdbc = jdbc;
        }

        @Override
        protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
                throws ServletException, IOException {
            Matcher match = SCOPED_PATH.matcher(request.getRequestURI());
            if (!match.matches()) {
                chain.doFilter(request, response);
                return;
            }
            var authentication = SecurityContextHolder.getContext().getAuthentication();
            if (authentication == null || !authentication.isAuthenticated()) {
                response.sendError(HttpServletResponse.SC_UNAUTHORIZED);
                return;
            }
            UUID ontologyId;
            try {
                ontologyId = UUID.fromString(match.group(1));
            } catch (IllegalArgumentException failure) {
                response.sendError(HttpServletResponse.SC_BAD_REQUEST, "Ontology id is invalid");
                return;
            }
            List<String> roles = jdbc.queryForList(
                    "SELECT role FROM control.ontology_members WHERE ontology_id=? AND member_id=?",
                    String.class, ontologyId, authentication.getName());
            if (roles.isEmpty()) {
                response.sendError(HttpServletResponse.SC_FORBIDDEN, "Ontology membership is required");
                return;
            }
            String role = roles.getFirst();
            boolean readOnly = Set.of("GET", "HEAD", "OPTIONS").contains(request.getMethod());
            boolean membershipMutation = request.getRequestURI().contains("/members");
            if ((!readOnly && !WRITE_ROLES.contains(role))
                    || (membershipMutation && !readOnly
                    && !Set.of("OWNER", "ADMINISTRATOR").contains(role))) {
                response.sendError(HttpServletResponse.SC_FORBIDDEN, "Ontology role does not allow this operation");
                return;
            }
            List<GrantedAuthority> authorities = new ArrayList<>(authentication.getAuthorities());
            addAuthority(authorities, "ROLE_Viewer");
            if (WRITE_ROLES.contains(role)) addAuthority(authorities, "ROLE_Builder");
            if (Set.of("OWNER", "ADMINISTRATOR").contains(role)) addAuthority(authorities, "ROLE_Admin");
            var scopedAuthentication = new UsernamePasswordAuthenticationToken(
                    authentication.getPrincipal(), authentication.getCredentials(), authorities);
            scopedAuthentication.setDetails(authentication.getDetails());
            SecurityContextHolder.getContext().setAuthentication(scopedAuthentication);
            chain.doFilter(request, response);
        }

        private void addAuthority(List<GrantedAuthority> authorities, String authority) {
            if (authorities.stream().noneMatch(value -> value.getAuthority().equals(authority))) {
                authorities.add(new SimpleGrantedAuthority(authority));
            }
        }
    }
}
