package com.hezhangjian.ontology.service;

import static com.hezhangjian.ontology.service.ModelingModels.ResourceKind.OBJECT_TYPE;
import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ModelingModelsTest {
    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    @Test
    void resourceViewExposesModelingResourceIdSeparatelyFromCallerDefinedId() {
        UUID resourceId = UUID.randomUUID();
        ModelingModels.ResourceView source = new ModelingModels.ResourceView(
                resourceId,
                OBJECT_TYPE,
                "employee",
                "人员",
                "",
                "ot_employee",
                "ACTIVE",
                true,
                List.of(),
                "DRAFT",
                1,
                null,
                1,
                Map.of(),
                List.of(),
                null,
                null);

        com.hezhangjian.ontology.model.ResourceView contract =
                objectMapper.convertValue(
                        source, com.hezhangjian.ontology.model.ResourceView.class);

        assertThat(contract.getId()).isEqualTo("employee");
        assertThat(contract.getResourceId()).isEqualTo(resourceId);
    }
}
