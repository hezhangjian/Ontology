package com.hezhangjian.ontology.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class DashboardDataSourceContractTest {
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void acceptsDatasetApiIdInDashboardDefinition() {
        com.hezhangjian.ontology.model.DashboardDataSource request =
                new com.hezhangjian.ontology.model.DashboardDataSource()
                        .id(UUID.randomUUID())
                        .name("Token usage")
                        .kind("DATASET")
                        .datasetId("token")
                        .query(Map.of());

        DashboardModels.DashboardDataSource source =
                objectMapper.convertValue(request, DashboardModels.DashboardDataSource.class);

        assertThat(source.datasetId()).isEqualTo("token");
        assertThat(source.referenceId()).isNull();
    }
}
