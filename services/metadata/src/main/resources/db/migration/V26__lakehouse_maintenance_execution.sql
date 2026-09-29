ALTER TABLE lakehouse_maintenance_job
    ADD COLUMN executor_request_key VARCHAR(160),
    ADD COLUMN remote_handle VARCHAR(64);

CREATE UNIQUE INDEX uk_lakehouse_maintenance_executor_request
    ON lakehouse_maintenance_job (executor_request_key)
    WHERE executor_request_key IS NOT NULL;
