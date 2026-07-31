package com.hezhangjian.ontology.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.hezhangjian.ontology.model.DataSource;
import com.hezhangjian.ontology.model.DataSourcePage;
import com.hezhangjian.ontology.service.DataConnectionApiService;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

class DataConnectionControllerTest {
    private static final String ONTOLOGY_ID = "token_api";
    private final DataConnectionApiService service = mock(DataConnectionApiService.class);
    private final DataConnectionController controller = new DataConnectionController(service);

    @Test
    void listsConnectionsThroughGeneratedContract() {
        DataSourcePage page = new DataSourcePage();
        page.setItems(List.of());
        when(service.list(ONTOLOGY_ID, 0, 20, null, null, null, null))
                .thenReturn(page);

        ResponseEntity<DataSourcePage> response = controller.listConnections(
                ONTOLOGY_ID, 0, 20, null, null, null, null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isSameAs(page);
    }

    @Test
    void createsExternalLocationWithoutExposingInternalOntologyUuid() {
        UUID connectionId = UUID.randomUUID();
        DataSource source = mock(DataSource.class);
        when(source.getId()).thenReturn(connectionId);
        when(service.create(ONTOLOGY_ID, null))
                .thenReturn(new DataConnectionApiService.Versioned<>(source, "1"));

        ResponseEntity<DataSource> response = controller.createConnection(ONTOLOGY_ID, null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getHeaders().getLocation())
                .hasToString("/v1/ontologies/" + ONTOLOGY_ID + "/connections/" + connectionId);
    }

    @Test
    void deletesThroughApiServiceBoundary() {
        UUID id = UUID.randomUUID();

        assertThat(controller.deleteConnection(id, ONTOLOGY_ID).getStatusCode())
                .isEqualTo(HttpStatus.NO_CONTENT);
        verify(service).delete(ONTOLOGY_ID, id);
    }
}
