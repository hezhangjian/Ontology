package com.hezhangjian.ontology.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.hezhangjian.ontology.model.CreateOntologyReq;
import com.hezhangjian.ontology.model.Ontology;
import com.hezhangjian.ontology.service.OntologyService;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

class OntologyControllerTest {
    private final OntologyService service = mock(OntologyService.class);
    private final OntologyController controller = new OntologyController(service);

    @Test
    void listsVisibleOntologies() {
        when(service.list()).thenReturn(List.of(view()));

        ResponseEntity<List<Ontology>> response = controller.listOntologies();

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).extracting(Ontology::getId).containsExactly("supply-chain");
    }

    @Test
    void createsOntologyWithEtag() {
        Ontology ontology = view();
        CreateOntologyReq request = new CreateOntologyReq("supply-chain", "供应链运营");
        when(service.create(request)).thenReturn(ontology);
        when(service.etag(ontology)).thenReturn("\"1\"");

        ResponseEntity<Ontology> response = controller.createOntology(request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getHeaders().getETag()).isEqualTo("\"1\"");
        assertThat(response.getBody()).isSameAs(ontology);
        verify(service).create(request);
    }

    private Ontology view() {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        return new Ontology()
                .id("supply-chain")
                .name("供应链运营")
                .description("供应链场景")
                .createdAt(now)
                .updatedAt(now);
    }
}
