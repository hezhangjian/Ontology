package com.hezhangjian.ontology.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Lob;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;

@Entity
@Getter
@IdClass(ObjectTypeEntityId.class)
@Setter
@Table(name = "object_type")
public class ObjectTypeEntity {
    @Id
    @Column(name = "ontology_id", nullable = false, length = 32)
    private String ontologyId;

    @Id
    @Column(name = "id", nullable = false, length = 32)
    private String id;

    @Column(name = "name", nullable = false, length = 32)
    private String name;

    @Column(name = "description", length = 1024)
    private String description;

    @Column(name = "id_field", nullable = false, length = 32)
    private String idField;

    @Column(name = "name_field", nullable = false, length = 32)
    private String nameField;

    @Lob
    @Column(name = "properties_json", nullable = false)
    private String propertiesJson;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    @PrePersist
    void prePersist() {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    void preUpdate() {
        updatedAt = OffsetDateTime.now(ZoneOffset.UTC);
    }
}
