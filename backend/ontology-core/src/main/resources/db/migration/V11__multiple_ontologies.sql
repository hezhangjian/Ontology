CREATE TABLE control.ontologies (
    id UUID PRIMARY KEY,
    api_name VARCHAR(160) NOT NULL UNIQUE,
    display_name VARCHAR(240) NOT NULL,
    description TEXT NOT NULL DEFAULT '',
    icon VARCHAR(32) NOT NULL DEFAULT 'deployment-unit',
    color VARCHAR(24) NOT NULL DEFAULT '#3157d5',
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

INSERT INTO control.ontologies (id, api_name, display_name, description, icon, color)
VALUES
    ('00000000-0000-0000-0000-00000000a001', 'token_consumption', 'Token 消耗', '人员、小组与模型 Token 使用分析', 'fund', '#3157d5'),
    ('00000000-0000-0000-0000-00000000a002', 'iot_operations', 'IoT 设备运营', '设备、传感器、遥测与告警管理', 'gateway', '#0f8f6f');

ALTER TABLE control.ontology_resources
    ADD COLUMN ontology_id UUID REFERENCES control.ontologies(id);
UPDATE control.ontology_resources
SET ontology_id = '00000000-0000-0000-0000-00000000a001';
ALTER TABLE control.ontology_resources ALTER COLUMN ontology_id SET NOT NULL;
ALTER TABLE control.ontology_resources DROP CONSTRAINT ontology_resources_kind_api_name_key;
ALTER TABLE control.ontology_resources
    ADD CONSTRAINT ontology_resources_ontology_kind_api_name_key UNIQUE (ontology_id, kind, api_name);
CREATE INDEX ontology_resources_ontology_kind_idx
    ON control.ontology_resources(ontology_id, kind, updated_at DESC);
UPDATE control.ontology_resource_versions v
SET definition = v.definition || jsonb_build_object(
    'leftObjectTypeId', l.left_object_type_id,
    'rightObjectTypeId', l.right_object_type_id,
    'cardinality', l.cardinality,
    'sourceMode', l.source_mode,
    'sourcePropertyId', l.source_property_id
)
FROM control.link_type_versions l
WHERE l.version_id=v.id;

ALTER TABLE control.ontology_proposals
    ADD COLUMN ontology_id UUID REFERENCES control.ontologies(id);
UPDATE control.ontology_proposals p
SET ontology_id = COALESCE(
    (SELECT r.ontology_id
     FROM control.ontology_proposal_tasks t
     JOIN control.ontology_resources r ON r.id = t.resource_id
     WHERE t.proposal_id = p.id
     LIMIT 1),
    '00000000-0000-0000-0000-00000000a001'
);
ALTER TABLE control.ontology_proposals ALTER COLUMN ontology_id SET NOT NULL;
CREATE INDEX ontology_proposals_ontology_status_idx
    ON control.ontology_proposals(ontology_id, status, updated_at DESC);

ALTER TABLE control.data_sources ADD COLUMN workspace_id UUID REFERENCES control.ontologies(id);
UPDATE control.data_sources SET workspace_id='00000000-0000-0000-0000-00000000a001';
ALTER TABLE control.data_sources ALTER COLUMN workspace_id SET NOT NULL;
ALTER TABLE control.data_sources DROP CONSTRAINT data_sources_normalized_name_key;
ALTER TABLE control.data_sources ADD CONSTRAINT data_sources_workspace_name_key UNIQUE(workspace_id, normalized_name);

ALTER TABLE control.pipelines ADD COLUMN workspace_id UUID REFERENCES control.ontologies(id);
UPDATE control.pipelines SET workspace_id='00000000-0000-0000-0000-00000000a001';
ALTER TABLE control.pipelines ALTER COLUMN workspace_id SET NOT NULL;
DROP INDEX control.pipelines_normalized_name_active_idx;
CREATE UNIQUE INDEX pipelines_workspace_name_active_idx ON control.pipelines(workspace_id, normalized_name) WHERE archived_at IS NULL;

ALTER TABLE control.datasets ADD COLUMN workspace_id UUID REFERENCES control.ontologies(id);
UPDATE control.datasets SET workspace_id='00000000-0000-0000-0000-00000000a001';
ALTER TABLE control.datasets ALTER COLUMN workspace_id SET NOT NULL;
ALTER TABLE control.datasets DROP CONSTRAINT datasets_normalized_name_key;
ALTER TABLE control.datasets ADD CONSTRAINT datasets_workspace_name_key UNIQUE(workspace_id, normalized_name);

ALTER TABLE control.dashboards ADD COLUMN workspace_id UUID REFERENCES control.ontologies(id);
UPDATE control.dashboards SET workspace_id='00000000-0000-0000-0000-00000000a001';
ALTER TABLE control.dashboards ALTER COLUMN workspace_id SET NOT NULL;
CREATE INDEX dashboards_workspace_updated_idx ON control.dashboards(workspace_id, updated_at DESC);
