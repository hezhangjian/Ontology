package com.hezhangjian.ontology.core.modeling;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(OntologyCatalogController.class)
@Import(com.hezhangjian.ontology.core.security.ResourceServerSecurity.class)
class OntologyCatalogControllerTest {
    @Autowired MockMvc mvc;
    @MockitoBean OntologyCatalogService service;

    @Test
    void localUserCanListOntologies() throws Exception {
        when(service.list()).thenReturn(List.of(view()));
        mvc.perform(get("/v1/ontologies"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].displayName").value("供应链运营"));
    }

    @Test
    void localBuilderCanCreateOntology() throws Exception {
        when(service.create(any())).thenReturn(view());
        mvc.perform(post("/v1/ontologies")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"apiName":"supply_chain","displayName":"供应链运营","description":"供应链场景","color":"#3157d5"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.apiName").value("supply_chain"));
        verify(service).create(any());
    }

    private OntologyCatalogService.OntologyView view() {
        return new OntologyCatalogService.OntologyView(UUID.randomUUID(), "supply_chain", "供应链运营",
                "供应链场景", "deployment-unit", "#3157d5", 0, 0, Instant.now());
    }
}
