ALTER TABLE control.data_sources RENAME COLUMN workspace_id TO ontology_id;
ALTER TABLE control.pipelines RENAME COLUMN workspace_id TO ontology_id;
ALTER TABLE control.datasets RENAME COLUMN workspace_id TO ontology_id;
ALTER TABLE control.dashboards RENAME COLUMN workspace_id TO ontology_id;

ALTER TABLE control.ontology_revisions
    ADD COLUMN ontology_id UUID REFERENCES control.ontologies(id);
UPDATE control.ontology_revisions
SET ontology_id = '00000000-0000-0000-0000-00000000a001';
ALTER TABLE control.ontology_revisions ALTER COLUMN ontology_id SET NOT NULL;
CREATE UNIQUE INDEX ontology_revisions_one_active_idx
    ON control.ontology_revisions(ontology_id) WHERE status = 'ACTIVE';
CREATE INDEX ontology_revisions_ontology_created_idx
    ON control.ontology_revisions(ontology_id, created_at DESC);

INSERT INTO control.ontology_revisions(revision, status, activated_at, ontology_id)
SELECT coalesce(max(revision), 0) + 1, 'ACTIVE', now(), '00000000-0000-0000-0000-00000000a002'
FROM control.ontology_revisions
WHERE NOT EXISTS (
    SELECT 1 FROM control.ontology_revisions
    WHERE ontology_id = '00000000-0000-0000-0000-00000000a002'
);

ALTER TABLE control.ontology_deployments
    ADD COLUMN ontology_id UUID REFERENCES control.ontologies(id);
UPDATE control.ontology_deployments d
SET ontology_id = p.ontology_id
FROM control.ontology_proposals p
WHERE p.id = d.proposal_id;
ALTER TABLE control.ontology_deployments ALTER COLUMN ontology_id SET NOT NULL;
CREATE INDEX ontology_deployments_ontology_created_idx
    ON control.ontology_deployments(ontology_id, created_at DESC);

ALTER TABLE control.ontology_health_issues
    ADD COLUMN ontology_id UUID REFERENCES control.ontologies(id);
UPDATE control.ontology_health_issues h
SET ontology_id = COALESCE(
    (SELECT r.ontology_id FROM control.ontology_resources r WHERE r.id = h.resource_id),
    '00000000-0000-0000-0000-00000000a001'
);
ALTER TABLE control.ontology_health_issues ALTER COLUMN ontology_id SET NOT NULL;
ALTER TABLE control.ontology_health_issues
    DROP CONSTRAINT ontology_health_issues_issue_key_key;
ALTER TABLE control.ontology_health_issues
    ADD CONSTRAINT ontology_health_issues_ontology_issue_key_key UNIQUE(ontology_id, issue_key);

ALTER TABLE control.projection_ledger
    ADD COLUMN ontology_id UUID REFERENCES control.ontologies(id);
UPDATE control.projection_ledger l
SET ontology_id = r.ontology_id
FROM control.ontology_revisions r
WHERE r.revision = l.ontology_revision;
ALTER TABLE control.projection_ledger ALTER COLUMN ontology_id SET NOT NULL;
CREATE INDEX projection_ledger_ontology_entity_version_idx
    ON control.projection_ledger(ontology_id, entity_key, entity_version DESC);

ALTER TABLE control.projection_operations
    ADD COLUMN ontology_id UUID REFERENCES control.ontologies(id);
UPDATE control.projection_operations o
SET ontology_id = r.ontology_id
FROM control.ontology_revisions r
WHERE r.revision = o.ontology_revision;
ALTER TABLE control.projection_operations ALTER COLUMN ontology_id SET NOT NULL;

ALTER TABLE control.index_rebuild_jobs
    ADD COLUMN ontology_id UUID REFERENCES control.ontologies(id);
UPDATE control.index_rebuild_jobs
SET ontology_id = '00000000-0000-0000-0000-00000000a001';
ALTER TABLE control.index_rebuild_jobs ALTER COLUMN ontology_id SET NOT NULL;

CREATE TABLE control.ontology_members (
    ontology_id UUID NOT NULL REFERENCES control.ontologies(id) ON DELETE CASCADE,
    member_id VARCHAR(240) NOT NULL,
    member_type VARCHAR(16) NOT NULL DEFAULT 'USER' CHECK (member_type IN ('GROUP', 'SERVICE', 'USER')),
    display_name VARCHAR(240) NOT NULL,
    role VARCHAR(24) NOT NULL CHECK (role IN ('OWNER', 'ADMINISTRATOR', 'BUILDER', 'OPERATOR', 'VIEWER')),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (ontology_id, member_id)
);

INSERT INTO control.ontology_members(ontology_id, member_id, display_name, role)
SELECT id, 'local-user', 'Local User', 'OWNER'
FROM control.ontologies;

CREATE TABLE control.dataset_materialization_rows (
    correlation_id VARCHAR(240) NOT NULL,
    event_id UUID PRIMARY KEY,
    ontology_id UUID NOT NULL REFERENCES control.ontologies(id),
    ontology_revision BIGINT NOT NULL,
    message_id VARCHAR(512) NOT NULL,
    payload JSONB NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX dataset_materialization_rows_correlation_idx
    ON control.dataset_materialization_rows(correlation_id, event_id);
