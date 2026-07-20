CREATE TABLE control.dashboards (
    id UUID PRIMARY KEY,
    name VARCHAR(240) NOT NULL,
    description TEXT NOT NULL DEFAULT '',
    owner_id VARCHAR(240) NOT NULL,
    owner_name VARCHAR(240) NOT NULL,
    visibility VARCHAR(24) NOT NULL CHECK (visibility IN ('PRIVATE', 'USERS', 'GROUPS', 'TEAM', 'ORGANIZATION')),
    lifecycle VARCHAR(24) NOT NULL CHECK (lifecycle IN ('DRAFT', 'VALIDATING', 'READY', 'PUBLISHING', 'PUBLISHED', 'VALIDATION_FAILED', 'PUBLISH_FAILED', 'ARCHIVED')),
    refresh_policy VARCHAR(16) NOT NULL CHECK (refresh_policy IN ('MANUAL', 'OFF', '1_MIN', '5_MIN', '15_MIN', '60_MIN')),
    tags TEXT[] NOT NULL DEFAULT '{}',
    current_version_id UUID,
    active_draft_id UUID,
    etag BIGINT NOT NULL DEFAULT 1,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_published_at TIMESTAMPTZ,
    archived_at TIMESTAMPTZ
);

CREATE TABLE control.dashboard_versions (
    id UUID PRIMARY KEY,
    dashboard_id UUID NOT NULL REFERENCES control.dashboards(id),
    version INTEGER NOT NULL,
    definition JSONB NOT NULL,
    schema_version INTEGER NOT NULL,
    ontology_revision BIGINT NOT NULL REFERENCES control.ontology_revisions(revision),
    query_plan_hash VARCHAR(64) NOT NULL,
    published_by VARCHAR(240) NOT NULL,
    published_by_name VARCHAR(240) NOT NULL,
    release_notes TEXT NOT NULL DEFAULT '',
    health_status VARCHAR(16) NOT NULL CHECK (health_status IN ('HEALTHY', 'WARNING', 'ERROR', 'UNKNOWN')),
    published_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (dashboard_id, version)
);

