CREATE TABLE control.datasets (
    id UUID PRIMARY KEY,
    name VARCHAR(240) NOT NULL,
    normalized_name VARCHAR(240) NOT NULL UNIQUE,
    description TEXT NOT NULL DEFAULT '',
    pipeline_id UUID NOT NULL REFERENCES control.pipelines(id),
    schema JSONB NOT NULL DEFAULT '[]',
    row_count BIGINT NOT NULL DEFAULT 0,
    status VARCHAR(24) NOT NULL CHECK (status IN ('BUILDING', 'READY', 'FAILED')),
    object_key VARCHAR(1000),
    owner_id VARCHAR(240) NOT NULL,
    owner_name VARCHAR(240) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX datasets_pipeline_idx ON control.datasets(pipeline_id, updated_at DESC);

CREATE TABLE control.dataset_rows (
    dataset_id UUID NOT NULL REFERENCES control.datasets(id) ON DELETE CASCADE,
    row_number BIGINT NOT NULL,
    body JSONB NOT NULL,
    PRIMARY KEY (dataset_id, row_number)
);

CREATE INDEX dataset_rows_body_gin_idx ON control.dataset_rows USING gin(body);
