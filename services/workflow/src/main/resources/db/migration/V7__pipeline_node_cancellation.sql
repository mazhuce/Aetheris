-- A remotely confirmed Spark cancellation is distinct from failure or skipping.
ALTER TABLE pipeline_node_run DROP CONSTRAINT chk_pipeline_node_status;
ALTER TABLE pipeline_node_run ADD CONSTRAINT chk_pipeline_node_status CHECK (
    status IN ('queued', 'running', 'succeeded', 'failed', 'skipped', 'waived', 'unknown', 'cancelled')
);
