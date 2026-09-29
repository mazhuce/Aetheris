ALTER TABLE lakehouse_schema_change
    DROP CONSTRAINT IF EXISTS lakehouse_schema_change_status_check;

ALTER TABLE lakehouse_schema_change
    ADD CONSTRAINT lakehouse_schema_change_status_check
    CHECK (status IN ('proposed', 'approved', 'rejected', 'isolated', 'executing', 'applied', 'failed', 'cancelled'));

ALTER TABLE lakehouse_schema_change
    ADD COLUMN execution_request_key VARCHAR(160),
    ADD COLUMN remote_handle VARCHAR(64),
    ADD COLUMN execution_result_json JSONB,
    ADD COLUMN execution_error VARCHAR(2048);

CREATE UNIQUE INDEX uk_lakehouse_schema_change_execution_request
    ON lakehouse_schema_change (execution_request_key)
    WHERE execution_request_key IS NOT NULL;
