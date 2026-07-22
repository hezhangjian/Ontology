ALTER TABLE control.dashboard_data_sources
    DROP CONSTRAINT dashboard_data_sources_source_kind_check;

ALTER TABLE control.dashboard_data_sources
    ADD CONSTRAINT dashboard_data_sources_source_kind_check
    CHECK (source_kind IN ('DATASET', 'OBJECT_SET', 'EXPLORATION', 'OBJECT_LIST', 'FUNCTION'));

ALTER TABLE control.dashboard_data_sources
    ADD COLUMN dataset_id UUID REFERENCES control.datasets(id);
