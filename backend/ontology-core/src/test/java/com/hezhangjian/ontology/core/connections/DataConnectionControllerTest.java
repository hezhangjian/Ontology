package com.hezhangjian.ontology.core.connections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(DataConnectionController.class)
@Import(com.hezhangjian.ontology.core.security.ResourceServerSecurity.class)
class DataConnectionControllerTest {
    @Autowired MockMvc mvc;
    @MockitoBean DataConnectionService service;
    @MockitoBean JwtDecoder jwtDecoder;

    @Test
    void builderCanListWithoutCredentialMaterial() throws Exception {
        when(service.list(0, 20, null, null, null, null)).thenReturn(new ConnectionModels.DataSourcePage(
                List.of(source()), 0, 20, 1, Map.of("all", 1), Map.of()));

        mvc.perform(get("/v1/data-sources").with(jwt().jwt(jwt -> jwt.subject("builder").claim("name", "平台构建者"))
                        .authorities(new SimpleGrantedAuthority("ROLE_Builder"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].name").value("受控 MinIO"))
                .andExpect(jsonPath("$.items[0].credential.ciphertext").doesNotExist());
    }

    @Test
    void viewerCannotReadAndOnlyAdminCanDelete() throws Exception {
        UUID id = UUID.randomUUID();
        mvc.perform(get("/v1/data-sources").with(jwt().authorities(new SimpleGrantedAuthority("ROLE_Viewer"))))
                .andExpect(status().isForbidden());
        mvc.perform(delete("/v1/data-sources/" + id).with(jwt().authorities(new SimpleGrantedAuthority("ROLE_Builder"))))
                .andExpect(status().isForbidden());
        mvc.perform(delete("/v1/data-sources/" + id).with(jwt().jwt(jwt -> jwt.subject("admin").claim("name", "管理员"))
                        .authorities(new SimpleGrantedAuthority("ROLE_Admin"))))
                .andExpect(status().isNoContent());
        verify(service).delete(any(), any());
    }

    @Test
    void lightweightTokenFallsBackToPreferredUsername() throws Exception {
        UUID id = UUID.randomUUID();
        mvc.perform(delete("/v1/data-sources/" + id).with(jwt().jwt(token -> token
                        .claims(claims -> claims.remove("sub"))
                        .claim("preferred_username", "platform-admin"))
                        .authorities(new SimpleGrantedAuthority("ROLE_Admin"))))
                .andExpect(status().isNoContent());

        ArgumentCaptor<ConnectionModels.Actor> actor = ArgumentCaptor.forClass(ConnectionModels.Actor.class);
        verify(service).delete(any(), actor.capture());
        assertEquals("platform-admin", actor.getValue().id());
    }

    private ConnectionModels.DataSource source() {
        Instant now = Instant.now();
        ConnectionModels.CredentialSummary credential = new ConnectionModels.CredentialSummary(UUID.randomUUID(), "MinIO 凭据",
                "MANAGED", "S3_CSV", "CONFIGURED", 1, now, null);
        return new ConnectionModels.DataSource(UUID.randomUUID(), "受控 MinIO", "仅元数据发现",
                ConnectionModels.DataSourceType.S3_CSV, "builder", "平台构建者", List.of("demo"),
                Map.of("endpoint", "http://minio:9000"), credential, ConnectionModels.ConnectionStatus.HEALTHY,
                ConnectionModels.SyncStatus.NO_TASKS, 7, now, null, 1, 0, 0, now, now);
    }
}
