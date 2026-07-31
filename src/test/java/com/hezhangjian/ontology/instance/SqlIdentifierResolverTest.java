package com.hezhangjian.ontology.instance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class SqlIdentifierResolverTest {
    private final SqlIdentifierResolver resolver = new SqlIdentifierResolver();

    @Test
    void onlyAcceptsGeneratedPhysicalIdentifiers() {
        assertThat(resolver.qualified("instance", "object_type_r_123"))
                .isEqualTo("\"instance\".\"object_type_r_123\"");
        assertThatThrownBy(() -> resolver.quote("employee; DROP TABLE control.ontologies"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> resolver.tableName("Employee"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
