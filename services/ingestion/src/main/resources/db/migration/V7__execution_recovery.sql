ALTER TABLE ingestion.ingestion_run
    ADD COLUMN lease_owner VARCHAR(64),
    ADD COLUMN lease_expires_at TIMESTAMPTZ NOT NULL DEFAULT (clock_timestamp() + INTERVAL '120 seconds'),
    ADD COLUMN execution_snapshot TEXT NOT NULL DEFAULT '{}';

-- Old running rows are not evidence of failure. Keep the concurrency slot until reconciliation.
UPDATE ingestion.ingestion_run SET status='unknown', failure_code='UPGRADE_RECONCILIATION_REQUIRED',
    error_message='升级前运行结果待核实；禁止自动重放', finished_at=clock_timestamp()
    WHERE status='running';
DROP INDEX ingestion.uq_ingestion_run_active_task;
CREATE UNIQUE INDEX uq_ingestion_run_active_task ON ingestion.ingestion_run(task_id)
    WHERE status IN ('running','unknown');
CREATE INDEX ingestion_run_lease_expiry ON ingestion.ingestion_run(lease_expires_at) WHERE status='running';

CREATE TABLE ingestion.execution_reconciliation_audit (
    id BIGSERIAL PRIMARY KEY,
    run_id BIGINT NOT NULL, task_id BIGINT NOT NULL,
    previous_status VARCHAR(16) NOT NULL, resolved_status VARCHAR(16) NOT NULL,
    actor VARCHAR(128) NOT NULL, evidence TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp()
);
INSERT INTO ingestion.execution_reconciliation_audit(run_id,task_id,previous_status,resolved_status,actor,evidence)
SELECT id,task_id,'running','unknown','system:migration','升级前运行结果待核实；禁止自动重放'
FROM ingestion.ingestion_run WHERE failure_code='UPGRADE_RECONCILIATION_REQUIRED';
UPDATE ingestion.ingestion_task task SET last_run_status='unknown',last_run_rows=NULL,
    last_error='升级前运行结果待核实；禁止自动重放'
FROM ingestion.ingestion_run run WHERE run.task_id=task.id AND run.failure_code='UPGRADE_RECONCILIATION_REQUIRED';

CREATE FUNCTION ingestion.block_unresolved_task_delete() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    IF EXISTS (SELECT 1 FROM ingestion.ingestion_run WHERE task_id=OLD.id AND status IN ('running','unknown')) THEN
        RAISE EXCEPTION '仍有执行中或待对账运行，禁止删除接入任务' USING ERRCODE='55000';
    END IF;
    RETURN OLD;
END $$;
CREATE TRIGGER block_unresolved_task_delete BEFORE DELETE ON ingestion.ingestion_task
    FOR EACH ROW EXECUTE FUNCTION ingestion.block_unresolved_task_delete();
