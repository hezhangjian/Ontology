CREATE TABLE control.ontology_resources (
    id UUID PRIMARY KEY,
    kind VARCHAR(24) NOT NULL CHECK (kind IN ('OBJECT_TYPE', 'LINK_TYPE', 'INTERFACE', 'ACTION', 'FUNCTION')),
    api_name VARCHAR(160) NOT NULL,
    display_name VARCHAR(240) NOT NULL,
    description TEXT NOT NULL DEFAULT '',
    physical_key VARCHAR(160) NOT NULL UNIQUE,
    owner_id VARCHAR(240) NOT NULL,
    owner_name VARCHAR(240) NOT NULL,
    maturity VARCHAR(24) NOT NULL CHECK (maturity IN ('EXPERIMENTAL', 'ACTIVE', 'DEPRECATED')),
    promoted BOOLEAN NOT NULL DEFAULT false,
    tags TEXT[] NOT NULL DEFAULT '{}',
    tombstoned BOOLEAN NOT NULL DEFAULT false,
    etag BIGINT NOT NULL DEFAULT 1,
    latest_version INTEGER NOT NULL DEFAULT 1,
    active_version INTEGER,
    published_revision BIGINT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (kind, api_name)
);

CREATE TABLE control.ontology_resource_versions (
    id UUID PRIMARY KEY,
    resource_id UUID NOT NULL REFERENCES control.ontology_resources(id),
    version INTEGER NOT NULL,
    lifecycle VARCHAR(24) NOT NULL CHECK (lifecycle IN ('DRAFT', 'IN_REVIEW', 'APPROVED', 'PUBLISHED', 'REJECTED', 'RETIRED')),
    display_name VARCHAR(240) NOT NULL,
    description TEXT NOT NULL DEFAULT '',
    maturity VARCHAR(24) NOT NULL,
    promoted BOOLEAN NOT NULL DEFAULT false,
    owner_id VARCHAR(240) NOT NULL,
    owner_name VARCHAR(240) NOT NULL,
    tags TEXT[] NOT NULL DEFAULT '{}',
    definition JSONB NOT NULL DEFAULT '{}',
    fingerprint VARCHAR(64) NOT NULL,
    created_by VARCHAR(240) NOT NULL,
    created_by_name VARCHAR(240) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    published_revision BIGINT,
    published_at TIMESTAMPTZ,
    UNIQUE (resource_id, version)
);

CREATE TABLE control.object_type_versions (
    version_id UUID PRIMARY KEY REFERENCES control.ontology_resource_versions(id),
    resource_id UUID NOT NULL REFERENCES control.ontology_resources(id),
    source_mode VARCHAR(24) NOT NULL CHECK (source_mode IN ('PIPELINE', 'ACTION')),
    primary_pipeline_id UUID,
    primary_property_id UUID,
    title_property_id UUID,
    object_count BIGINT NOT NULL DEFAULT 0,
    projection_status VARCHAR(24) NOT NULL DEFAULT 'NOT_DEPLOYED'
);

CREATE TABLE control.properties (
    id UUID PRIMARY KEY,
    object_type_id UUID NOT NULL REFERENCES control.ontology_resources(id),
    api_name VARCHAR(160) NOT NULL,
    physical_key VARCHAR(160) NOT NULL UNIQUE,
    tombstoned BOOLEAN NOT NULL DEFAULT false,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (object_type_id, api_name)
);

