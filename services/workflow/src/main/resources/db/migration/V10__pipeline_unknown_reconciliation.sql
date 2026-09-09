-- Keep the periodic unknown-result reconciler proportional to unresolved work,
-- not to the full pipeline history.

CREATE INDEX idx_pipeline_unknown_runs
    ON pipeline_run(id)
    WHERE status = 'unknown';

CREATE INDEX idx_pipeline_unknown_nodes
    ON pipeline_node_run(pipeline_run_id, id)
    WHERE status = 'unknown';
