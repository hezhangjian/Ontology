CREATE TABLE control.action_previews (
    id UUID PRIMARY KEY,
    action_id UUID NOT NULL REFERENCES control.ontology_resources(id),
    action_version INTEGER NOT NULL,
    actor_id VARCHAR(240) NOT NULL,
    object_id VARCHAR(512) NOT NULL,
    expected_version BIGINT,
    parameters JSONB NOT NULL DEFAULT '{}',
    edits JSONB NOT NULL,
    token_hash VARCHAR(64) NOT NULL UNIQUE,
    expires_at TIMESTAMPTZ NOT NULL,
    consumed_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE control.action_executions (
    id UUID PRIMARY KEY,
    preview_id UUID NOT NULL UNIQUE REFERENCES control.action_previews(id),
    action_id UUID NOT NULL REFERENCES control.ontology_resources(id),
    actor_id VARCHAR(240) NOT NULL,
    actor_name VARCHAR(240) NOT NULL,
    idempotency_key VARCHAR(240) NOT NULL UNIQUE,
    correlation_id VARCHAR(240) NOT NULL UNIQUE,
    status VARCHAR(24) NOT NULL CHECK (status IN ('SUBMITTED', 'PROJECTING', 'SUCCEEDED', 'DEGRADED', 'FAILED')),
    safe_error VARCHAR(1000),
    submitted_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    completed_at TIMESTAMPTZ
);

CREATE INDEX action_previews_actor_expiry_idx ON control.action_previews(actor_id, expires_at DESC);
CREATE INDEX action_executions_action_time_idx ON control.action_executions(action_id, submitted_at DESC);
