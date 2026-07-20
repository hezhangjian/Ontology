package com.hezhangjian.ontology.core.explorer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hezhangjian.ontology.core.explorer.ExplorerModels.ObjectSetRequest;
import com.hezhangjian.ontology.core.explorer.ExplorerModels.ObjectTypeDefinition;
import com.hezhangjian.ontology.core.explorer.ExplorerModels.PropertyDefinition;
import com.hezhangjian.ontology.core.explorer.ExplorerModels.SortClause;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

class ExplorerPolicyTest {
    private final UUID typeId = UUID.randomUUID();
    private final UUID nameId = UUID.randomUUID();
    private final UUID secretId = UUID.randomUUID();
    private final ExplorerPolicy policy = new ExplorerPolicy(new ObjectMapper());
    private final ObjectTypeDefinition type = new ObjectTypeDefinition(typeId, "Asset", "资产", "ACTIVE", 7,
            List.of(new PropertyDefinition(nameId, "name", "名称", "STRING", true, true, true, true, true, false),
                    new PropertyDefinition(secretId, "secret", "秘密", "STRING", false, false, false, false, false, true)));

    @Test
    void acceptsStableTypedPropertyAndSortIds() {
        ObjectSetRequest request = new ObjectSetRequest(typeId,
                Map.of("type", "property", "propertyId", nameId.toString(), "operator", "starts_with", "value", "A"),
                List.of(new SortClause(nameId, "asc")), 25, null, List.of(nameId));
        assertThat(policy.validate(request, type).fingerprint()).hasSize(64);
    }

    @Test
    void keepsFingerprintStableAcrossCursorPages() {
        ObjectSetRequest firstPage = new ObjectSetRequest(typeId, Map.of(), List.of(), 25, null, List.of(nameId));
        ObjectSetRequest nextPage = new ObjectSetRequest(typeId, Map.of(), List.of(), 25, "signed-cursor", List.of(nameId));

        assertThat(policy.validate(firstPage, type).fingerprint())
                .isEqualTo(policy.validate(nextPage, type).fingerprint());
    }

    @Test
    void rejectsSensitiveFieldsBeforeStorageCompilation() {
        ObjectSetRequest request = new ObjectSetRequest(typeId,
                Map.of("type", "property", "propertyId", secretId.toString(), "operator", "eq", "value", "x"),
                List.of(), 50, null, List.of());
        assertThatThrownBy(() -> policy.validate(request, type)).isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("无权访问");
    }

    @Test
    void rejectsMoreThanFiftyLeaves() {
        List<Map<String, Object>> children = new ArrayList<>();
        for (int index = 0; index < 51; index++) {
            children.add(Map.of("type", "property", "propertyId", nameId.toString(), "operator", "eq", "value", index));
        }
        ObjectSetRequest request = new ObjectSetRequest(typeId, Map.of("type", "and", "children", children),
                List.of(), 50, null, List.of());
        assertThatThrownBy(() -> policy.validate(request, type)).isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("最多 50");
    }

    @Test
    void rejectsMultiHopAndFourthRelationConditions() {
        List<Map<String, Object>> relations = List.of(
                Map.of("type", "relation"), Map.of("type", "relation"),
                Map.of("type", "relation"), Map.of("type", "relation"));
        ObjectSetRequest request = new ObjectSetRequest(typeId, Map.of("type", "and", "children", relations),
                List.of(), 50, null, List.of());
        assertThatThrownBy(() -> policy.validate(request, type)).isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("最多 3");
    }
}
