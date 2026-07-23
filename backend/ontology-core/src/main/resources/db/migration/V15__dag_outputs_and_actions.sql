ALTER TABLE control.datasets
    ADD COLUMN output_node_id VARCHAR(120) NOT NULL DEFAULT 'output-1';
ALTER TABLE control.datasets
    DROP CONSTRAINT datasets_workspace_name_key;
ALTER TABLE control.datasets
    ADD CONSTRAINT datasets_ontology_name_key UNIQUE(ontology_id, normalized_name);
ALTER TABLE control.datasets
    ADD CONSTRAINT datasets_pipeline_output_key UNIQUE(pipeline_id, output_node_id);

ALTER TABLE control.dataset_materialization_rows
    ADD COLUMN dataset_id UUID REFERENCES control.datasets(id) ON DELETE CASCADE;
UPDATE control.dataset_materialization_rows rows
SET dataset_id = datasets.id
FROM control.pipeline_runs runs
JOIN control.datasets datasets ON datasets.pipeline_id = runs.pipeline_id
WHERE runs.correlation_id = rows.correlation_id
  AND datasets.output_node_id = 'output-1';
DELETE FROM control.dataset_materialization_rows WHERE dataset_id IS NULL;
ALTER TABLE control.dataset_materialization_rows ALTER COLUMN dataset_id SET NOT NULL;
DROP INDEX control.dataset_materialization_rows_correlation_idx;
CREATE INDEX dataset_materialization_rows_correlation_idx
    ON control.dataset_materialization_rows(dataset_id, correlation_id, event_id);

ALTER TABLE control.action_previews
    ADD COLUMN ontology_id UUID REFERENCES control.ontologies(id);
UPDATE control.action_previews p
SET ontology_id = r.ontology_id
FROM control.ontology_resources r
WHERE r.id = p.action_id;
ALTER TABLE control.action_previews ALTER COLUMN ontology_id SET NOT NULL;
ALTER TABLE control.action_previews ALTER COLUMN object_id DROP NOT NULL;
ALTER TABLE control.action_previews
    ADD COLUMN approval_required BOOLEAN NOT NULL DEFAULT false;

ALTER TABLE control.action_executions
    ADD COLUMN ontology_id UUID REFERENCES control.ontologies(id);
UPDATE control.action_executions e
SET ontology_id = r.ontology_id
FROM control.ontology_resources r
WHERE r.id = e.action_id;
ALTER TABLE control.action_executions ALTER COLUMN ontology_id SET NOT NULL;
ALTER TABLE control.action_executions
    ADD COLUMN action_version INTEGER NOT NULL DEFAULT 1;
ALTER TABLE control.action_executions
    ADD COLUMN trace_id VARCHAR(240);
UPDATE control.action_executions SET trace_id = correlation_id WHERE trace_id IS NULL;
ALTER TABLE control.action_executions ALTER COLUMN trace_id SET NOT NULL;
ALTER TABLE control.action_executions
    DROP CONSTRAINT action_executions_status_check;
ALTER TABLE control.action_executions
    ADD CONSTRAINT action_executions_status_check
    CHECK (status IN (
      'PENDING_APPROVAL', 'SUBMITTED', 'PROJECTING', 'SUCCEEDED',
      'DEGRADED', 'FAILED', 'REJECTED'
    ));

CREATE TABLE control.action_execution_reviews (
    id UUID PRIMARY KEY,
    execution_id UUID NOT NULL REFERENCES control.action_executions(id) ON DELETE CASCADE,
    decision VARCHAR(16) NOT NULL CHECK (decision IN ('APPROVED', 'REJECTED')),
    comment TEXT NOT NULL DEFAULT '',
    reviewer_id VARCHAR(240) NOT NULL,
    reviewer_name VARCHAR(240) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE(execution_id, reviewer_id)
);

CREATE TABLE control.action_mutation_outbox (
    id UUID PRIMARY KEY,
    execution_id UUID NOT NULL UNIQUE REFERENCES control.action_executions(id) ON DELETE CASCADE,
    payload JSONB NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING', 'PUBLISHED', 'FAILED')),
    attempts INTEGER NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_error VARCHAR(1000),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    published_at TIMESTAMPTZ
);
CREATE INDEX action_mutation_outbox_pending_idx
    ON control.action_mutation_outbox(status, next_attempt_at, created_at);
