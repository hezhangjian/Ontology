package com.hezhangjian.ontology.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.hezhangjian.ontology.model.PipelinePage;
import com.hezhangjian.ontology.service.PipelineApiService;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

class PipelineControllerTest {
    private static final String ONTOLOGY_ID = "token_api";
    private final PipelineApiService service = mock(PipelineApiService.class);
    private final PipelineController controller = new PipelineController(service);

    @Test
    void listsPipelinesThroughGeneratedContract() {
        PipelinePage page = new PipelinePage();
        page.setItems(List.of());
        when(service.list(
                        ONTOLOGY_ID, 0, 20, null, null, null, null, null, null))
                .thenReturn(page);

        assertThat(controller.listPipelines(
                                ONTOLOGY_ID,
                                0,
                                20,
                                null,
                                null,
                                null,
                                null,
                                null,
                                null)
                        .getBody())
                .isSameAs(page);
    }

    @Test
    void deletesThroughApiServiceBoundary() {
        UUID id = UUID.randomUUID();

        assertThat(controller.deletePipeline(id, ONTOLOGY_ID).getStatusCode())
                .isEqualTo(HttpStatus.NO_CONTENT);
        verify(service).delete(ONTOLOGY_ID, id);
    }
}
