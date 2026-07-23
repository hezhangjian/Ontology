package com.hezhangjian.ontology.core.modeling;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hezhangjian.ontology.core.connections.ConnectionProblem;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ModelingPolicyTest {
    private final ModelingPolicy policy = new ModelingPolicy(new ObjectMapper());

    @Test
    void acceptsOneStableScalarPrimaryKeyAndSensitiveNonKeyProperty() {
        assertThatCode(() -> policy.validateProperties(List.of(
                property("employee_id", "STRING", true, true, false),
                property("email", "STRING", false, false, false)))).doesNotThrowAnyException();
    }

    @Test
    void rejectsJsonPrimaryKeysAndMissingPrimaryKeys() {
        assertThatThrownBy(() -> policy.validateProperties(List.of(property("payload", "JSON", true, true, false))))
                .isInstanceOf(ConnectionProblem.class);
        assertThatThrownBy(() -> policy.validateProperties(List.of(property("name", "STRING", true, false, true))))
                .isInstanceOf(ConnectionProblem.class).hasMessageContaining("主键");
    }

    @Test
    void rejectsConflictingActionWrites() {
        assertThatThrownBy(() -> policy.validateActionRules(List.of(
                Map.of("targetPropertyId", "status", "value", "OPEN"),
                Map.of("targetPropertyId", "status", "value", "CLOSED"))))
                .isInstanceOf(ConnectionProblem.class).hasMessageContaining("冲突写入");
    }

    @Test
    void permitsReadOnlyFunctionDslAndRejectsWriteEscapeHatches() {
        assertThatCode(() -> policy.validateFunctionDsl(Map.of("from", "Employee", "aggregate", "count"))).doesNotThrowAnyException();
        assertThatCode(() -> policy.validateFunctionDsl(Map.of("result", Map.of("actionName", "UpdateEquipmentQualityPolicy"))))
                .doesNotThrowAnyException();
        assertThatThrownBy(() -> policy.validateFunctionDsl(Map.of("script", "g.V().drop()")))
                .isInstanceOf(ConnectionProblem.class).hasMessageContaining("只读");
        assertThatThrownBy(() -> policy.validateFunctionDsl(Map.of("steps", List.of(Map.of("tool", "execute_action")))))
                .isInstanceOf(ConnectionProblem.class).hasMessageContaining("只读");
    }

    private ModelingModels.PropertyDraft property(String apiName, String type, boolean required, boolean primary, boolean title) {
        return new ModelingModels.PropertyDraft(apiName, apiName, "", type, required, primary, title,
                true, true, false, false, null, null, null, List.of());
    }
}
