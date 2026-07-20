CREATE SCHEMA IF NOT EXISTS control;

CREATE TABLE control.ontology_revisions (
    revision BIGINT PRIMARY KEY,
    status VARCHAR(24) NOT NULL CHECK (status IN ('DRAFT', 'ACTIVE', 'RETIRED')),
    activated_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE control.object_types (
    revision BIGINT NOT NULL REFERENCES control.ontology_revisions(revision),
    type_id VARCHAR(160) NOT NULL,
    display_name VARCHAR(240) NOT NULL,
    active BOOLEAN NOT NULL DEFAULT true,
    PRIMARY KEY (revision, type_id)
);

CREATE TABLE control.object_properties (
    revision BIGINT NOT NULL,
    type_id VARCHAR(160) NOT NULL,
    property_id VARCHAR(160) NOT NULL,
    value_type VARCHAR(24) NOT NULL CHECK (value_type IN ('BOOLEAN', 'DATE', 'DECIMAL', 'INTEGER', 'JSON', 'TEXT')),
    required BOOLEAN NOT NULL DEFAULT false,
    searchable BOOLEAN NOT NULL DEFAULT true,
    sensitive BOOLEAN NOT NULL DEFAULT false,
    PRIMARY KEY (revision, type_id, property_id),
    FOREIGN KEY (revision, type_id) REFERENCES control.object_types(revision, type_id)
);

CREATE TABLE control.relation_types (
    revision BIGINT NOT NULL REFERENCES control.ontology_revisions(revision),
    type_id VARCHAR(160) NOT NULL,
    source_type_id VARCHAR(160) NOT NULL,
    target_type_id VARCHAR(160) NOT NULL,
    active BOOLEAN NOT NULL DEFAULT true,
    PRIMARY KEY (revision, type_id),
    FOREIGN KEY (revision, source_type_id) REFERENCES control.object_types(revision, type_id),
    FOREIGN KEY (revision, target_type_id) REFERENCES control.object_types(revision, type_id)
);

CREATE TABLE control.projection_ledger (
    event_id UUID PRIMARY KEY,
    event_type VARCHAR(48) NOT NULL,
    topic VARCHAR(320) NOT NULL,
    message_id VARCHAR(320) NOT NULL,
    ontology_revision BIGINT NOT NULL REFERENCES control.ontology_revisions(revision),
    entity_key VARCHAR(512) NOT NULL,
    entity_version BIGINT NOT NULL,
    correlation_id VARCHAR(240) NOT NULL,
    graph_element_id TEXT,
    status VARCHAR(24) NOT NULL CHECK (status IN ('RECEIVED', 'GRAPH_APPLIED', 'PROJECTED', 'DEGRADED', 'STALE', 'DLQ')),
    attempts INTEGER NOT NULL DEFAULT 0,
    last_error_code VARCHAR(80),
    last_error_message VARCHAR(1000),
    received_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    graph_applied_at TIMESTAMPTZ,
    projected_at TIMESTAMPTZ,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX projection_ledger_entity_version_idx
    ON control.projection_ledger(entity_key, entity_version DESC);
CREATE INDEX projection_ledger_status_updated_idx
    ON control.projection_ledger(status, updated_at);

CREATE TABLE control.projection_failures (
    failure_id UUID PRIMARY KEY,
    event_id UUID REFERENCES control.projection_ledger(event_id),
    error_code VARCHAR(80) NOT NULL,
    retryable BOOLEAN NOT NULL,
    attempt INTEGER NOT NULL,
    safe_message VARCHAR(1000) NOT NULL,
    failed_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE control.projection_operations (
    operation_id UUID PRIMARY KEY,
    idempotency_key VARCHAR(240) NOT NULL UNIQUE,
    ontology_revision BIGINT NOT NULL REFERENCES control.ontology_revisions(revision),
    requested_by VARCHAR(240) NOT NULL,
    correlation_id VARCHAR(240) NOT NULL,
    edit_count INTEGER NOT NULL CHECK (edit_count BETWEEN 1 AND 100),
    status VARCHAR(24) NOT NULL CHECK (status IN ('RECEIVED', 'PROJECTING', 'PROJECTED', 'DEGRADED', 'FAILED')),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE control.index_rebuild_jobs (
    rebuild_id UUID PRIMARY KEY,
    requested_by VARCHAR(240) NOT NULL,
    correlation_id VARCHAR(240) NOT NULL,
    target_index VARCHAR(240),
    object_count BIGINT NOT NULL DEFAULT 0,
    status VARCHAR(24) NOT NULL CHECK (status IN ('RECEIVED', 'RUNNING', 'SUCCEEDED', 'FAILED')),
    safe_error VARCHAR(1000),
    requested_at TIMESTAMPTZ NOT NULL,
    started_at TIMESTAMPTZ,
    completed_at TIMESTAMPTZ
);

INSERT INTO control.ontology_revisions (revision, status, activated_at)
VALUES (1, 'ACTIVE', now());

INSERT INTO control.object_types (revision, type_id, display_name)
VALUES
    (1, 'Department', 'Department'),
    (1, 'Employee', 'Employee');

INSERT INTO control.object_properties
    (revision, type_id, property_id, value_type, required, searchable, sensitive)
VALUES
    (1, 'Department', 'name', 'TEXT', true, true, false),
    (1, 'Employee', 'department', 'TEXT', false, true, false),
    (1, 'Employee', 'email', 'TEXT', false, false, true),
    (1, 'Employee', 'name', 'TEXT', true, true, false);

INSERT INTO control.relation_types
    (revision, type_id, source_type_id, target_type_id)
VALUES (1, 'member_of', 'Employee', 'Department');
