-- No historical username is promoted to an invocation or approval.
ALTER TABLE workflow.task ADD COLUMN schedule_version BIGINT NOT NULL DEFAULT 1 CHECK (schedule_version>0);
ALTER TABLE workflow.scheduler_sync_outbox ADD COLUMN target_generation BIGINT NOT NULL DEFAULT 1 CHECK (target_generation>0);
ALTER TABLE workflow.execution_attempt
  ADD COLUMN source_schedule_generation BIGINT CHECK (source_schedule_generation>0),
  ADD COLUMN source_binding_generation BIGINT CHECK (source_binding_generation>=0);

CREATE FUNCTION workflow.assign_schedule_generation() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
  IF TG_OP='INSERT' THEN NEW.schedule_version:=1;
  ELSIF ROW(NEW.cron_expression,NEW.status,NEW.execution_revision,NEW.cron_binding_generation)
       IS DISTINCT FROM ROW(OLD.cron_expression,OLD.status,OLD.execution_revision,OLD.cron_binding_generation)
  THEN NEW.schedule_version:=OLD.schedule_version+1;
  ELSE NEW.schedule_version:=OLD.schedule_version;
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER b00_assign_schedule_generation BEFORE INSERT OR UPDATE ON workflow.task
FOR EACH ROW EXECUTE FUNCTION workflow.assign_schedule_generation();

CREATE FUNCTION workflow.enqueue_schedule_generation() RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE task_key BIGINT; generation BIGINT; desired TEXT;
BEGIN
  IF TG_OP='DELETE' THEN task_key:=OLD.id; generation:=OLD.schedule_version+1; desired:='unschedule';
  ELSE
    IF TG_OP='UPDATE' AND NEW.schedule_version=OLD.schedule_version THEN RETURN NEW; END IF;
    task_key:=NEW.id; generation:=NEW.schedule_version;
    desired:=CASE WHEN NEW.task_type='sql' AND NEW.status='enabled' AND length(btrim(NEW.cron_expression))>0
       AND NEW.execution_approval IS NOT NULL AND NEW.cron_actor IS NOT NULL
       AND NEW.cron_approved_execution_revision=NEW.execution_revision THEN 'sync' ELSE 'unschedule' END;
  END IF;
  -- Retire old desired work; a callback can only acknowledge its own generation.
  UPDATE workflow.scheduler_sync_outbox SET status='succeeded',last_error='SUPERSEDED_GENERATION',updated_at=clock_timestamp()
    WHERE task_id=task_key AND status IN ('pending','processing') AND target_generation<generation;
  INSERT INTO workflow.scheduler_sync_outbox(task_id,operation,target_generation,status,next_attempt_at)
    VALUES(task_key,desired,generation,'pending',clock_timestamp())
    ON CONFLICT(task_id,operation) WHERE status IN ('pending','processing')
    DO UPDATE SET target_generation=EXCLUDED.target_generation,status='pending',attempts=0,
      next_attempt_at=clock_timestamp(),last_error=NULL,updated_at=clock_timestamp();
  IF TG_OP='DELETE' THEN RETURN OLD; ELSE RETURN NEW; END IF;
END $$;
CREATE TRIGGER fixed_schedule_outbox AFTER INSERT OR UPDATE OR DELETE ON workflow.task
FOR EACH ROW EXECUTE FUNCTION workflow.enqueue_schedule_generation();

-- The actual edges, including deletion, are part of the approved definition.
CREATE FUNCTION workflow.refresh_execution_dependencies() RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE task_key BIGINT;
BEGIN
  FOR task_key IN SELECT DISTINCT v FROM (VALUES(CASE WHEN TG_OP<>'INSERT' THEN OLD.task_id END),
      (CASE WHEN TG_OP<>'DELETE' THEN NEW.task_id END)) AS ids(v) WHERE v IS NOT NULL ORDER BY v LOOP
    UPDATE workflow.task task SET execution_dependencies=jsonb_build_object('upstreamTaskIds',
      COALESCE((SELECT jsonb_agg(edge.upstream_task_id ORDER BY edge.upstream_task_id)
        FROM workflow.task_dependency edge WHERE edge.task_id=task_key),'[]'::jsonb)) WHERE task.id=task_key;
  END LOOP;
  IF TG_OP='DELETE' THEN RETURN OLD; ELSE RETURN NEW; END IF;
END $$;
CREATE TRIGGER fixed_dependency_projection AFTER INSERT OR UPDATE OR DELETE ON workflow.task_dependency
FOR EACH ROW EXECUTE FUNCTION workflow.refresh_execution_dependencies();
UPDATE workflow.task task SET execution_dependencies=jsonb_build_object('upstreamTaskIds',
  COALESCE((SELECT jsonb_agg(edge.upstream_task_id ORDER BY edge.upstream_task_id)
    FROM workflow.task_dependency edge WHERE edge.task_id=task.id),'[]'::jsonb));

-- SQL admissions are deliberately narrower than the generic storage envelope in V28.
CREATE FUNCTION workflow.guard_sql_invocation_kind() RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE current_generation BIGINT; current_binding BIGINT;
BEGIN
  IF TG_OP='UPDATE' AND OLD.manifest_sealed_at IS NOT NULL AND
     ROW(NEW.source_schedule_generation,NEW.source_binding_generation)
       IS DISTINCT FROM ROW(OLD.source_schedule_generation,OLD.source_binding_generation) THEN
    RAISE EXCEPTION 'sealed SQL generation is immutable' USING ERRCODE='55000';
  END IF;
  IF NEW.manifest_sealed_at IS NOT NULL AND NEW.task_type='sql'
     AND (TG_OP='INSERT' OR OLD.manifest_sealed_at IS NULL) THEN
    IF (NEW.invocation_json->>'kind' IN ('manual','cron') AND NEW.sealed_manifest->'parent'='null'::jsonb) IS NOT TRUE THEN
      RAISE EXCEPTION 'SQL source requires manual or bound cron invocation' USING ERRCODE='55000';
    END IF;
    IF NEW.invocation_json->>'kind'='manual' THEN
      IF NEW.source_schedule_generation IS NOT NULL OR NEW.source_binding_generation IS NOT NULL THEN
        RAISE EXCEPTION 'manual SQL has no cron generation' USING ERRCODE='55000';
      END IF;
    ELSE
      SELECT task.schedule_version,task.cron_binding_generation INTO current_generation,current_binding
        FROM workflow.task task JOIN workflow.task_instance instance ON instance.task_id=task.id
        WHERE instance.id=NEW.instance_id FOR SHARE OF task;
      IF NEW.source_schedule_generation IS DISTINCT FROM current_generation OR NEW.source_binding_generation IS DISTINCT FROM current_binding
         OR NEW.source_schedule_generation IS NULL OR NEW.source_binding_generation IS NULL THEN
        RAISE EXCEPTION 'cron SQL generation does not match its source' USING ERRCODE='55000';
      END IF;
    END IF;
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER fixed_sql_invocation_kind BEFORE INSERT OR UPDATE ON workflow.execution_attempt
FOR EACH ROW EXECUTE FUNCTION workflow.guard_sql_invocation_kind();
