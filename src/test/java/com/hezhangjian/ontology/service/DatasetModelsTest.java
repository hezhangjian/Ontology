package com.hezhangjian.ontology.service;

import static com.hezhangjian.ontology.service.DatasetModels.*;
import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class DatasetModelsTest {
    @Test
    void exposesReadableDatasetSourceWithoutInternalIdentifiers() throws Exception {
        UUID internalId = UUID.randomUUID();
        UUID pipelineId = UUID.randomUUID();
        Dataset dataset = new Dataset("monthly_usage", internalId, "月度用量", "",
                pipelineId, "用量处理", List.of(), 12, "READY", "测试用户",
                Instant.parse("2026-07-28T00:00:00Z"), Instant.parse("2026-07-28T00:00:00Z"),
                new DatasetSource("PIPELINE", pipelineId.toString(), "用量处理"), true);

        String json = new ObjectMapper().findAndRegisterModules().writeValueAsString(dataset);

        assertThat(json).contains("\"id\":\"monthly_usage\"")
                .contains("\"source\":{\"kind\":\"PIPELINE\"")
                .doesNotContain("internalId")
                .doesNotContain("pipelineId")
                .doesNotContain("pipelineName");
    }
}
