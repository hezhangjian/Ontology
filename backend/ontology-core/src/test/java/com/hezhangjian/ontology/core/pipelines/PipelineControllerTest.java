package com.hezhangjian.ontology.core.pipelines;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(PipelineController.class)
@Import(com.hezhangjian.ontology.core.security.ResourceServerSecurity.class)
class PipelineControllerTest {
    @Autowired MockMvc mvc;
    @MockitoBean PipelineEventStreamService streams;
    @MockitoBean PipelineService service;

    @Test
    void localUserCanListPipelinesWithoutLogin() throws Exception {
        when(service.list(0, 20, null, null, null, null, null, null)).thenReturn(
                new PipelineModels.PipelinePage(List.of(), 0, 20, 0, Map.of("ALL", 0), Map.of()));

        mvc.perform(get("/v1/pipelines")).andExpect(status().isOk());
    }

    @Test
    void localUserCanResetOffsetsWithoutLogin() throws Exception {
        UUID id = UUID.randomUUID();
        String body = "{\"position\":\"EARLIEST\",\"specificOffsets\":{},\"acknowledgeDuplicateOrLossRisk\":true}";

        mvc.perform(post("/v1/pipelines/" + id + "/reset-offsets").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk());
        verify(service).resetOffsets(any(), any(), any());
    }

    @Test
    void localUserCanDeletePipelineWithoutLogin() throws Exception {
        UUID id = UUID.randomUUID();

        mvc.perform(delete("/v1/pipelines/" + id))
                .andExpect(status().isNoContent());

        verify(service).delete(any(), any());
    }
}
