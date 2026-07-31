package com.hezhangjian.ontology.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import com.hezhangjian.ontology.instance.ObjectInstanceModels.ObjectSchema;
import com.hezhangjian.ontology.instance.ObjectInstanceModels.PropertySchema;
import com.hezhangjian.ontology.model.CreateObjectInstanceImportReq;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class DatasetObjectRowMapperTest {
    private final DatasetObjectRowMapper mapper = new DatasetObjectRowMapper();

    @Test
    void acceptsDisplayNamesAndKeepsInternalApiNamesOutOfTheImportContract() {
        PropertySchema id = property("field_5de5_53f7", "工号", "p_id", true, true, false);
        PropertySchema name =
                property("field_59d3_540d", "姓名", "p_name", false, false, true);
        ObjectSchema schema = new ObjectSchema(
                UUID.randomUUID(),
                UUID.randomUUID(),
                "employee",
                "ot_employee",
                id,
                name,
                List.of(id, name),
                "instance",
                "object_type_employee");
        CreateObjectInstanceImportReq request = new CreateObjectInstanceImportReq()
                .identityField("工号")
                .titleField("姓名")
                .fieldMappings(Map.of("工号", "工号", "姓名", "姓名"));

        assertThatCode(() -> mapper.validate(schema, request)).doesNotThrowAnyException();
        assertThat(mapper.map(
                        schema,
                        request.getFieldMappings(),
                        Map.of("工号", "EMP001", "姓名", "张一鸣")))
                .containsExactlyInAnyOrderEntriesOf(Map.of(
                        "field_5de5_53f7", "EMP001",
                        "field_59d3_540d", "张一鸣"));
    }

    private PropertySchema property(
            String apiName,
            String displayName,
            String physicalKey,
            boolean required,
            boolean primary,
            boolean title) {
        return new PropertySchema(
                UUID.randomUUID(),
                apiName,
                displayName,
                physicalKey,
                "STRING",
                required,
                primary,
                title,
                true,
                false);
    }
}
