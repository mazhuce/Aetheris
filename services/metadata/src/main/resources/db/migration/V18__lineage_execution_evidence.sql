CREATE TABLE lineage_execution_evidence (
    id                 BIGSERIAL PRIMARY KEY,
    execution_key      VARCHAR(96)  NOT NULL UNIQUE,
    task_id            BIGINT       NOT NULL,
    attempt_id         BIGINT       NOT NULL,
    biz_date           VARCHAR(10)  NOT NULL,
    task_type          VARCHAR(16)  NOT NULL,
    definition_digest  VARCHAR(64),
    source_tables      JSONB        NOT NULL DEFAULT '[]'::jsonb,
    target_table       VARCHAR(256),
    output_uri         VARCHAR(512),
    parse_status       VARCHAR(24)  NOT NULL,
    recorded_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT chk_lineage_evidence_type CHECK (task_type IN ('sql','spark','quality')),
    CONSTRAINT chk_lineage_parse_status CHECK (parse_status IN ('parsed','declared','unknown'))
);
CREATE INDEX idx_lineage_evidence_task_date
    ON lineage_execution_evidence(task_id,biz_date,attempt_id DESC);
