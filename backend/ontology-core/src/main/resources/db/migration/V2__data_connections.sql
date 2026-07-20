CREATE TABLE control.connection_secrets (
    id UUID PRIMARY KEY,
    name VARCHAR(160) NOT NULL,
    provider VARCHAR(16) NOT NULL CHECK (provider IN ('MANAGED', 'FILE')),
    ciphertext BYTEA,
    nonce BYTEA,
    algorithm VARCHAR(32) NOT NULL DEFAULT 'AES-256-GCM',
    key_version INTEGER NOT NULL DEFAULT 1,
    file_references JSONB,
    credential_type VARCHAR(40) NOT NULL,
    created_by VARCHAR(160) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    rotated_at TIMESTAMPTZ,
    revoked_at TIMESTAMPTZ,
    CHECK (
        (provider = 'MANAGED' AND ciphertext IS NOT NULL AND nonce IS NOT NULL AND file_references IS NULL)
        OR (provider = 'FILE' AND ciphertext IS NULL AND nonce IS NULL AND file_references IS NOT NULL)
    )
);

CREATE TABLE control.data_sources (
    id UUID PRIMARY KEY,
    name VARCHAR(160) NOT NULL,
    normalized_name VARCHAR(160) NOT NULL UNIQUE,
    description VARCHAR(1000),
    source_type VARCHAR(32) NOT NULL CHECK (source_type IN ('S3_CSV', 'MYSQL', 'POSTGRESQL', 'KAFKA', 'EXTERNAL_PULSAR')),
    owner_id VARCHAR(160) NOT NULL,
    owner_name VARCHAR(240) NOT NULL,
    tags TEXT[] NOT NULL DEFAULT '{}',
    config JSONB NOT NULL,
    secret_ref UUID NOT NULL REFERENCES control.connection_secrets(id),
    connection_status VARCHAR(32) NOT NULL CHECK (connection_status IN ('UNTESTED', 'TESTING', 'HEALTHY', 'HEALTHY_RESTRICTED', 'ERROR', 'DISABLED')),
    status_before_disable VARCHAR(32),
    sync_status VARCHAR(32) NOT NULL DEFAULT 'NO_TASKS' CHECK (sync_status IN ('NO_TASKS', 'IDLE', 'RUNNING', 'STREAMING', 'PARTIAL_FAILURE', 'ALL_FAILURE')),
    asset_count INTEGER NOT NULL DEFAULT 0 CHECK (asset_count >= 0),
    last_checked_at TIMESTAMPTZ,
    last_error JSONB,
    version BIGINT NOT NULL DEFAULT 1,
    created_by VARCHAR(160) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    deleted_at TIMESTAMPTZ
);

CREATE INDEX data_sources_status_updated_idx ON control.data_sources(connection_status, updated_at DESC) WHERE deleted_at IS NULL;
CREATE INDEX data_sources_owner_idx ON control.data_sources(owner_id) WHERE deleted_at IS NULL;
CREATE INDEX data_sources_type_idx ON control.data_sources(source_type) WHERE deleted_at IS NULL;

