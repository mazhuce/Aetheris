-- Quality parent approvals pin one approved child definition; historical rows acquire no authority.
ALTER TABLE workflow.task
  ADD COLUMN quality_execution_revision BIGINT CHECK (quality_execution_revision>0),
  ADD COLUMN quality_definition_sha256 CHAR(64) CHECK (quality_definition_sha256 ~ '^[a-f0-9]{64}$'),
  ADD CONSTRAINT quality_definition_reference CHECK ((quality_execution_revision IS NULL)=(quality_definition_sha256 IS NULL));
ALTER TABLE workflow.execution_attempt
  ADD COLUMN quality_result_id BIGINT CHECK (quality_result_id>0),
  ADD COLUMN quality_manifest_sha256 CHAR(64) CHECK (quality_manifest_sha256 ~ '^[a-f0-9]{64}$'),
  ADD COLUMN quality_evidence_json JSONB,
  ADD COLUMN quality_evidence_sha256 CHAR(64),
  ADD CONSTRAINT quality_result_reference CHECK ((quality_result_id IS NULL)=(quality_manifest_sha256 IS NULL)),
  ADD CONSTRAINT quality_result_evidence CHECK ((quality_evidence_json IS NULL AND quality_evidence_sha256 IS NULL)
    OR (quality_evidence_json IS NOT NULL AND quality_evidence_sha256=encode(sha256(convert_to(quality_evidence_json::text,'UTF8')),'hex')) IS TRUE);

CREATE OR REPLACE FUNCTION workflow.execution_definition_projection(body JSONB) RETURNS JSONB
LANGUAGE SQL IMMUTABLE PARALLEL SAFE AS $$ SELECT jsonb_build_object('schemaVersion',1,'taskType',body->'task_type','datasourceId',body->'datasource_id','sql',body->'task_sql','timeoutSeconds',body->'timeout_seconds','maxRetries',body->'max_retries','targetTable',body->'target_table','jobDefinitionId',body->'job_definition_id','qualityRuleId',body->'quality_rule_id','flinkEndpoint',body->'flink_endpoint','flinkMode',body->'flink_mode','eventTimeColumn',body->'event_time_column','watermarkExpression',body->'watermark_expression','allowedLatenessSeconds',body->'allowed_lateness_seconds','lateDataPolicy',body->'late_data_policy','deadLetterTable',body->'dead_letter_table','dependencies',body->'execution_dependencies') || CASE WHEN body->>'task_type'='quality' THEN jsonb_build_object('qualityExecutionRevision',body->'quality_execution_revision','qualityDefinitionSha256',body->'quality_definition_sha256') ELSE '{}'::jsonb END $$;

CREATE OR REPLACE FUNCTION workflow.guard_execution_definition() RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE definition JSONB;
BEGIN
    definition := workflow.execution_definition_projection(to_jsonb(NEW));
    IF TG_OP='INSERT' THEN
        NEW.execution_revision := 1;
        NEW.execution_approval := NULL; NEW.execution_approved_at := NULL;
        NEW.cron_actor := NULL; NEW.cron_approved_execution_revision := NULL;
        NEW.cron_binding_generation := 0;
    ELSE
        IF definition IS DISTINCT FROM OLD.execution_definition_json THEN
            NEW.execution_revision := OLD.execution_revision+1;
            NEW.execution_approval := NULL; NEW.execution_approved_at := NULL;
            NEW.cron_actor := NULL; NEW.cron_approved_execution_revision := NULL;
        ELSE NEW.execution_revision := OLD.execution_revision;
        END IF;
        IF ROW(NEW.cron_expression,NEW.status) IS DISTINCT FROM ROW(OLD.cron_expression,OLD.status) THEN
            NEW.cron_actor := NULL; NEW.cron_approved_execution_revision := NULL;
        END IF;
        IF NEW.execution_approval IS NULL THEN
            NEW.cron_actor := NULL; NEW.cron_approved_execution_revision := NULL;
        END IF;
        IF ROW(NEW.cron_actor,NEW.cron_approved_execution_revision)
          IS DISTINCT FROM ROW(OLD.cron_actor,OLD.cron_approved_execution_revision) THEN
            NEW.cron_binding_generation := OLD.cron_binding_generation+1;
        ELSE NEW.cron_binding_generation := OLD.cron_binding_generation;
        END IF;
    END IF;
    NEW.execution_definition_json := definition;
    NEW.execution_definition_sha256 := encode(sha256(convert_to(definition::text,'UTF8')),'hex');
    RETURN NEW;
END $$;

UPDATE workflow.task SET execution_revision=execution_revision WHERE task_type='quality';

CREATE OR REPLACE FUNCTION workflow.enqueue_schedule_generation() RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE task_key BIGINT; generation BIGINT; desired TEXT;
BEGIN
  IF TG_OP='DELETE' THEN task_key:=OLD.id; generation:=OLD.schedule_version+1; desired:='unschedule';
  ELSE
    IF TG_OP='UPDATE' AND NEW.schedule_version=OLD.schedule_version THEN RETURN NEW; END IF;
    task_key:=NEW.id; generation:=NEW.schedule_version;
    desired:=CASE WHEN NEW.task_type IN ('sql','quality') AND NEW.status='enabled' AND length(btrim(NEW.cron_expression))>0
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

