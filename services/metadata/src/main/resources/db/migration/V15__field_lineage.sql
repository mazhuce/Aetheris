CREATE TABLE lineage_field_relation (
    id BIGSERIAL PRIMARY KEY,
    source_table VARCHAR(256) NOT NULL,
    source_column VARCHAR(256) NOT NULL,
    target_table VARCHAR(256) NOT NULL,
    target_column VARCHAR(256) NOT NULL,
    relation_type VARCHAR(32) NOT NULL DEFAULT 'etl',
    expression TEXT,
    source VARCHAR(16) NOT NULL DEFAULT 'manual',
    confidence NUMERIC(5,4) NOT NULL DEFAULT 1.0000,
    description VARCHAR(1000),
    created_by VARCHAR(64),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT ck_lineage_field_source CHECK (source IN ('manual','parsed')),
    CONSTRAINT ck_lineage_field_confidence CHECK (confidence >= 0 AND confidence <= 1)
);
CREATE UNIQUE INDEX uq_lineage_field_edge ON lineage_field_relation(source_table, source_column, target_table, target_column, relation_type, source);
CREATE INDEX idx_lineage_field_source ON lineage_field_relation(source_table, source_column);
CREATE INDEX idx_lineage_field_target ON lineage_field_relation(target_table, target_column);
