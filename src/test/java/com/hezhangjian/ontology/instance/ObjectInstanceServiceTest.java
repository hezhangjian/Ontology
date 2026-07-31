package com.hezhangjian.ontology.instance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.hezhangjian.ontology.instance.ObjectInstanceModels.ObjectSchema;
import com.hezhangjian.ontology.instance.ObjectInstanceModels.PropertySchema;
import com.hezhangjian.ontology.instance.ObjectInstanceModels.StoredInstance;
import com.hezhangjian.ontology.repo.ObjectInstanceRepository;
import java.time.Instant;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ObjectInstanceServiceTest {
    private final ObjectMapper json = new ObjectMapper().findAndRegisterModules();
    private final ObjectInstanceRepository repository = mock(ObjectInstanceRepository.class);
    private final UUID ontologyId = UUID.randomUUID();
    private final UUID objectTypeId = UUID.randomUUID();
    private ObjectSchema schema;
    private ObjectInstanceService service;

    @BeforeEach
    void setUp() {
        PropertySchema id = property("id", "p_id", "STRING", true, true, false);
        PropertySchema name = property("name", "p_name", "STRING", true, false, true);
        PropertySchema age = property("age", "p_age", "INTEGER", false, false, false);
        schema = new ObjectSchema(
                ontologyId,
                objectTypeId,
                "employee",
                "r_employee",
                id,
                name,
                List.of(id, name, age),
                "instance",
                "object_type_r_employee");
        when(repository.schema(ontologyId, "employee")).thenReturn(schema);
        service = new ObjectInstanceService(repository, json);
    }

    @Test
    void createDerivesIdentityAndTitleAndQueuesFullSnapshot() {
        StoredInstance stored = stored("000001", 1, object(
                "p_id", "000001", "p_name", "Tony Stark", "p_age", 45));
        when(repository.insert(
                        eq(schema),
                        eq("000001"),
                        eq("Tony Stark"),
                        any(),
                        any(),
                        eq("API"),
                        eq(null),
                        eq(null)))
                .thenReturn(stored);

        var result = service.create(
                ontologyId,
                "factory",
                "employee",
                Map.of("id", "000001", "name", "Tony Stark", "age", 45),
                "create-1");

        assertThat(result.instance().id()).isEqualTo("000001");
        assertThat(result.instance().title()).isEqualTo("Tony Stark");
        verify(repository).enqueue(eq(schema), any(), eq("factory"), any());
        verify(repository).saveIdempotency(
                eq(ontologyId), eq(objectTypeId), eq("create-1"), any(), eq("000001"), eq(1L));
    }

    @Test
    void usesDisplayNamesAtTheObjectInstanceApiBoundary() {
        PropertySchema id =
                property("field_5de5_53f7", "工号", "p_id", "STRING", true, true, false);
        PropertySchema name =
                property("field_59d3_540d", "姓名", "p_name", "STRING", true, false, true);
        PropertySchema department = property(
                "field_4e94_7ea7_90e8_95e8",
                "五级部门",
                "p_department",
                "STRING",
                false,
                false,
                false);
        ObjectSchema businessSchema = new ObjectSchema(
                ontologyId,
                objectTypeId,
                "employee",
                "r_employee",
                id,
                name,
                List.of(id, name, department),
                "instance",
                "object_type_r_employee");
        when(repository.schema(ontologyId, "employee")).thenReturn(businessSchema);

        assertThat(service.validateForImport(
                        ontologyId,
                        "employee",
                        Map.of("工号", "EMP001", "姓名", "张一鸣", "五级部门", "湖仓团队")))
                .isEqualTo("EMP001");

        StoredInstance source = stored(
                "EMP001",
                1,
                object("p_id", "EMP001", "p_name", "张一鸣", "p_department", "湖仓团队"));
        assertThat(service.externalProperties(businessSchema, source))
                .containsExactly(
                        Map.entry("工号", "EMP001"),
                        Map.entry("姓名", "张一鸣"),
                        Map.entry("五级部门", "湖仓团队"));
    }

    @Test
    void rejectsWrongTypesAndPrimaryKeyPatches() {
        assertThatThrownBy(() -> service.create(
                        ontologyId,
                        "factory",
                        "employee",
                        Map.of("id", "000001", "name", "Tony", "age", "forty-five"),
                        "bad"))
                .isInstanceOf(ObjectInstanceStoreException.class)
                .hasMessageContaining("age");

        StoredInstance current = stored(
                "000001", 2, object("p_id", "000001", "p_name", "Tony"));
        when(repository.find(schema, "000001", false)).thenReturn(Optional.of(current));
        assertThatThrownBy(() -> service.update(
                        ontologyId,
                        "factory",
                        "employee",
                        "000001",
                        2,
                        Map.of("id", "000002")))
                .isInstanceOf(ObjectInstanceStoreException.class)
                .hasMessageContaining("primary key");
    }

    @Test
    void idempotencyReplayReturnsTheOriginalVersionWithoutAnotherInsert() {
        StoredInstance current =
                stored("000001", 1, object("p_id", "000001", "p_name", "Tony"));
        when(repository.idempotency(ontologyId, objectTypeId, "create-1"))
                .thenReturn(Optional.of(new ObjectInstanceRepository.IdempotencyRecord(
                        "ignore", "000001", 1)));
        ObjectNode payload = object("p_id", "000001", "p_name", "Tony");
        String hash = invokeFingerprint(payload);
        when(repository.idempotency(ontologyId, objectTypeId, "create-1"))
                .thenReturn(Optional.of(new ObjectInstanceRepository.IdempotencyRecord(
                        hash, "000001", 1)));
        when(repository.find(schema, "000001", false)).thenReturn(Optional.of(current));

        Map<String, Object> request = new LinkedHashMap<>();
        request.put("id", "000001");
        request.put("name", "Tony");
        var replay = service.create(
                ontologyId,
                "factory",
                "employee",
                request,
                "create-1");

        assertThat(replay.instance().version()).isEqualTo(1);
        assertThat(replay.eventId()).isNull();
    }

    @Test
    void unchangedImportKeepsTheRunCorrelation() {
        ObjectNode base = object("p_id", "000001", "p_name", "Tony");
        StoredInstance current = imported(base, "PIPELINE", "pipeline-a:output");
        when(repository.find(schema, "000001", true)).thenReturn(Optional.of(current));
        UUID correlationId = UUID.randomUUID();

        var result = service.mergeBase(
                ontologyId,
                "factory",
                "employee",
                Map.of("id", "000001", "name", "Tony"),
                "PIPELINE",
                "pipeline-a:output",
                "revision-2",
                correlationId);

        assertThat(result.correlationId()).isEqualTo(correlationId);
        assertThat(result.eventId()).isNull();
    }

    @Test
    void identicalPayloadFromAnotherSourceTransfersOwnership() {
        ObjectNode base = object("p_id", "000001", "p_name", "Tony");
        StoredInstance current = imported(base, "DATASET", "dataset-a");
        StoredInstance updated = imported(base, "PIPELINE", "pipeline-a:output");
        when(repository.find(schema, "000001", true)).thenReturn(Optional.of(current));
        when(repository.update(
                        eq(schema),
                        eq("000001"),
                        eq(2L),
                        eq("Tony"),
                        any(),
                        any(),
                        eq("PIPELINE"),
                        eq("pipeline-a:output"),
                        eq("revision-1")))
                .thenReturn(updated);

        var result = service.mergeBase(
                ontologyId,
                "factory",
                "employee",
                Map.of("id", "000001", "name", "Tony"),
                "PIPELINE",
                "pipeline-a:output",
                "revision-1",
                UUID.randomUUID());

        assertThat(result.eventId()).isNotNull();
        verify(repository).enqueue(eq(schema), any(), eq("factory"), any());
    }

    private String invokeFingerprint(ObjectNode payload) {
        try {
            byte[] digest = java.security.MessageDigest.getInstance("SHA-256")
                    .digest(payload.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(digest);
        } catch (Exception failure) {
            throw new IllegalStateException(failure);
        }
    }

    private PropertySchema property(
            String apiName,
            String physicalKey,
            String type,
            boolean required,
            boolean primary,
            boolean title) {
        return property(
                apiName, apiName, physicalKey, type, required, primary, title);
    }

    private PropertySchema property(
            String apiName,
            String displayName,
            String physicalKey,
            String type,
            boolean required,
            boolean primary,
            boolean title) {
        return new PropertySchema(
                UUID.randomUUID(),
                apiName,
                displayName,
                physicalKey,
                type,
                required,
                primary,
                title,
                true,
                false);
    }

    private StoredInstance stored(String id, long version, ObjectNode overrides) {
        return new StoredInstance(
                id,
                overrides.path("p_name").asText(id),
                version,
                json.createObjectNode(),
                overrides,
                "API",
                null,
                null,
                Instant.now(),
                Instant.now(),
                null);
    }

    private StoredInstance imported(
            ObjectNode base, String sourceKind, String sourceRef) {
        return new StoredInstance(
                "000001",
                base.path("p_name").asText(),
                2,
                base,
                json.createObjectNode(),
                sourceKind,
                sourceRef,
                "revision-0",
                Instant.now(),
                Instant.now(),
                null);
    }

    private ObjectNode object(Object... values) {
        ObjectNode result = json.createObjectNode();
        for (int index = 0; index < values.length; index += 2) {
            result.set(String.valueOf(values[index]), json.valueToTree(values[index + 1]));
        }
        return result;
    }
}
