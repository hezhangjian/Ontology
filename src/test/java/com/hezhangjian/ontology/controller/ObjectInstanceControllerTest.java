package com.hezhangjian.ontology.controller;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.hezhangjian.ontology.model.ObjectInstance;
import com.hezhangjian.ontology.service.ObjectInstanceApiService;
import com.hezhangjian.ontology.service.ObjectInstanceBulkService;
import com.hezhangjian.ontology.service.ObjectInstanceImportService;
import com.hezhangjian.ontology.service.ObjectInstanceReconciliationService;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class ObjectInstanceControllerTest {
    private static final String COLLECTION =
            "/v1/ontologies/hr/object-types/person/object-instances";

    private final ObjectInstanceApiService instances = mock(ObjectInstanceApiService.class);

    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(new ObjectInstanceController(
                    instances,
                    mock(ObjectInstanceBulkService.class),
                    mock(ObjectInstanceImportService.class),
                    mock(ObjectInstanceReconciliationService.class)))
            .build();

    @Test
    void bindsCreateHeadersAndPathParametersInContractOrder() throws Exception {
        when(instances.create("hr", "person", Map.of("name", "Alice"), "request-1"))
                .thenReturn(new ObjectInstance().id("alice").version(1L));

        mvc.perform(post(COLLECTION)
                        .header("Idempotency-Key", "request-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"properties\":{\"name\":\"Alice\"}}"))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", COLLECTION + "/alice"));
        verify(instances).create("hr", "person", Map.of("name", "Alice"), "request-1");
    }

    @Test
    void bindsObjectIdentityWithoutSwappingOntologyAndObjectIds() throws Exception {
        when(instances.get("hr", "person", "alice"))
                .thenReturn(new ObjectInstance().id("alice").version(1L));

        mvc.perform(get(COLLECTION + "/alice"))
                .andExpect(status().isOk())
                .andExpect(header().string("ETag", "\"1\""));
        verify(instances).get("hr", "person", "alice");
    }

    @Test
    void bindsDeletePreconditionAlongsideObjectIdentity() throws Exception {
        mvc.perform(delete(COLLECTION + "/alice").header("If-Match", "\"1\""))
                .andExpect(status().isNoContent());
        verify(instances).delete("hr", "person", "alice", "\"1\"");
    }
}
