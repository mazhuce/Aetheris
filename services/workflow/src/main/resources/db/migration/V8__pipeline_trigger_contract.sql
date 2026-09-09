-- Mixed pipelines are a first-class trigger source. Manual and scheduled task
-- execution must not inherit pipeline-only quality waiver semantics.
ALTER TABLE task_instance DROP CONSTRAINT chk_task_instance_trigger;
ALTER TABLE task_instance ADD CONSTRAINT chk_task_instance_trigger CHECK (
    trigger_type IN ('manual', 'schedule', 'retry', 'pipeline')
);
