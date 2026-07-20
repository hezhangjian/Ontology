CREATE TABLE control.saved_explorations (
    id UUID PRIMARY KEY,
    name VARCHAR(240) NOT NULL,
    description TEXT NOT NULL DEFAULT '',
    object_type_id UUID NOT NULL REFERENCES control.ontology_resources(id),
    owner_id VARCHAR(240) NOT NULL,
    owner_name VARCHAR(240) NOT NULL,
    visibility VARCHAR(16) NOT NULL CHECK (visibility IN ('PRIVATE', 'SHARED')),
    current_version INTEGER NOT NULL DEFAULT 1,
    etag BIGINT NOT NULL DEFAULT 1,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE control.saved_exploration_versions (
    id UUID PRIMARY KEY,
    exploration_id UUID NOT NULL REFERENCES control.saved_explorations(id) ON DELETE CASCADE,
    version INTEGER NOT NULL,
    ontology_revision BIGINT NOT NULL REFERENCES control.ontology_revisions(revision),
    query_ast JSONB NOT NULL,
    columns JSONB NOT NULL DEFAULT '[]',
    perspective VARCHAR(24) NOT NULL CHECK (perspective IN ('TABLE', 'CARDS', 'ANALYSIS', 'GRAPH', 'COMPARE')),
    view_config JSONB NOT NULL DEFAULT '{}',
    created_by VARCHAR(240) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (exploration_id, version)
);

CREATE TABLE control.object_lists (
    id UUID PRIMARY KEY,
    name VARCHAR(240) NOT NULL,
    description TEXT NOT NULL DEFAULT '',
    object_type_id UUID NOT NULL REFERENCES control.ontology_resources(id),
    source_exploration_id UUID REFERENCES control.saved_explorations(id),
    owner_id VARCHAR(240) NOT NULL,
    owner_name VARCHAR(240) NOT NULL,
    visibility VARCHAR(16) NOT NULL CHECK (visibility IN ('PRIVATE', 'SHARED')),
    etag BIGINT NOT NULL DEFAULT 1,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE control.object_list_items (
    list_id UUID NOT NULL REFERENCES control.object_lists(id) ON DELETE CASCADE,
    object_id VARCHAR(512) NOT NULL,
    added_by VARCHAR(240) NOT NULL,
    added_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (list_id, object_id)
);

CREATE TABLE control.explorer_layouts (
    id UUID PRIMARY KEY,
    user_id VARCHAR(240) NOT NULL,
    object_type_id UUID NOT NULL REFERENCES control.ontology_resources(id),
    layout JSONB NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (user_id, object_type_id)
);

CREATE TABLE control.explorer_favorites (
    user_id VARCHAR(240) NOT NULL,
    resource_kind VARCHAR(24) NOT NULL CHECK (resource_kind IN ('OBJECT', 'OBJECT_TYPE', 'EXPLORATION', 'LIST')),
    resource_id VARCHAR(512) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (user_id, resource_kind, resource_id)
);

CREATE TABLE control.explorer_recent_items (
    user_id VARCHAR(240) NOT NULL,
    resource_kind VARCHAR(24) NOT NULL CHECK (resource_kind IN ('OBJECT', 'OBJECT_TYPE', 'EXPLORATION', 'LIST', 'SEARCH')),
    resource_id VARCHAR(512) NOT NULL,
    object_type_id UUID,
    title VARCHAR(240) NOT NULL,
    visited_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (user_id, resource_kind, resource_id)
);

CREATE TABLE control.selection_tokens (
    id UUID PRIMARY KEY,
    token_hash VARCHAR(64) NOT NULL UNIQUE,
    owner_id VARCHAR(240) NOT NULL,
    object_type_id UUID NOT NULL REFERENCES control.ontology_resources(id),
    query_fingerprint VARCHAR(64) NOT NULL,
    ontology_revision BIGINT NOT NULL,
    policy_revision BIGINT NOT NULL,
    object_count INTEGER NOT NULL CHECK (object_count BETWEEN 0 AND 1000),
    expires_at TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE control.selection_token_items (
    token_id UUID NOT NULL REFERENCES control.selection_tokens(id) ON DELETE CASCADE,
    object_id VARCHAR(512) NOT NULL,
    object_version BIGINT NOT NULL,
    PRIMARY KEY (token_id, object_id)
);

CREATE TABLE control.export_jobs (
    id UUID PRIMARY KEY,
    owner_id VARCHAR(240) NOT NULL,
    owner_name VARCHAR(240) NOT NULL,
    object_type_id UUID NOT NULL REFERENCES control.ontology_resources(id),
    query_ast JSONB NOT NULL,
    query_fingerprint VARCHAR(64) NOT NULL,
    columns UUID[] NOT NULL DEFAULT '{}',
    format VARCHAR(8) NOT NULL CHECK (format IN ('CSV', 'IDS')),
    status VARCHAR(24) NOT NULL CHECK (status IN ('PENDING', 'RUNNING', 'SUCCEEDED', 'FAILED', 'CANCELLED', 'EXPIRED')),
    row_count BIGINT NOT NULL DEFAULT 0,
    object_key VARCHAR(512),
    content_hash VARCHAR(64),
    safe_error VARCHAR(1000),
    ontology_revision BIGINT NOT NULL,
    policy_revision BIGINT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    completed_at TIMESTAMPTZ,
    expires_at TIMESTAMPTZ NOT NULL
);

CREATE TABLE control.bulk_action_jobs (
    id UUID PRIMARY KEY,
    owner_id VARCHAR(240) NOT NULL,
    owner_name VARCHAR(240) NOT NULL,
    action_id UUID NOT NULL REFERENCES control.ontology_resources(id),
    selection_token_id UUID NOT NULL REFERENCES control.selection_tokens(id),
    status VARCHAR(24) NOT NULL CHECK (status IN ('PENDING', 'RUNNING', 'SUCCEEDED', 'PARTIAL', 'FAILED', 'CANCELLED')),
    total_count INTEGER NOT NULL CHECK (total_count BETWEEN 1 AND 1000),
    succeeded_count INTEGER NOT NULL DEFAULT 0,
    failed_count INTEGER NOT NULL DEFAULT 0,
    skipped_count INTEGER NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    completed_at TIMESTAMPTZ
);

CREATE TABLE control.bulk_action_items (
    job_id UUID NOT NULL REFERENCES control.bulk_action_jobs(id) ON DELETE CASCADE,
    object_id VARCHAR(512) NOT NULL,
    object_version BIGINT NOT NULL,
    status VARCHAR(24) NOT NULL CHECK (status IN ('PENDING', 'PREVIEWED', 'SUCCEEDED', 'FAILED', 'SKIPPED')),
    safe_error VARCHAR(1000),
    attempt INTEGER NOT NULL DEFAULT 0,
    PRIMARY KEY (job_id, object_id)
);

CREATE INDEX saved_explorations_owner_updated_idx ON control.saved_explorations(owner_id, updated_at DESC);
CREATE INDEX object_lists_owner_updated_idx ON control.object_lists(owner_id, updated_at DESC);
CREATE INDEX explorer_recent_user_visited_idx ON control.explorer_recent_items(user_id, visited_at DESC);
CREATE INDEX selection_tokens_owner_expiry_idx ON control.selection_tokens(owner_id, expires_at);
CREATE INDEX export_jobs_owner_created_idx ON control.export_jobs(owner_id, created_at DESC);
CREATE INDEX bulk_action_jobs_owner_created_idx ON control.bulk_action_jobs(owner_id, created_at DESC);

COMMENT ON TABLE control.saved_exploration_versions IS 'Stores typed Object Set AST and view configuration, never object bodies or result rows.';
COMMENT ON TABLE control.object_list_items IS 'Stores stable object references only; current object data remains in HugeGraph.';
COMMENT ON TABLE control.export_jobs IS 'Stores private MinIO object references and hashes, never exported object content.';