CREATE TABLE control.property_versions (
    id UUID PRIMARY KEY,
    property_id UUID NOT NULL REFERENCES control.properties(id),
    object_type_version_id UUID NOT NULL REFERENCES control.object_type_versions(version_id),
    display_name VARCHAR(240) NOT NULL,
    description TEXT NOT NULL DEFAULT '',
    value_type VARCHAR(24) NOT NULL CHECK (value_type IN ('STRING', 'INTEGER', 'LONG', 'DECIMAL', 'BOOLEAN', 'DATE', 'DATETIME', 'ENUM', 'STRING_ARRAY', 'INTEGER_ARRAY', 'JSON')),
    required BOOLEAN NOT NULL DEFAULT false,
    primary_key BOOLEAN NOT NULL DEFAULT false,
    title_property BOOLEAN NOT NULL DEFAULT false,
    searchable BOOLEAN NOT NULL DEFAULT true,
    filterable BOOLEAN NOT NULL DEFAULT true,
    sortable BOOLEAN NOT NULL DEFAULT false,
    sensitive BOOLEAN NOT NULL DEFAULT false,
    masking_policy VARCHAR(80),
    analyzer VARCHAR(80),
    source_field VARCHAR(240),
    enum_values TEXT[] NOT NULL DEFAULT '{}',
    UNIQUE (property_id, object_type_version_id)
);

CREATE TABLE control.link_type_versions (
    version_id UUID PRIMARY KEY REFERENCES control.ontology_resource_versions(id),
    resource_id UUID NOT NULL REFERENCES control.ontology_resources(id),
    left_object_type_id UUID NOT NULL REFERENCES control.ontology_resources(id),
    right_object_type_id UUID NOT NULL REFERENCES control.ontology_resources(id),
    cardinality VARCHAR(8) NOT NULL CHECK (cardinality IN ('1:1', '1:N', 'N:1', 'N:M')),
    source_mode VARCHAR(24) NOT NULL CHECK (source_mode IN ('FOREIGN_KEY', 'PIPELINE')),
    source_property_id UUID REFERENCES control.properties(id),
    pipeline_id UUID,
    left_display_name VARCHAR(240) NOT NULL,
    right_display_name VARCHAR(240) NOT NULL
);

CREATE TABLE control.interface_versions (
    version_id UUID PRIMARY KEY REFERENCES control.ontology_resource_versions(id),
    resource_id UUID NOT NULL REFERENCES control.ontology_resources(id)
);

CREATE TABLE control.interface_slots (
    id UUID PRIMARY KEY,
    interface_version_id UUID NOT NULL REFERENCES control.interface_versions(version_id),
    api_name VARCHAR(160) NOT NULL,
    display_name VARCHAR(240) NOT NULL,
    value_type VARCHAR(24) NOT NULL,
    required BOOLEAN NOT NULL DEFAULT false,
    UNIQUE (interface_version_id, api_name)
);

CREATE TABLE control.interface_implementations (
    id UUID PRIMARY KEY,
    interface_version_id UUID NOT NULL REFERENCES control.interface_versions(version_id),
    object_type_id UUID NOT NULL REFERENCES control.ontology_resources(id),
    slot_id UUID NOT NULL REFERENCES control.interface_slots(id),
    property_id UUID NOT NULL REFERENCES control.properties(id),
    UNIQUE (interface_version_id, object_type_id, slot_id)
);

CREATE TABLE control.action_types (
    resource_id UUID PRIMARY KEY REFERENCES control.ontology_resources(id),
    target_object_type_id UUID NOT NULL REFERENCES control.ontology_resources(id)
);

CREATE TABLE control.action_type_versions (
    version_id UUID PRIMARY KEY REFERENCES control.ontology_resource_versions(id),
    resource_id UUID NOT NULL REFERENCES control.action_types(resource_id),
    operation VARCHAR(24) NOT NULL CHECK (operation IN ('CREATE', 'UPDATE', 'RETIRE', 'LINK', 'UNLINK')),
    approval_policy VARCHAR(24) NOT NULL CHECK (approval_policy IN ('NONE', 'ALWAYS', 'CONDITIONAL')),
    rules JSONB NOT NULL DEFAULT '[]',
    submit_condition JSONB NOT NULL DEFAULT '{}'
);

CREATE TABLE control.action_parameters (
    id UUID PRIMARY KEY,
    action_version_id UUID NOT NULL REFERENCES control.action_type_versions(version_id),
    api_name VARCHAR(160) NOT NULL,
    display_name VARCHAR(240) NOT NULL,
    value_type VARCHAR(48) NOT NULL,
    required BOOLEAN NOT NULL DEFAULT false,
    sensitive BOOLEAN NOT NULL DEFAULT false,
    default_value JSONB,
    UNIQUE (action_version_id, api_name)
);

