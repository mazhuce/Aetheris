-- Managed Spark claims a queued instance before runner capacity is admitted.
-- V4 added queued attempts but omitted this matching logical-instance state.
-- Retain every existing state; invalid values and all other constraints stay enforced.
ALTER TABLE workflow.task_instance DROP CONSTRAINT chk_task_instance_status;
ALTER TABLE workflow.task_instance ADD CONSTRAINT chk_task_instance_status CHECK (
    status IN ('pending', 'queued', 'running', 'success', 'failed', 'skipped',
               'submitting', 'cancel_requested', 'cancelled', 'timed_out', 'unknown')
);
