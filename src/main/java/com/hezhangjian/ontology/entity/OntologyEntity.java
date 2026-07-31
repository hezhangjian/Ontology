package com.hezhangjian.ontology.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

@Entity
@Getter
@Setter
@Table(name = "ontologies", schema = "control")
public class OntologyEntity {
    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID internalId;

    @Column(name = "api_name", nullable = false, unique = true, length = 160)
    private String apiName;

    @Column(name = "display_name", nullable = false, length = 240)
    private String displayName;

    @Column(name = "description", nullable = false, length = 1024)
    private String description = "";

    @Column(name = "icon", nullable = false, length = 32)
    private String icon = "deployment-unit";

    @Column(name = "color", nullable = false, length = 24)
    private String color = "#3157d5";

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    @PrePersist
    void prePersist() {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        if (internalId == null) {
            internalId = UUID.randomUUID();
        }
        if (createdAt == null) {
            createdAt = now;
        }
        updatedAt = now;
    }

    @PreUpdate
    void preUpdate() {
        updatedAt = OffsetDateTime.now(ZoneOffset.UTC);
    }
}
