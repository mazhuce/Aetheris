-- Stored active/inactive is configuration, never a connection-health observation.
ALTER TABLE metadata.data_source ADD COLUMN connection_revision BIGINT NOT NULL DEFAULT 0;

CREATE TABLE metadata.datasource_connection_health (
    datasource_id BIGINT PRIMARY KEY REFERENCES metadata.data_source(id) ON DELETE CASCADE,
    configuration_revision BIGINT NOT NULL,
    status VARCHAR(16) NOT NULL CHECK (status IN ('unknown', 'checking', 'available', 'unavailable')),
    probe_token VARCHAR(36),
    started_at TIMESTAMPTZ,
    checked_at TIMESTAMPTZ,
    last_success_at TIMESTAMPTZ,
    expires_at TIMESTAMPTZ,
    latency_ms BIGINT,
    failure_code VARCHAR(48),
    failure_message VARCHAR(255)
);

-- Invalidate atomically with configuration writes, including managed datasource updates.
-- The revision and probe token prevent delayed results from reviving an old connection.
CREATE FUNCTION metadata.invalidate_datasource_connection_health() RETURNS trigger AS $$
BEGIN
    IF ROW(NEW.type, NEW.host, NEW.port, NEW.database_name, NEW.username,
           NEW.password, NEW.jdbc_url, NEW.connection_params)
       IS DISTINCT FROM
       ROW(OLD.type, OLD.host, OLD.port, OLD.database_name, OLD.username,
           OLD.password, OLD.jdbc_url, OLD.connection_params) THEN
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

CREATE TRIGGER data_source_connection_changed BEFORE UPDATE ON metadata.data_source
FOR EACH ROW EXECUTE FUNCTION metadata.invalidate_datasource_connection_health();
