package com.hezhangjian.ontology.service;

import static com.hezhangjian.ontology.service.DatasetModels.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hezhangjian.ontology.client.DatasetStorageClient;
import com.hezhangjian.ontology.client.DatasetSourceClient;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import com.hezhangjian.ontology.repo.SqlClientRepository;

class DatasetServiceTest {
    @Test
    void infersCsvScalarTypesFromMultipleNonBlankSamples() {
        DatasetService service = new DatasetService(mock(SqlClientRepository.class), new ObjectMapper(),
                mock(DatasetSourceClient.class), mock(DatasetStorageClient.class), mock(PipelineService.class));

        assertThat(service.inferType(List.of("1", "200", "-3"))).isEqualTo("INTEGER");
        assertThat(service.inferType(List.of("1", "2.50", new BigDecimal("3")))).isEqualTo("DECIMAL");
        assertThat(service.inferType(List.of("2026-01-01", "2026-07-25T18:30:00"))).isEqualTo("DATETIME");
        assertThat(service.inferType(List.of("1", "not-a-number", "3"))).isEqualTo("TEXT");
        assertThat(service.inferType(List.of())).isEqualTo("TEXT");
    }

    @Test
    void groupsRawFieldsByMonthAndCalculatesMultipleMeasures() {
        UUID datasetId = UUID.randomUUID();
        DatasetStorageClient storage = mock(DatasetStorageClient.class);
        DatasetService service = spy(new DatasetService(mock(SqlClientRepository.class), new ObjectMapper(),
                mock(DatasetSourceClient.class), storage, mock(PipelineService.class)));
        List<Map<String, Object>> rows = List.of(
                Map.of("used_at", "2026-01-05T10:00:00Z", "employee_id", "A", "tokens", 10),
                Map.of("used_at", "2026-01-20T10:00:00Z", "employee_id", "B", "tokens", 50),
                Map.of("used_at", "2026-02-03T10:00:00Z", "employee_id", "A", "tokens", 40));
        Dataset dataset = new Dataset(datasetId, "Token", "", UUID.randomUUID(), "pipeline",
                List.of(), rows.size(), "READY", "tester", Instant.now(), Instant.now());
        doReturn(dataset).when(service).get(datasetId);
        when(storage.rows(datasetId)).thenReturn(rows);

        QueryResult result = service.query(datasetId, new QueryRequest(List.of(),
                List.of(new Dimension("used_at", "month", "MONTH")),
                List.of(new Metric("SUM", "tokens", null, "total"),
                        new Metric("SUM_PER_DISTINCT", "tokens", "employee_id", "per_capita")),
                List.of(), "month", "ASC", 100));

        assertThat(result.rows()).containsExactly(
                Map.of("month", "2026-01", "total", new BigDecimal("60"), "per_capita", new BigDecimal("30.00")),
                Map.of("month", "2026-02", "total", new BigDecimal("40"), "per_capita", new BigDecimal("40.00")));
    }

    @Test
    void appliesWidgetStyleFieldFiltersBeforeAggregation() {
        UUID datasetId = UUID.randomUUID();
        DatasetStorageClient storage = mock(DatasetStorageClient.class);
        DatasetService service = spy(new DatasetService(mock(SqlClientRepository.class), new ObjectMapper(),
                mock(DatasetSourceClient.class), storage, mock(PipelineService.class)));
        List<Map<String, Object>> rows = List.of(
                Map.of("department", "R&D", "tokens", 10),
                Map.of("department", "Sales", "tokens", 90));
        Dataset dataset = new Dataset(datasetId, "Token", "", UUID.randomUUID(), "pipeline",
                List.of(), rows.size(), "READY", "tester", Instant.now(), Instant.now());
        doReturn(dataset).when(service).get(datasetId);
        when(storage.rows(datasetId)).thenReturn(rows);

        QueryResult result = service.query(datasetId, new QueryRequest(List.of(), List.of(),
                List.of(new Metric("SUM", "tokens", null, "total")),
                List.of(new Filter("department", "EQUALS", List.of("R&D"))), "total", "DESC", 100));

        assertThat(result.rows()).containsExactly(Map.of("total", new BigDecimal("10")));
    }

    @Test
    void comparesTwoFieldsBeforeAggregation() {
        UUID datasetId = UUID.randomUUID();
        DatasetStorageClient storage = mock(DatasetStorageClient.class);
        DatasetService service = spy(new DatasetService(mock(SqlClientRepository.class), new ObjectMapper(),
                mock(DatasetSourceClient.class), storage, mock(PipelineService.class)));
        List<Map<String, Object>> rows = List.of(
                Map.of("name", "Alice", "leader_name", "Alice", "tokens", 10),
                Map.of("name", "Bob", "leader_name", "Alice", "tokens", 90),
                Map.of("name", "Carol", "leader_name", "Carol", "tokens", 20));
        Dataset dataset = new Dataset(datasetId, "Token", "", UUID.randomUUID(), "pipeline",
                List.of(), rows.size(), "READY", "tester", Instant.now(), Instant.now());
        doReturn(dataset).when(service).get(datasetId);
        when(storage.rows(datasetId)).thenReturn(rows);

        QueryResult result = service.query(datasetId, new QueryRequest(List.of("name"), List.of(),
                List.of(new Metric("SUM", "tokens", null, "total")),
                List.of(new Filter("name", "FIELD_EQUALS", List.of(), "leader_name")), "name", "ASC", 100));

        assertThat(result.rows()).containsExactly(
                Map.of("name", "Alice", "total", new BigDecimal("10")),
                Map.of("name", "Carol", "total", new BigDecimal("20")));
    }
}
