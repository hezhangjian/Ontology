package com.hezhangjian.ontology.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Collections;
import java.util.List;

import org.junit.jupiter.api.Test;

class PipelineServiceTest {
    @Test
    void firstOrNullPreservesNullableDatabaseValues() {
        Object result = PipelineService.firstOrNull(Collections.singletonList(null));

        assertThat(result).isNull();
    }

    @Test
    void firstOrNullReturnsNullForNoDatabaseRows() {
        Object result = PipelineService.firstOrNull(List.of());

        assertThat(result).isNull();
    }
}