CREATE FUNCTION workflow.guard_quality_result_binding() RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE field TEXT;
BEGIN
  IF TG_OP='UPDATE' THEN
    FOREACH field IN ARRAY ARRAY['quality_result_id','quality_manifest_sha256','quality_evidence_json','quality_evidence_sha256'] LOOP
      IF to_jsonb(OLD)->field IS DISTINCT FROM 'null'::jsonb AND to_jsonb(NEW)->field IS DISTINCT FROM to_jsonb(OLD)->field THEN
        RAISE EXCEPTION 'child result binding is immutable' USING ERRCODE='55000';
      END IF;
    END LOOP;
  END IF;
  IF NEW.quality_result_id IS NOT NULL AND (NEW.task_type<>'quality' OR NEW.manifest_sealed_at IS NULL) THEN
    RAISE EXCEPTION 'child result requires sealed quality parent' USING ERRCODE='55000';
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER fixed_quality_result_binding BEFORE INSERT OR UPDATE ON workflow.execution_attempt
FOR EACH ROW EXECUTE FUNCTION workflow.guard_quality_result_binding();

CREATE OR REPLACE FUNCTION workflow.guard_sql_invocation_kind() RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE current_generation BIGINT; current_binding BIGINT;
BEGIN
  IF TG_OP='UPDATE' AND OLD.manifest_sealed_at IS NOT NULL AND
    ROW(NEW.source_schedule_generation,NEW.source_binding_generation,NEW.pipeline_run_id,NEW.pipeline_node_id,NEW.pipeline_fence)
      IS DISTINCT FROM ROW(OLD.source_schedule_generation,OLD.source_binding_generation,OLD.pipeline_run_id,OLD.pipeline_node_id,OLD.pipeline_fence) THEN
    RAISE EXCEPTION 'sealed execution source binding is immutable' USING ERRCODE='55000';
  END IF;
  IF NEW.manifest_sealed_at IS NOT NULL AND (TG_OP='INSERT' OR OLD.manifest_sealed_at IS NULL) THEN
    IF NEW.invocation_json->>'kind'='pipeline' THEN
      IF NOT workflow.pipeline_binding_valid(NEW) THEN RAISE EXCEPTION 'pipeline source binding is required' USING ERRCODE='55000'; END IF;
    ELSIF NEW.pipeline_run_id IS NOT NULL OR NEW.pipeline_node_id IS NOT NULL OR NEW.pipeline_fence IS NOT NULL THEN
      RAISE EXCEPTION 'non-pipeline invocation cannot use pipeline references' USING ERRCODE='55000';
    END IF;
    IF NEW.task_type IN ('sql','quality') THEN
      IF (NEW.invocation_json->>'kind' IN ('manual','cron','pipeline') AND NEW.sealed_manifest->'parent'='null'::jsonb) IS NOT TRUE THEN
        RAISE EXCEPTION 'SQL source requires a trusted fixed invocation' USING ERRCODE='55000';
      END IF;
      IF NEW.invocation_json->>'kind' IN ('manual','pipeline') THEN
        IF NEW.source_schedule_generation IS NOT NULL OR NEW.source_binding_generation IS NOT NULL THEN
          RAISE EXCEPTION 'non-cron SQL has no cron generation' USING ERRCODE='55000';
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
  END IF;
  RETURN NEW;
END $$;

CREATE OR REPLACE FUNCTION workflow.pipeline_success_evidence(node_key BIGINT) RETURNS BOOLEAN LANGUAGE SQL STABLE AS $$
  SELECT EXISTS(SELECT 1 FROM workflow.pipeline_node_run node
    JOIN workflow.task_instance instance ON instance.id=node.instance_id
    JOIN workflow.execution_attempt attempt ON attempt.id=node.attempt_id AND attempt.instance_id=instance.id
    JOIN workflow.pipeline_run run ON run.id=node.pipeline_run_id
    WHERE node.id=node_key AND node.status IN ('succeeded','waived') AND node.execution_revision IS NOT NULL
      AND instance.active_attempt_id=attempt.id AND instance.fencing_token=node.fencing_token
      AND attempt.fencing_token=node.fencing_token AND (
        (node.status='succeeded' AND instance.status='success' AND attempt.status='succeeded')
        OR (node.status='waived' AND instance.status='failed' AND attempt.status='failed' AND attempt.task_type='quality'
          AND attempt.error_code='QUALITY_GATE_FAILED' AND attempt.quality_result_id IS NOT NULL
          AND attempt.quality_manifest_sha256 IS NOT NULL AND attempt.quality_evidence_json->>'state'='failed'
          AND (attempt.quality_evidence_json->>'violationCount')::bigint>(attempt.quality_evidence_json->>'thresholdValue')::bigint
          AND EXISTS(SELECT 1 FROM workflow.quality_waiver waiver WHERE waiver.id=node.waiver_id
            AND waiver.quality_task_id=node.task_id AND waiver.biz_date=instance.biz_date AND waiver.expires_at>clock_timestamp())))
      AND attempt.finished_at IS NOT NULL AND instance.finished_at IS NOT NULL
      AND attempt.pipeline_node_id=node.id AND attempt.pipeline_run_id=run.id
      AND attempt.invocation_json=run.invocation_json AND attempt.execution_revision=node.execution_revision
      AND attempt.approval_json=node.approval_json AND attempt.manifest_sealed_at IS NOT NULL
      AND attempt.metadata_receipt_id IS NOT NULL)
$$;

