ALTER TABLE control.pipeline_preview_runs
    ADD COLUMN graph JSONB,
    ADD COLUMN runtime JSONB,
    ADD COLUMN row_count INTEGER NOT NULL DEFAULT 0,
    ADD COLUMN size_bytes BIGINT NOT NULL DEFAULT 0;

CREATE INDEX pipeline_preview_active_idx
    ON control.pipeline_preview_runs(status, expires_at)
    WHERE status IN ('SUBMITTED', 'RUNNING');

COMMENT ON COLUMN control.pipeline_preview_runs.graph IS 'Immutable draft graph snapshot compiled by the bounded Flink preview job.';
