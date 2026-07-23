package com.hezhangjian.ontology.core.connections;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(DataConnectionController.class)
@Import(com.hezhangjian.ontology.core.security.ResourceServerSecurity.class)
class DataConnectionControllerTest {
    private static final String BASE = "/v1/ontologies/00000000-0000-0000-0000-00000000a001";
    @Autowired MockMvc mvc;
    @MockitoBean DataConnectionService service;

    @Test
    void localUserCanListWithoutCredentialMaterialOrLogin() throws Exception {
        when(service.list(0, 20, null, null, null, null)).thenReturn(new ConnectionModels.DataSourcePage(
                List.of(source()), 0, 20, 1, Map.of("all", 1), Map.of()));

        mvc.perform(get(BASE + "/connections"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].name").value("受控 MinIO"))
                .andExpect(jsonPath("$.items[0].credential.ciphertext").doesNotExist());
    }

    @Test
    void localUserCanReadAndDeleteWithoutLogin() throws Exception {
        UUID id = UUID.randomUUID();
        when(service.list(0, 20, null, null, null, null)).thenReturn(new ConnectionModels.DataSourcePage(
                List.of(), 0, 20, 0, Map.of(), Map.of()));
        mvc.perform(get(BASE + "/connections")).andExpect(status().isOk());
        mvc.perform(delete(BASE + "/connections/" + id))
                .andExpect(status().isNoContent());
        verify(service).delete(any(), any());
    }

    @Test
    void localIdentityIsUsedWithoutLogin() throws Exception {
        UUID id = UUID.randomUUID();
        mvc.perform(delete(BASE + "/connections/" + id))
                .andExpect(status().isNoContent());

        verify(service).delete(any(), any());
    }

    @Test
    void builderCanImportChosenCsvFilesWithoutStorageParameters() throws Exception {
        ConnectionModels.DataSource imported = source();
        when(service.importLocalCsv(any(), any(), any(), any(), any())).thenReturn(imported);
        MockMultipartFile file = new MockMultipartFile("files", "data/demo-token-usage.csv", "text/csv",
                "employee_name,month,total_token\n张三,2026-07,120\n".getBytes());

        mvc.perform(multipart(BASE + "/connections/local-csv").file(file)
                        .param("name", "7 月 Token 消耗")
                        .param("tags", "Token", "月度"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value("受控 MinIO"));

        verify(service).importLocalCsv(any(), any(), any(), any(), any());
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
