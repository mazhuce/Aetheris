-- Existing rows remain READ-only; operators must provision an independent least-privilege writer explicitly.
ALTER TABLE metadata.data_source
    ADD COLUMN write_username VARCHAR(255) DEFAULT NULL,
    ADD COLUMN write_password TEXT DEFAULT NULL;

-- Keep internal complete-entity snapshots fenced across writer rotation/disable as well as READ changes.
-- Conservatively invalidate the READ health observation on any credential configuration change.
CREATE OR REPLACE FUNCTION metadata.invalidate_datasource_connection_health() RETURNS trigger AS $$
BEGIN
    IF ROW(NEW.type, NEW.host, NEW.port, NEW.database_name, NEW.username,
           NEW.password, NEW.jdbc_url, NEW.connection_params, NEW.write_username, NEW.write_password)
       IS DISTINCT FROM
       ROW(OLD.type, OLD.host, OLD.port, OLD.database_name, OLD.username,
           OLD.password, OLD.jdbc_url, OLD.connection_params, OLD.write_username, OLD.write_password) THEN
        NEW.connection_revision := OLD.connection_revision + 1;
        UPDATE metadata.datasource_connection_health
        SET configuration_revision = NEW.connection_revision,
            status = 'unknown', probe_token = NULL, started_at = NULL,
            checked_at = NULL, last_success_at = NULL, expires_at = NULL,
            latency_ms = NULL, failure_code = NULL, failure_message = NULL
        WHERE datasource_id = OLD.id;
    ELSE
        NEW.connection_revision := OLD.connection_revision;
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;
