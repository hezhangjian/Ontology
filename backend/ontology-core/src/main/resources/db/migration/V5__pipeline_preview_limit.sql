ALTER TABLE control.pipeline_preview_runs
    ADD COLUMN row_limit INTEGER NOT NULL DEFAULT 100
    CHECK (row_limit BETWEEN 1 AND 100);

COMMENT ON COLUMN control.pipeline_preview_runs.row_limit IS 'Immutable bounded preview row limit requested by the builder.';
