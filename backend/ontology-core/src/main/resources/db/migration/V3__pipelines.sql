ALTER TABLE control.pipelines
    ADD COLUMN description VARCHAR(1000),
    ADD COLUMN normalized_name VARCHAR(240),
    ADD COLUMN template VARCHAR(40) NOT NULL DEFAULT 'BLANK',
    ADD COLUMN lifecycle VARCHAR(24) NOT NULL DEFAULT 'DRAFT',
    ADD COLUMN run_status VARCHAR(24) NOT NULL DEFAULT 'NEVER_RUN',
    ADD COLUMN target_summary VARCHAR(480),
    ADD COLUMN schedule_summary VARCHAR(240) NOT NULL DEFAULT 'MANUAL',
    ADD COLUMN published_version INTEGER,
    ADD COLUMN row_version BIGINT NOT NULL DEFAULT 1,
    ADD COLUMN last_run_at TIMESTAMPTZ,
    ADD COLUMN archived_at TIMESTAMPTZ;

UPDATE control.pipelines
SET normalized_name = lower(name),
    lifecycle = CASE WHEN status = 'DRAFT' THEN 'DRAFT' ELSE 'PUBLISHED' END,
    run_status = CASE WHEN status = 'FAILED' THEN 'FAILED' ELSE 'NEVER_RUN' END;

ALTER TABLE control.pipelines
    ALTER COLUMN normalized_name SET NOT NULL,
    ADD CONSTRAINT pipelines_lifecycle_check CHECK (lifecycle IN ('DRAFT', 'IN_REVIEW', 'PUBLISHED', 'PAUSED', 'ARCHIVED')),
    ADD CONSTRAINT pipelines_run_status_check CHECK (run_status IN ('NEVER_RUN', 'HEALTHY', 'RUNNING', 'LIVE', 'DEGRADED', 'FAILED'));

CREATE UNIQUE INDEX pipelines_normalized_name_active_idx
    ON control.pipelines(normalized_name) WHERE archived_at IS NULL;
CREATE INDEX pipelines_list_idx ON control.pipelines(lifecycle, run_status, updated_at DESC);

