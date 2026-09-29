ALTER TABLE task
    ADD COLUMN IF NOT EXISTS event_time_column VARCHAR(128),
    ADD COLUMN IF NOT EXISTS watermark_expression VARCHAR(512),
    ADD COLUMN IF NOT EXISTS allowed_lateness_seconds INTEGER NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS late_data_policy VARCHAR(16) NOT NULL DEFAULT 'drop',
    ADD COLUMN IF NOT EXISTS dead_letter_table VARCHAR(512);

ALTER TABLE task DROP CONSTRAINT IF EXISTS chk_flink_event_time_policy;
ALTER TABLE task ADD CONSTRAINT chk_flink_event_time_policy CHECK (
    (task_type = 'flink'
        AND allowed_lateness_seconds >= 0
        AND late_data_policy IN ('drop', 'dead_letter', 'side_output')
        AND (late_data_policy <> 'dead_letter' OR dead_letter_table IS NOT NULL))
    OR (task_type <> 'flink'
        AND event_time_column IS NULL
        AND watermark_expression IS NULL
        AND allowed_lateness_seconds = 0
        AND late_data_policy = 'drop'
        AND dead_letter_table IS NULL)
);

ALTER TABLE execution_attempt
    ADD COLUMN IF NOT EXISTS event_time_column VARCHAR(128),
    ADD COLUMN IF NOT EXISTS watermark_expression VARCHAR(512),
    ADD COLUMN IF NOT EXISTS allowed_lateness_seconds INTEGER,
    ADD COLUMN IF NOT EXISTS late_data_policy VARCHAR(16),
    ADD COLUMN IF NOT EXISTS dead_letter_table VARCHAR(512);