CREATE TABLE control.function_types (
    resource_id UUID PRIMARY KEY REFERENCES control.ontology_resources(id)
);

CREATE TABLE control.function_type_versions (
    version_id UUID PRIMARY KEY REFERENCES control.ontology_resource_versions(id),
    resource_id UUID NOT NULL REFERENCES control.function_types(resource_id),
    output_type VARCHAR(48) NOT NULL,
    query_dsl JSONB NOT NULL,
    dependency_ids UUID[] NOT NULL DEFAULT '{}',
    timeout_ms INTEGER NOT NULL CHECK (timeout_ms BETWEEN 100 AND 30000),
    max_results INTEGER NOT NULL CHECK (max_results BETWEEN 1 AND 10000),
    cache_seconds INTEGER NOT NULL CHECK (cache_seconds BETWEEN 0 AND 86400)
);

CREATE TABLE control.function_parameters (
    id UUID PRIMARY KEY,
    function_version_id UUID NOT NULL REFERENCES control.function_type_versions(version_id),
    api_name VARCHAR(160) NOT NULL,
    display_name VARCHAR(240) NOT NULL,
    value_type VARCHAR(48) NOT NULL,
    required BOOLEAN NOT NULL DEFAULT false,
    UNIQUE (function_version_id, api_name)
);