CREATE TABLE control.dashboard_drafts (
    id UUID PRIMARY KEY,
    dashboard_id UUID NOT NULL REFERENCES control.dashboards(id),
    base_version_id UUID REFERENCES control.dashboard_versions(id),
    definition JSONB NOT NULL,
    schema_version INTEGER NOT NULL,
    etag BIGINT NOT NULL DEFAULT 1,
    status VARCHAR(24) NOT NULL CHECK (status IN ('DRAFT', 'VALIDATING', 'READY', 'VALIDATION_FAILED', 'PUBLISH_FAILED')),
    updated_by VARCHAR(240) NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

ALTER TABLE control.dashboards
    ADD CONSTRAINT dashboards_current_version_fk FOREIGN KEY (current_version_id) REFERENCES control.dashboard_versions(id),
    ADD CONSTRAINT dashboards_active_draft_fk FOREIGN KEY (active_draft_id) REFERENCES control.dashboard_drafts(id);

CREATE TABLE control.dashboard_pages (
    id UUID PRIMARY KEY,
    dashboard_id UUID NOT NULL REFERENCES control.dashboards(id),
    draft_id UUID REFERENCES control.dashboard_drafts(id) ON DELETE CASCADE,
    version_id UUID REFERENCES control.dashboard_versions(id) ON DELETE CASCADE,
    stable_id UUID NOT NULL,
    name VARCHAR(160) NOT NULL,
    description TEXT NOT NULL DEFAULT '',
    page_order INTEGER NOT NULL,
    CHECK ((draft_id IS NULL) <> (version_id IS NULL)),
    UNIQUE (draft_id, stable_id),
    UNIQUE (version_id, stable_id)
);

CREATE TABLE control.dashboard_data_sources (
    id UUID PRIMARY KEY,
    dashboard_id UUID NOT NULL REFERENCES control.dashboards(id),
    draft_id UUID REFERENCES control.dashboard_drafts(id) ON DELETE CASCADE,
    version_id UUID REFERENCES control.dashboard_versions(id) ON DELETE CASCADE,
    stable_id UUID NOT NULL,
    name VARCHAR(160) NOT NULL,
    source_kind VARCHAR(24) NOT NULL CHECK (source_kind IN ('OBJECT_SET', 'EXPLORATION', 'OBJECT_LIST', 'FUNCTION')),
    object_type_id UUID REFERENCES control.ontology_resources(id),
    reference_id UUID,
    reference_version INTEGER,
    query_ast JSONB NOT NULL DEFAULT '{}',
    ontology_revision BIGINT NOT NULL,
    CHECK ((draft_id IS NULL) <> (version_id IS NULL)),
    UNIQUE (draft_id, stable_id),
    UNIQUE (version_id, stable_id)
);

CREATE TABLE control.dashboard_widgets (
    id UUID PRIMARY KEY,
    dashboard_id UUID NOT NULL REFERENCES control.dashboards(id),
    draft_id UUID REFERENCES control.dashboard_drafts(id) ON DELETE CASCADE,
    version_id UUID REFERENCES control.dashboard_versions(id) ON DELETE CASCADE,
    stable_id UUID NOT NULL,
    page_stable_id UUID NOT NULL,
    data_source_stable_id UUID,
    widget_type VARCHAR(24) NOT NULL CHECK (widget_type IN ('METRIC', 'LINE', 'AREA', 'BAR', 'STACKED_BAR', 'PIE', 'DONUT', 'SCATTER', 'OBJECT_TABLE', 'PIVOT', 'MARKDOWN', 'FILTER', 'SECTION')),
    title VARCHAR(240) NOT NULL,
    description TEXT NOT NULL DEFAULT '',
    layout JSONB NOT NULL,
    config JSONB NOT NULL DEFAULT '{}',
    interaction JSONB NOT NULL DEFAULT '{}',
    CHECK ((draft_id IS NULL) <> (version_id IS NULL)),
    UNIQUE (draft_id, stable_id),
    UNIQUE (version_id, stable_id)
);

CREATE TABLE control.dashboard_filter_variables (
    id UUID PRIMARY KEY,
    dashboard_id UUID NOT NULL REFERENCES control.dashboards(id),
    draft_id UUID REFERENCES control.dashboard_drafts(id) ON DELETE CASCADE,
    version_id UUID REFERENCES control.dashboard_versions(id) ON DELETE CASCADE,
    stable_id UUID NOT NULL,
    name VARCHAR(160) NOT NULL,
    value_type VARCHAR(24) NOT NULL,
    control_type VARCHAR(24) NOT NULL,
    scope VARCHAR(16) NOT NULL CHECK (scope IN ('GLOBAL', 'PAGE', 'WIDGET')),
    scope_id UUID,
    default_value JSONB,
    required BOOLEAN NOT NULL DEFAULT false,
    allow_empty BOOLEAN NOT NULL DEFAULT true,
    sensitive BOOLEAN NOT NULL DEFAULT false,
    apply_mode VARCHAR(16) NOT NULL CHECK (apply_mode IN ('AUTO', 'MANUAL', 'DEFERRED')),
    CHECK ((draft_id IS NULL) <> (version_id IS NULL)),
    UNIQUE (draft_id, stable_id),
    UNIQUE (version_id, stable_id)
);

CREATE TABLE control.dashboard_filter_bindings (
    id UUID PRIMARY KEY,
    dashboard_id UUID NOT NULL REFERENCES control.dashboards(id),
    draft_id UUID REFERENCES control.dashboard_drafts(id) ON DELETE CASCADE,
    version_id UUID REFERENCES control.dashboard_versions(id) ON DELETE CASCADE,
    filter_stable_id UUID NOT NULL,
    data_source_stable_id UUID NOT NULL,
    property_id UUID NOT NULL REFERENCES control.properties(id),
    operator VARCHAR(32) NOT NULL,
    CHECK ((draft_id IS NULL) <> (version_id IS NULL))
);

CREATE TABLE control.dashboard_dependencies (
    id UUID PRIMARY KEY,
    dashboard_id UUID NOT NULL REFERENCES control.dashboards(id),
    version_id UUID NOT NULL REFERENCES control.dashboard_versions(id) ON DELETE CASCADE,
    dependency_kind VARCHAR(24) NOT NULL,
    resource_id UUID NOT NULL,
    resource_version INTEGER,
    ontology_revision BIGINT NOT NULL,
    required BOOLEAN NOT NULL DEFAULT true,
    health_status VARCHAR(16) NOT NULL CHECK (health_status IN ('HEALTHY', 'WARNING', 'ERROR', 'UNKNOWN')),
    UNIQUE (version_id, dependency_kind, resource_id)
);

CREATE TABLE control.dashboard_permissions (
    dashboard_id UUID NOT NULL REFERENCES control.dashboards(id) ON DELETE CASCADE,
    subject_type VARCHAR(16) NOT NULL CHECK (subject_type IN ('USER', 'GROUP', 'TEAM')),
    subject_id VARCHAR(240) NOT NULL,
    permission_role VARCHAR(16) NOT NULL CHECK (permission_role IN ('VIEWER', 'EDITOR', 'OWNER')),
    granted_by VARCHAR(240) NOT NULL,
    granted_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (dashboard_id, subject_type, subject_id)
);

CREATE TABLE control.dashboard_favorites (
    dashboard_id UUID NOT NULL REFERENCES control.dashboards(id) ON DELETE CASCADE,
    user_id VARCHAR(240) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (dashboard_id, user_id)
);

CREATE TABLE control.dashboard_edit_locks (
    dashboard_id UUID PRIMARY KEY REFERENCES control.dashboards(id) ON DELETE CASCADE,
    holder_id VARCHAR(240) NOT NULL,
    holder_name VARCHAR(240) NOT NULL,
    lease_token UUID NOT NULL UNIQUE,
    acquired_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    expires_at TIMESTAMPTZ NOT NULL
);

CREATE TABLE control.dashboard_query_plans (
    id UUID PRIMARY KEY,
    dashboard_id UUID NOT NULL REFERENCES control.dashboards(id),
    version_id UUID NOT NULL UNIQUE REFERENCES control.dashboard_versions(id),
    plan_hash VARCHAR(64) NOT NULL,
    definition_hash VARCHAR(64) NOT NULL,
    ontology_revision BIGINT NOT NULL,
    policy_revision BIGINT NOT NULL,
    estimated_cost INTEGER NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE control.dashboard_query_runs (
    id UUID PRIMARY KEY,
    plan_id UUID NOT NULL REFERENCES control.dashboard_query_plans(id),
    dashboard_id UUID NOT NULL REFERENCES control.dashboards(id),
    version_id UUID NOT NULL REFERENCES control.dashboard_versions(id),
    actor_id VARCHAR(240) NOT NULL,
    page_id UUID NOT NULL,
    refresh_id UUID NOT NULL,
    security_context_hash VARCHAR(64) NOT NULL,
    filter_hash VARCHAR(64) NOT NULL,
    status VARCHAR(24) NOT NULL CHECK (status IN ('RUNNING', 'SUCCEEDED', 'PARTIAL', 'FAILED')),
    widget_count INTEGER NOT NULL,
    succeeded_count INTEGER NOT NULL DEFAULT 0,
    failed_count INTEGER NOT NULL DEFAULT 0,
    cache_hit_count INTEGER NOT NULL DEFAULT 0,
    duration_ms BIGINT,
    watermark TIMESTAMPTZ,
    correlation_id UUID NOT NULL,
    safe_error VARCHAR(1000),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    completed_at TIMESTAMPTZ
);

CREATE TABLE control.dashboard_health_issues (
    id UUID PRIMARY KEY,
    dashboard_id UUID NOT NULL REFERENCES control.dashboards(id) ON DELETE CASCADE,
    version_id UUID REFERENCES control.dashboard_versions(id) ON DELETE CASCADE,
    page_stable_id UUID,
    widget_stable_id UUID,
    data_source_stable_id UUID,
    severity VARCHAR(16) NOT NULL CHECK (severity IN ('WARNING', 'ERROR', 'UNKNOWN')),
    issue_code VARCHAR(80) NOT NULL,
    summary VARCHAR(1000) NOT NULL,
    status VARCHAR(16) NOT NULL CHECK (status IN ('OPEN', 'RESOLVED')),
    detected_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    resolved_at TIMESTAMPTZ
);

CREATE INDEX dashboards_owner_updated_idx ON control.dashboards(owner_id, updated_at DESC);
CREATE INDEX dashboards_lifecycle_updated_idx ON control.dashboards(lifecycle, updated_at DESC);
CREATE INDEX dashboard_query_runs_dashboard_created_idx ON control.dashboard_query_runs(dashboard_id, created_at DESC);
CREATE INDEX dashboard_health_open_idx ON control.dashboard_health_issues(dashboard_id, status, severity);

COMMENT ON TABLE control.dashboard_versions IS 'Immutable dashboard definitions and query-plan evidence; object data is queried under the current caller.';
COMMENT ON COLUMN control.dashboard_drafts.definition IS 'Versioned typed dashboard schema mirrored into normalized page, data-source, widget, filter, and binding tables.';
COMMENT ON TABLE control.dashboard_query_runs IS 'Stores execution metadata and hashes only, never object rows, aggregate results, or sensitive filter values.';
