ALTER TABLE lineage_execution_evidence DROP CONSTRAINT IF EXISTS chk_lineage_evidence_type;
ALTER TABLE lineage_execution_evidence ADD CONSTRAINT chk_lineage_evidence_type
    CHECK (task_type IN ('sql','spark','flink','quality'));