CREATE TABLE control.data_source_test_results (
    id UUID PRIMARY KEY,
    data_source_id UUID NOT NULL REFERENCES control.data_sources(id) ON DELETE CASCADE,
    request_id UUID NOT NULL,
    tested_by VARCHAR(160) NOT NULL,
    config_fingerprint VARCHAR(64) NOT NULL,
    status VARCHAR(32) NOT NULL CHECK (status IN ('HEALTHY', 'HEALTHY_RESTRICTED', 'ERROR')),
    stages JSONB NOT NULL,
    discovered_summary JSONB NOT NULL DEFAULT '{}',
    tested_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX data_source_test_results_latest_idx ON control.data_source_test_results(data_source_id, tested_at DESC);

CREATE TABLE control.data_source_assets (
    id UUID PRIMARY KEY,
    data_source_id UUID NOT NULL REFERENCES control.data_sources(id) ON DELETE CASCADE,
    stable_key VARCHAR(768) NOT NULL,
    name VARCHAR(320) NOT NULL,
    full_path VARCHAR(1000) NOT NULL,
    parent_path VARCHAR(1000),
    asset_type VARCHAR(24) NOT NULL CHECK (asset_type IN ('BUCKET', 'PREFIX', 'FILE', 'DATABASE', 'SCHEMA', 'TABLE', 'VIEW', 'CLUSTER', 'TOPIC')),
    status VARCHAR(24) NOT NULL DEFAULT 'AVAILABLE' CHECK (status IN ('AVAILABLE', 'NEW', 'UNAVAILABLE')),
    schema_status VARCHAR(24) NOT NULL DEFAULT 'UNKNOWN' CHECK (schema_status IN ('UNKNOWN', 'READY', 'CHANGED', 'ERROR')),
    schema_hash VARCHAR(64),
    schema_version INTEGER NOT NULL DEFAULT 0,
    size_bytes BIGINT,
    estimated_rows BIGINT,
    partition_count INTEGER,
    permission_status VARCHAR(24) NOT NULL DEFAULT 'METADATA_ONLY' CHECK (permission_status IN ('READABLE', 'METADATA_ONLY', 'DENIED')),
    discovered_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    unavailable_at TIMESTAMPTZ,
    UNIQUE (data_source_id, stable_key)
);

CREATE INDEX data_source_assets_path_idx ON control.data_source_assets(data_source_id, full_path);

CREATE TABLE control.data_source_asset_fields (
    id UUID PRIMARY KEY,
    asset_id UUID NOT NULL REFERENCES control.data_source_assets(id) ON DELETE CASCADE,
    ordinal INTEGER NOT NULL,
    name VARCHAR(320) NOT NULL,
    inferred_type VARCHAR(64) NOT NULL,
    original_type VARCHAR(160),
    nullable BOOLEAN NOT NULL,
    sensitive BOOLEAN NOT NULL DEFAULT false,
    primary_key_candidate BOOLEAN NOT NULL DEFAULT false,
    sample_value_masked VARCHAR(320),
    UNIQUE (asset_id, ordinal),
    UNIQUE (asset_id, name)
);

CREATE TABLE control.data_source_discovery_runs (
    id UUID PRIMARY KEY,
    data_source_id UUID NOT NULL REFERENCES control.data_sources(id) ON DELETE CASCADE,
    status VARCHAR(24) NOT NULL CHECK (status IN ('RUNNING', 'SUCCEEDED', 'PARTIAL', 'FAILED')),
    discovered_count INTEGER NOT NULL DEFAULT 0,
    requested_by VARCHAR(160) NOT NULL,
    diagnostic JSONB,
    started_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    completed_at TIMESTAMPTZ
);

CREATE TABLE control.pipelines (
    id UUID PRIMARY KEY,
    name VARCHAR(240) NOT NULL,
    data_source_id UUID NOT NULL REFERENCES control.data_sources(id),
    source_asset_id UUID REFERENCES control.data_source_assets(id),
    mode VARCHAR(16) NOT NULL CHECK (mode IN ('BATCH', 'STREAMING')),
    status VARCHAR(24) NOT NULL,
    owner_id VARCHAR(160) NOT NULL,
    owner_name VARCHAR(240) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE control.pipeline_runs (
    id UUID PRIMARY KEY,
    pipeline_id UUID NOT NULL REFERENCES control.pipelines(id),
    trigger_type VARCHAR(24) NOT NULL,
    status VARCHAR(24) NOT NULL,
    flink_job_id VARCHAR(160),
    correlation_id VARCHAR(240) NOT NULL,
    read_count BIGINT NOT NULL DEFAULT 0,
    written_count BIGINT NOT NULL DEFAULT 0,
    rejected_count BIGINT NOT NULL DEFAULT 0,
    started_at TIMESTAMPTZ NOT NULL,
    completed_at TIMESTAMPTZ
);

CREATE TABLE control.pipeline_run_stages (
    id UUID PRIMARY KEY,
    pipeline_run_id UUID NOT NULL REFERENCES control.pipeline_runs(id) ON DELETE CASCADE,
    stage_order INTEGER NOT NULL,
    stage_type VARCHAR(40) NOT NULL,
    status VARCHAR(24) NOT NULL,
    correlation_id VARCHAR(240) NOT NULL,
    flink_job_id VARCHAR(160),
    event_position JSONB,
    read_count BIGINT NOT NULL DEFAULT 0,
    written_count BIGINT NOT NULL DEFAULT 0,
    rejected_count BIGINT NOT NULL DEFAULT 0,
    started_at TIMESTAMPTZ,
    completed_at TIMESTAMPTZ,
    UNIQUE (pipeline_run_id, stage_order)
);

CREATE TABLE control.audit_events (
    id UUID PRIMARY KEY,
    actor_id VARCHAR(160) NOT NULL,
    actor_name VARCHAR(240) NOT NULL,
    action VARCHAR(80) NOT NULL,
    resource_type VARCHAR(80) NOT NULL,
    resource_id VARCHAR(160) NOT NULL,
    request_id UUID NOT NULL,
    summary VARCHAR(1000) NOT NULL,
    safe_diff JSONB NOT NULL DEFAULT '{}',
    occurred_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX audit_events_resource_idx ON control.audit_events(resource_type, resource_id, occurred_at DESC);

COMMENT ON COLUMN control.data_sources.config IS 'Non-sensitive typed configuration; credential keys are rejected before persistence.';
COMMENT ON COLUMN control.connection_secrets.ciphertext IS 'AES-256-GCM ciphertext only. The key is injected through a Docker secret.';