CREATE TABLE control.pipeline_drafts (
    pipeline_id UUID PRIMARY KEY REFERENCES control.pipelines(id) ON DELETE CASCADE,
    base_version INTEGER,
    graph JSONB NOT NULL,
    runtime JSONB NOT NULL,
    schedule JSONB NOT NULL,
    etag BIGINT NOT NULL DEFAULT 1,
    updated_by VARCHAR(160) NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE control.pipeline_versions (
    id UUID PRIMARY KEY,
    pipeline_id UUID NOT NULL REFERENCES control.pipelines(id),
    version INTEGER NOT NULL,
    graph JSONB NOT NULL,
    pipeline_ir JSONB NOT NULL,
    job_spec JSONB NOT NULL,
    content_hash VARCHAR(64) NOT NULL,
    validation JSONB NOT NULL,
    published_by VARCHAR(160) NOT NULL,
    published_by_name VARCHAR(240) NOT NULL,
    published_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (pipeline_id, version)
);

CREATE TABLE control.pipeline_dependencies (
    id UUID PRIMARY KEY,
    pipeline_version_id UUID NOT NULL REFERENCES control.pipeline_versions(id) ON DELETE CASCADE,
    dependency_type VARCHAR(40) NOT NULL,
    resource_id VARCHAR(240) NOT NULL,
    resource_name VARCHAR(480),
    node_id VARCHAR(160),
    metadata JSONB NOT NULL DEFAULT '{}',
    UNIQUE (pipeline_version_id, dependency_type, resource_id, node_id)
);
CREATE INDEX pipeline_dependencies_resource_idx ON control.pipeline_dependencies(dependency_type, resource_id);

CREATE TABLE control.pipeline_proposals (
    id UUID PRIMARY KEY,
    pipeline_id UUID NOT NULL REFERENCES control.pipelines(id),
    draft_etag BIGINT NOT NULL,
    status VARCHAR(24) NOT NULL CHECK (status IN ('OPEN', 'APPROVED', 'REJECTED', 'SUPERSEDED')),
    risk_level VARCHAR(16) NOT NULL CHECK (risk_level IN ('NORMAL', 'HIGH')),
    title VARCHAR(240) NOT NULL,
    summary VARCHAR(1000),
    validation JSONB NOT NULL,
    impact JSONB NOT NULL,
    submitted_by VARCHAR(160) NOT NULL,
    submitted_by_name VARCHAR(240) NOT NULL,
    submitted_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    decided_by VARCHAR(160),
    decided_by_name VARCHAR(240),
    decided_at TIMESTAMPTZ,
    decision_comment VARCHAR(1000)
);

CREATE TABLE control.pipeline_proposal_comments (
    id UUID PRIMARY KEY,
    proposal_id UUID NOT NULL REFERENCES control.pipeline_proposals(id) ON DELETE CASCADE,
    author_id VARCHAR(160) NOT NULL,
    author_name VARCHAR(240) NOT NULL,
    body VARCHAR(2000) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

ALTER TABLE control.pipeline_runs
    ADD COLUMN pipeline_version_id UUID REFERENCES control.pipeline_versions(id),
    ADD COLUMN retry_of UUID REFERENCES control.pipeline_runs(id),
    ADD COLUMN requested_by VARCHAR(160),
    ADD COLUMN requested_by_name VARCHAR(240),
    ADD COLUMN diagnostic JSONB,
    ADD COLUMN projection_status VARCHAR(24),
    ADD COLUMN savepoint_path VARCHAR(1000),
    ADD COLUMN updated_at TIMESTAMPTZ NOT NULL DEFAULT now();
CREATE INDEX pipeline_runs_pipeline_idx ON control.pipeline_runs(pipeline_id, started_at DESC);
CREATE INDEX pipeline_runs_flink_idx ON control.pipeline_runs(flink_job_id) WHERE flink_job_id IS NOT NULL;

CREATE TABLE control.pipeline_run_events (
    id UUID PRIMARY KEY,
    pipeline_run_id UUID NOT NULL REFERENCES control.pipeline_runs(id) ON DELETE CASCADE,
    sequence BIGINT NOT NULL,
    event_type VARCHAR(40) NOT NULL,
    status VARCHAR(24),
    message VARCHAR(1000) NOT NULL,
    safe_details JSONB NOT NULL DEFAULT '{}',
    occurred_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (pipeline_run_id, sequence)
);

CREATE TABLE control.pipeline_checkpoints (
    id UUID PRIMARY KEY,
    pipeline_run_id UUID NOT NULL REFERENCES control.pipeline_runs(id) ON DELETE CASCADE,
    checkpoint_type VARCHAR(24) NOT NULL,
    external_id VARCHAR(240),
    location VARCHAR(1000),
    status VARCHAR(24) NOT NULL,
    size_bytes BIGINT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE control.pipeline_schedules (
    pipeline_id UUID PRIMARY KEY REFERENCES control.pipelines(id) ON DELETE CASCADE,
    enabled BOOLEAN NOT NULL DEFAULT FALSE,
    schedule_type VARCHAR(24) NOT NULL DEFAULT 'MANUAL',
    cron_expression VARCHAR(160),
    run_at TIMESTAMPTZ,
    concurrency_policy VARCHAR(24) NOT NULL DEFAULT 'SKIP',
    next_run_at TIMESTAMPTZ,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE control.pipeline_preview_runs (
    id UUID PRIMARY KEY,
    pipeline_id UUID NOT NULL REFERENCES control.pipelines(id) ON DELETE CASCADE,
    draft_etag BIGINT NOT NULL,
    node_id VARCHAR(160) NOT NULL,
    status VARCHAR(24) NOT NULL,
    flink_job_id VARCHAR(160),
    rows JSONB NOT NULL DEFAULT '[]',
    schema_snapshot JSONB NOT NULL DEFAULT '[]',
    diagnostic JSONB,
    requested_by VARCHAR(160) NOT NULL,
    started_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    completed_at TIMESTAMPTZ,
    expires_at TIMESTAMPTZ NOT NULL
);

CREATE TABLE control.workload_credential_grants (
    id UUID PRIMARY KEY,
    pipeline_run_id UUID NOT NULL REFERENCES control.pipeline_runs(id) ON DELETE CASCADE,
    data_source_id UUID NOT NULL REFERENCES control.data_sources(id),
    scope_hash VARCHAR(64) NOT NULL,
    job_signature VARCHAR(64) NOT NULL,
    status VARCHAR(24) NOT NULL DEFAULT 'ACTIVE',
    expires_at TIMESTAMPTZ NOT NULL,
    exchanged_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE control.projection_batches (
    id UUID PRIMARY KEY,
    pipeline_run_id UUID NOT NULL REFERENCES control.pipeline_runs(id) ON DELETE CASCADE,
    correlation_id VARCHAR(240) NOT NULL,
    expected_events BIGINT NOT NULL DEFAULT 0,
    acknowledged_events BIGINT NOT NULL DEFAULT 0,
    failed_events BIGINT NOT NULL DEFAULT 0,
    status VARCHAR(24) NOT NULL DEFAULT 'PENDING',
    completed_at TIMESTAMPTZ,
    UNIQUE (pipeline_run_id, correlation_id)
);

COMMENT ON TABLE control.pipeline_versions IS 'Immutable published Pipeline IR and reproducible Flink job specifications.';
COMMENT ON TABLE control.workload_credential_grants IS 'Short-lived run-scoped grants; plaintext credentials are never persisted here.';
