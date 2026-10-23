CREATE SEQUENCE ingestion.schedule_generation_seq;
ALTER TABLE ingestion.ingestion_task ADD COLUMN schedule_version BIGINT NOT NULL DEFAULT 0;
UPDATE ingestion.ingestion_task SET schedule_version=nextval('ingestion.schedule_generation_seq');
CREATE TABLE ingestion.scheduler_sync_outbox (
    resource_id BIGINT PRIMARY KEY, generation BIGINT NOT NULL,
    deleted BOOLEAN NOT NULL, name TEXT, cron TEXT, enabled BOOLEAN NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'pending' CHECK (status IN ('pending','applied')),
    attempts INTEGER NOT NULL DEFAULT 0, next_attempt_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_error VARCHAR(1024), updated_at TIMESTAMPTZ NOT NULL DEFAULT now(), applied_at TIMESTAMPTZ
);
CREATE INDEX scheduler_sync_pending_idx ON ingestion.scheduler_sync_outbox(next_attempt_at) WHERE status='pending';
INSERT INTO ingestion.scheduler_sync_outbox(resource_id,generation,deleted,name,cron,enabled)
SELECT id,schedule_version,false,name,schedule_cron,status='enabled' FROM ingestion.ingestion_task;

CREATE FUNCTION ingestion.assign_schedule_generation() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP='INSERT' THEN
        NEW.schedule_version := nextval('ingestion.schedule_generation_seq');
    ELSIF ROW(NEW.name,NEW.schedule_cron,NEW.status) IS DISTINCT FROM ROW(OLD.name,OLD.schedule_cron,OLD.status) THEN
        NEW.schedule_version := nextval('ingestion.schedule_generation_seq');
    ELSE
        NEW.schedule_version := OLD.schedule_version;
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER assign_schedule_generation BEFORE INSERT OR UPDATE ON ingestion.ingestion_task
FOR EACH ROW EXECUTE FUNCTION ingestion.assign_schedule_generation();

CREATE FUNCTION ingestion.enqueue_schedule_desired_state() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE item_id BIGINT; item_generation BIGINT; is_deleted BOOLEAN;
BEGIN
    IF TG_OP='DELETE' THEN
        item_id := OLD.id; item_generation := nextval('ingestion.schedule_generation_seq'); is_deleted := true;
    ELSE
        IF TG_OP='UPDATE' AND NEW.schedule_version=OLD.schedule_version THEN RETURN NEW; END IF;
        item_id := NEW.id; item_generation := NEW.schedule_version; is_deleted := false;
    END IF;
    INSERT INTO ingestion.scheduler_sync_outbox(resource_id,generation,deleted,name,cron,enabled)
    VALUES(item_id,item_generation,is_deleted,
           CASE WHEN is_deleted THEN NULL ELSE NEW.name END,
           CASE WHEN is_deleted THEN NULL ELSE NEW.schedule_cron END,
           CASE WHEN is_deleted THEN false ELSE NEW.status='enabled' END)
    ON CONFLICT(resource_id) DO UPDATE SET generation=EXCLUDED.generation,deleted=EXCLUDED.deleted,
        name=EXCLUDED.name,cron=EXCLUDED.cron,enabled=EXCLUDED.enabled,status='pending',
        attempts=0,next_attempt_at=now(),last_error=NULL,updated_at=now(),applied_at=NULL
    WHERE scheduler_sync_outbox.generation < EXCLUDED.generation;
    IF TG_OP='DELETE' THEN RETURN OLD; END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER enqueue_schedule_desired_state AFTER INSERT OR UPDATE OR DELETE ON ingestion.ingestion_task
FOR EACH ROW EXECUTE FUNCTION ingestion.enqueue_schedule_desired_state();