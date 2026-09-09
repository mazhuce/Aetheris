-- Pipeline workers run outside the request transaction. Leases ensure a process
-- crash cannot leave an active row holding the partial unique index forever.

ALTER TABLE pipeline_run
    ADD COLUMN heartbeat_at TIMESTAMPTZ,
    ADD COLUMN lease_expires_at TIMESTAMPTZ;

-- An active row present while this migration runs belongs to an older process
-- that cannot renew the new lease. Preserve uncertainty instead of guessing.
UPDATE pipeline_run
SET status = 'unknown',
    error_message = COALESCE(error_message, '升级时发现未完成流水线，需要核对后重跑'),
    finished_at = COALESCE(finished_at, now()),
    updated_at = now()
WHERE status IN ('queued', 'running');

UPDATE pipeline_node_run
SET status = 'unknown',
    finished_at = COALESCE(finished_at, now()),
    message = COALESCE(message, '升级时执行中断，节点结果待核对')
WHERE status = 'running';

UPDATE pipeline_node_run
SET status = 'skipped',
    finished_at = COALESCE(finished_at, now()),
    message = COALESCE(message, '流水线升级中断，节点未执行')
WHERE status = 'queued';

CREATE INDEX idx_pipeline_active_lease
    ON pipeline_run (lease_expires_at)
    WHERE status IN ('queued', 'running');