CREATE TABLE control.ontology_mappings (
    id UUID PRIMARY KEY,
    resource_version_id UUID NOT NULL REFERENCES control.ontology_resource_versions(id),
    pipeline_id UUID REFERENCES control.pipelines(id),
    pipeline_version INTEGER,
    sink_node_id VARCHAR(160),
    source_field VARCHAR(240),
    property_id UUID REFERENCES control.properties(id),
    transform_path TEXT[] NOT NULL DEFAULT '{}',
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE control.ontology_proposals (
    id UUID PRIMARY KEY,
    title VARCHAR(240) NOT NULL,
    description TEXT NOT NULL DEFAULT '',
    status VARCHAR(32) NOT NULL CHECK (status IN ('DRAFT', 'VALIDATING', 'IN_REVIEW', 'APPROVED', 'PUBLISHING', 'PUBLISHED', 'CHANGES_REQUESTED', 'REJECTED', 'FAILED', 'CLOSED')),
    baseline_revision BIGINT NOT NULL,
    risk_level VARCHAR(16) NOT NULL DEFAULT 'LOW' CHECK (risk_level IN ('LOW', 'MEDIUM', 'HIGH', 'BLOCKED')),
    validation JSONB NOT NULL DEFAULT '[]',
    impact JSONB NOT NULL DEFAULT '{}',
    created_by VARCHAR(240) NOT NULL,
    created_by_name VARCHAR(240) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    submitted_at TIMESTAMPTZ,
    published_revision BIGINT
);

CREATE TABLE control.ontology_proposal_tasks (
    id UUID PRIMARY KEY,
    proposal_id UUID NOT NULL REFERENCES control.ontology_proposals(id),
    resource_id UUID NOT NULL REFERENCES control.ontology_resources(id),
    resource_version_id UUID NOT NULL REFERENCES control.ontology_resource_versions(id),
    status VARCHAR(24) NOT NULL DEFAULT 'PENDING',
    risk_level VARCHAR(16) NOT NULL DEFAULT 'LOW',
    UNIQUE (proposal_id, resource_id)
);

CREATE TABLE control.ontology_reviews (
    id UUID PRIMARY KEY,
    proposal_id UUID NOT NULL REFERENCES control.ontology_proposals(id),
    decision VARCHAR(24) NOT NULL CHECK (decision IN ('APPROVED', 'CHANGES_REQUESTED', 'REJECTED')),
    comment TEXT NOT NULL DEFAULT '',
    reviewer_id VARCHAR(240) NOT NULL,
    reviewer_name VARCHAR(240) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE control.ontology_comments (
    id UUID PRIMARY KEY,
    proposal_id UUID NOT NULL REFERENCES control.ontology_proposals(id),
    body TEXT NOT NULL,
    author_id VARCHAR(240) NOT NULL,
    author_name VARCHAR(240) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE control.ontology_deployments (
    id UUID PRIMARY KEY,
    proposal_id UUID NOT NULL REFERENCES control.ontology_proposals(id),
    target_revision BIGINT NOT NULL,
    status VARCHAR(24) NOT NULL CHECK (status IN ('PENDING', 'RUNNING', 'SUCCEEDED', 'FAILED')),
    current_step VARCHAR(48) NOT NULL,
    attempt INTEGER NOT NULL DEFAULT 1,
    safe_error TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    started_at TIMESTAMPTZ,
    completed_at TIMESTAMPTZ
);

CREATE TABLE control.ontology_deployment_steps (
    id UUID PRIMARY KEY,
    deployment_id UUID NOT NULL REFERENCES control.ontology_deployments(id),
    step_order INTEGER NOT NULL,
    step_name VARCHAR(48) NOT NULL,
    status VARCHAR(24) NOT NULL CHECK (status IN ('PENDING', 'RUNNING', 'SUCCEEDED', 'FAILED')),
    external_resource VARCHAR(320),
    safe_error TEXT,
    started_at TIMESTAMPTZ,
    completed_at TIMESTAMPTZ,
    UNIQUE (deployment_id, step_order)
);

CREATE TABLE control.ontology_health_issues (
    id UUID PRIMARY KEY,
    issue_key VARCHAR(320) NOT NULL UNIQUE,
    severity VARCHAR(16) NOT NULL CHECK (severity IN ('INFO', 'WARNING', 'ERROR', 'CRITICAL')),
    category VARCHAR(80) NOT NULL,
    resource_id UUID REFERENCES control.ontology_resources(id),
    title VARCHAR(240) NOT NULL,
    evidence TEXT NOT NULL DEFAULT '',
    recommendation TEXT NOT NULL DEFAULT '',
    owner_name VARCHAR(240),
    status VARCHAR(24) NOT NULL DEFAULT 'OPEN' CHECK (status IN ('OPEN', 'ACCEPTED', 'RESOLVED')),
    first_seen_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_seen_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    accepted_until TIMESTAMPTZ
);

CREATE INDEX ontology_resources_kind_updated_idx ON control.ontology_resources(kind, updated_at DESC);
CREATE INDEX ontology_resource_versions_resource_idx ON control.ontology_resource_versions(resource_id, version DESC);
CREATE INDEX ontology_proposals_status_updated_idx ON control.ontology_proposals(status, updated_at DESC);
CREATE INDEX ontology_deployments_proposal_idx ON control.ontology_deployments(proposal_id, created_at DESC);

INSERT INTO control.ontology_resources
    (id, kind, api_name, display_name, description, physical_key, owner_id, owner_name, maturity, promoted, active_version, published_revision)
VALUES
    ('00000000-0000-0000-0000-000000000101', 'OBJECT_TYPE', 'Department', '部门', '组织部门', 'Department', 'platform', 'Platform', 'ACTIVE', true, 1, 1),
    ('00000000-0000-0000-0000-000000000102', 'OBJECT_TYPE', 'Employee', '员工', '员工主数据', 'Employee', 'platform', 'Platform', 'ACTIVE', true, 1, 1),
    ('00000000-0000-0000-0000-000000000201', 'LINK_TYPE', 'member_of', '所属部门', '员工所属部门', 'member_of', 'platform', 'Platform', 'ACTIVE', false, 1, 1);

INSERT INTO control.ontology_resource_versions
    (id, resource_id, version, lifecycle, display_name, description, maturity, promoted, owner_id, owner_name, definition, fingerprint, created_by, created_by_name, published_revision, published_at)
VALUES
    ('10000000-0000-0000-0000-000000000101', '00000000-0000-0000-0000-000000000101', 1, 'PUBLISHED', '部门', '组织部门', 'ACTIVE', true, 'platform', 'Platform', '{"sourceMode":"ACTION"}', repeat('1',64), 'platform', 'Platform', 1, now()),
    ('10000000-0000-0000-0000-000000000102', '00000000-0000-0000-0000-000000000102', 1, 'PUBLISHED', '员工', '员工主数据', 'ACTIVE', true, 'platform', 'Platform', '{"sourceMode":"PIPELINE"}', repeat('2',64), 'platform', 'Platform', 1, now()),
    ('10000000-0000-0000-0000-000000000201', '00000000-0000-0000-0000-000000000201', 1, 'PUBLISHED', '所属部门', '员工所属部门', 'ACTIVE', false, 'platform', 'Platform', '{}', repeat('3',64), 'platform', 'Platform', 1, now());

INSERT INTO control.object_type_versions(version_id, resource_id, source_mode, projection_status)
VALUES
    ('10000000-0000-0000-0000-000000000101', '00000000-0000-0000-0000-000000000101', 'ACTION', 'HEALTHY'),
    ('10000000-0000-0000-0000-000000000102', '00000000-0000-0000-0000-000000000102', 'PIPELINE', 'HEALTHY');

INSERT INTO control.properties(id, object_type_id, api_name, physical_key)
VALUES
    ('20000000-0000-0000-0000-000000000101', '00000000-0000-0000-0000-000000000101', 'name', 'p_department_name'),
    ('20000000-0000-0000-0000-000000000201', '00000000-0000-0000-0000-000000000102', 'employee_id', 'p_employee_id'),
    ('20000000-0000-0000-0000-000000000202', '00000000-0000-0000-0000-000000000102', 'name', 'p_employee_name'),
    ('20000000-0000-0000-0000-000000000203', '00000000-0000-0000-0000-000000000102', 'department', 'p_employee_department'),
    ('20000000-0000-0000-0000-000000000204', '00000000-0000-0000-0000-000000000102', 'email', 'p_employee_email');

INSERT INTO control.property_versions
    (id, property_id, object_type_version_id, display_name, value_type, required, primary_key, title_property, searchable, filterable, sortable, sensitive)
VALUES
    ('30000000-0000-0000-0000-000000000101', '20000000-0000-0000-0000-000000000101', '10000000-0000-0000-0000-000000000101', '名称', 'STRING', true, true, true, true, true, true, false),
    ('30000000-0000-0000-0000-000000000201', '20000000-0000-0000-0000-000000000201', '10000000-0000-0000-0000-000000000102', '员工编号', 'STRING', true, true, false, true, true, true, false),
    ('30000000-0000-0000-0000-000000000202', '20000000-0000-0000-0000-000000000202', '10000000-0000-0000-0000-000000000102', '姓名', 'STRING', true, false, true, true, true, true, false),
    ('30000000-0000-0000-0000-000000000203', '20000000-0000-0000-0000-000000000203', '10000000-0000-0000-0000-000000000102', '部门', 'STRING', false, false, false, true, true, false, false),
    ('30000000-0000-0000-0000-000000000204', '20000000-0000-0000-0000-000000000204', '10000000-0000-0000-0000-000000000102', '邮箱', 'STRING', false, false, false, false, false, false, true);

UPDATE control.object_type_versions SET primary_property_id='20000000-0000-0000-0000-000000000101', title_property_id='20000000-0000-0000-0000-000000000101' WHERE resource_id='00000000-0000-0000-0000-000000000101';
UPDATE control.object_type_versions SET primary_property_id='20000000-0000-0000-0000-000000000201', title_property_id='20000000-0000-0000-0000-000000000202' WHERE resource_id='00000000-0000-0000-0000-000000000102';

INSERT INTO control.link_type_versions
    (version_id, resource_id, left_object_type_id, right_object_type_id, cardinality, source_mode, left_display_name, right_display_name)
VALUES
    ('10000000-0000-0000-0000-000000000201', '00000000-0000-0000-0000-000000000201', '00000000-0000-0000-0000-000000000102', '00000000-0000-0000-0000-000000000101', 'N:1', 'FOREIGN_KEY', '所属部门', '包含员工');
