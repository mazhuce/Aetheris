-- Engine execution materials are approved explicitly. No old user/approval is backfilled.
ALTER TABLE workflow.task
  ADD COLUMN engine_definition_json JSONB,
  ADD COLUMN engine_definition_sha256 CHAR(64),
  ADD CONSTRAINT engine_material_hash CHECK (
    (engine_definition_json IS NULL AND engine_definition_sha256 IS NULL) OR
    (jsonb_typeof(engine_definition_json)='object' AND engine_definition_json->>'schemaVersion'='1'
      AND engine_definition_json->>'engine' IN ('spark','flink')
      AND engine_definition_sha256=encode(sha256(convert_to(engine_definition_json::text,'UTF8')),'hex')) IS TRUE);
ALTER TABLE workflow.execution_attempt
  ADD COLUMN engine_dispatch_token UUID UNIQUE,
  ADD COLUMN engine_dispatch_started_at TIMESTAMPTZ,
  ADD COLUMN engine_runner_origin TEXT,
  ADD COLUMN engine_receipt_json JSONB,
  ADD COLUMN engine_receipt_sha256 CHAR(64),
  ADD COLUMN engine_result_json JSONB,
  ADD COLUMN engine_result_sha256 CHAR(64),
  ADD COLUMN engine_steps_json JSONB NOT NULL DEFAULT '[]'::jsonb CHECK (jsonb_typeof(engine_steps_json)='array'),
  ADD CONSTRAINT engine_dispatch_marker CHECK (
    (engine_dispatch_token IS NULL AND engine_dispatch_started_at IS NULL AND engine_runner_origin IS NULL)
    OR (engine_dispatch_token IS NOT NULL AND engine_dispatch_started_at IS NOT NULL AND engine_runner_origin ~ '^https?://')),
  ADD CONSTRAINT engine_receipt_hash CHECK (
    (engine_receipt_json IS NULL AND engine_receipt_sha256 IS NULL) OR
    (engine_dispatch_token IS NOT NULL AND jsonb_typeof(engine_receipt_json)='object'
      AND engine_receipt_sha256=encode(sha256(convert_to(engine_receipt_json::text,'UTF8')),'hex')) IS TRUE),
  ADD CONSTRAINT engine_result_hash CHECK (
    (engine_result_json IS NULL AND engine_result_sha256 IS NULL) OR
    (jsonb_typeof(engine_result_json)='object'
      AND engine_result_sha256=encode(sha256(convert_to(engine_result_json::text,'UTF8')),'hex')) IS TRUE);

CREATE OR REPLACE FUNCTION workflow.execution_definition_projection(body JSONB) RETURNS JSONB
LANGUAGE SQL IMMUTABLE PARALLEL SAFE AS $$ SELECT jsonb_build_object('schemaVersion',1,'taskType',body->'task_type','datasourceId',body->'datasource_id','sql',body->'task_sql','timeoutSeconds',body->'timeout_seconds','maxRetries',body->'max_retries','targetTable',body->'target_table','jobDefinitionId',body->'job_definition_id','qualityRuleId',body->'quality_rule_id','flinkEndpoint',body->'flink_endpoint','flinkMode',body->'flink_mode','eventTimeColumn',body->'event_time_column','watermarkExpression',body->'watermark_expression','allowedLatenessSeconds',body->'allowed_lateness_seconds','lateDataPolicy',body->'late_data_policy','deadLetterTable',body->'dead_letter_table','dependencies',body->'execution_dependencies') || CASE WHEN body->>'task_type'='quality' THEN jsonb_build_object('qualityExecutionRevision',body->'quality_execution_revision','qualityDefinitionSha256',body->'quality_definition_sha256') WHEN body->>'task_type' IN ('spark','flink') THEN jsonb_build_object('engineDefinition',body->'engine_definition_json') ELSE '{}'::jsonb END $$;

CREATE OR REPLACE FUNCTION workflow.guard_execution_definition() RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE definition JSONB;
BEGIN
    NEW.engine_definition_sha256 := CASE WHEN NEW.engine_definition_json IS NULL THEN NULL ELSE encode(sha256(convert_to(NEW.engine_definition_json::text,'UTF8')),'hex') END;
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

UPDATE workflow.task SET execution_revision=execution_revision WHERE task_type IN ('spark','flink');

CREATE OR REPLACE FUNCTION workflow.enqueue_schedule_generation() RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE task_key BIGINT; generation BIGINT; desired TEXT;
BEGIN
  IF TG_OP='DELETE' THEN task_key:=OLD.id; generation:=OLD.schedule_version+1; desired:='unschedule';
  ELSE
    IF TG_OP='UPDATE' AND NEW.schedule_version=OLD.schedule_version THEN RETURN NEW; END IF;
    task_key:=NEW.id; generation:=NEW.schedule_version;
    desired:=CASE WHEN NEW.task_type IN ('sql','quality','spark','flink') AND NEW.status='enabled' AND length(btrim(NEW.cron_expression))>0
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
    IF NEW.task_type IN ('sql','quality','spark','flink') THEN
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

CREATE FUNCTION workflow.guard_engine_execution() RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE material JSONB; receipt JSONB; result JSONB; engine_hash TEXT; current_source RECORD; step_index INT;
BEGIN
  IF NEW.manifest_sealed_at IS NULL OR NEW.task_type NOT IN ('spark','flink') THEN
    IF NEW.engine_dispatch_token IS NOT NULL OR NEW.engine_receipt_json IS NOT NULL OR NEW.engine_result_json IS NOT NULL OR NEW.engine_steps_json<>'[]'::jsonb THEN
      RAISE EXCEPTION 'engine dispatch requires a sealed source' USING ERRCODE='55000';
    END IF;
    RETURN NEW;
  END IF;
  IF TG_OP='UPDATE' AND NEW.engine_steps_json IS DISTINCT FROM OLD.engine_steps_json THEN
    IF NEW.engine_dispatch_token IS NULL OR jsonb_array_length(NEW.engine_steps_json)<>jsonb_array_length(OLD.engine_steps_json)+1 THEN
      RAISE EXCEPTION 'engine steps must append under original dispatch ownership' USING ERRCODE='55000';
    END IF;
    FOR step_index IN 0..jsonb_array_length(OLD.engine_steps_json)-1 LOOP
      IF NEW.engine_steps_json->step_index IS DISTINCT FROM OLD.engine_steps_json->step_index THEN
        RAISE EXCEPTION 'engine step history is immutable' USING ERRCODE='55000';
      END IF;
    END LOOP;
  END IF;
  material:=NEW.sealed_manifest->'execution'->'engineDefinition';
  engine_hash:=encode(sha256(convert_to(material::text,'UTF8')),'hex');
  IF (jsonb_typeof(material)='object' AND material->>'schemaVersion'='1' AND material->>'engine'=NEW.task_type) IS NOT TRUE THEN
    RAISE EXCEPTION 'approved engine material is required' USING ERRCODE='55000';
  END IF;
  IF NEW.task_type='flink' AND (NEW.flink_endpoint IS DISTINCT FROM material->>'gatewayOrigin' OR NEW.flink_mode IS DISTINCT FROM material->>'mode') THEN
    RAISE EXCEPTION 'Flink capacity scope must match its sealed canonical origin and mode' USING ERRCODE='55000';
  END IF;
  IF TG_OP='UPDATE' AND OLD.engine_dispatch_token IS NOT NULL AND
    ROW(NEW.engine_dispatch_token,NEW.engine_dispatch_started_at,NEW.engine_runner_origin,NEW.runner_id)
      IS DISTINCT FROM ROW(OLD.engine_dispatch_token,OLD.engine_dispatch_started_at,OLD.engine_runner_origin,OLD.runner_id) THEN
    RAISE EXCEPTION 'engine dispatch ownership is immutable' USING ERRCODE='55000';
  END IF;
  IF TG_OP='UPDATE' AND OLD.engine_receipt_json IS NOT NULL AND
    ROW(NEW.engine_receipt_json,NEW.engine_receipt_sha256,NEW.remote_handle)
      IS DISTINCT FROM ROW(OLD.engine_receipt_json,OLD.engine_receipt_sha256,OLD.remote_handle) THEN
    RAISE EXCEPTION 'engine receipt binding is immutable' USING ERRCODE='55000';
  END IF;
  IF TG_OP='UPDATE' AND OLD.engine_result_json IS NOT NULL AND
    ROW(NEW.engine_result_json,NEW.engine_result_sha256,NEW.result_metadata_json,NEW.status,NEW.affected_rows,NEW.error_message,NEW.finished_at)
      IS DISTINCT FROM ROW(OLD.engine_result_json,OLD.engine_result_sha256,OLD.result_metadata_json,OLD.status,OLD.affected_rows,OLD.error_message,OLD.finished_at) THEN
    RAISE EXCEPTION 'engine terminal evidence is immutable' USING ERRCODE='55000';
  END IF;
  IF TG_OP='UPDATE' AND OLD.flink_job_id IS NOT NULL AND NEW.flink_job_id IS DISTINCT FROM OLD.flink_job_id THEN
    RAISE EXCEPTION 'original Flink job identity is immutable' USING ERRCODE='55000';
  END IF;
  IF NEW.engine_dispatch_token IS NOT NULL AND (TG_OP='INSERT' OR OLD.engine_dispatch_token IS NULL) THEN
    SELECT task.status,task.execution_revision,task.execution_approval,task.execution_definition_json,
      instance.active_attempt_id,instance.fencing_token,instance.lease_expires_at
      INTO current_source FROM workflow.task_instance instance JOIN workflow.task task ON task.id=instance.task_id
      WHERE instance.id=NEW.instance_id FOR SHARE OF task,instance;
    IF NOT FOUND OR current_source.status<>'enabled' OR current_source.execution_revision IS DISTINCT FROM NEW.execution_revision
      OR current_source.execution_approval IS DISTINCT FROM NEW.approval_json
      OR current_source.execution_definition_json IS DISTINCT FROM NEW.sealed_manifest->'execution'
      OR current_source.active_attempt_id<>NEW.id OR current_source.fencing_token<>NEW.fencing_token
      OR current_source.lease_expires_at IS NULL OR NEW.lease_expires_at IS NULL
      OR current_source.lease_expires_at<=clock_timestamp() OR NEW.lease_expires_at<=clock_timestamp()
      OR NEW.status NOT IN ('submitting','running') OR NEW.engine_dispatch_started_at>clock_timestamp()
      OR NEW.engine_dispatch_started_at<clock_timestamp()-interval '5 seconds'
      OR (NEW.task_type='spark' AND NEW.queued_at+interval '300 seconds'<=clock_timestamp())
      OR (NEW.invocation_json->>'kind'='pipeline' AND NOT workflow.pipeline_binding_valid(NEW))
      OR (NEW.invocation_json->>'kind'='cron' AND NOT EXISTS(SELECT 1 FROM workflow.task task
        WHERE task.id=(NEW.sealed_manifest->>'resourceId')::bigint AND task.cron_actor=NEW.invocation_json
          AND task.cron_approved_execution_revision=NEW.execution_revision AND task.schedule_version=NEW.source_schedule_generation
          AND task.cron_binding_generation=NEW.source_binding_generation)) THEN
      RAISE EXCEPTION 'current fenced authority is required for engine dispatch' USING ERRCODE='55000';
    END IF;
    IF NEW.task_type='spark' AND NOT EXISTS (SELECT 1 FROM jsonb_array_elements(material->'runners') runner
      WHERE runner->>'id'=NEW.runner_id::text AND runner->>'origin'=NEW.engine_runner_origin) THEN
      RAISE EXCEPTION 'runner is outside approved engine material' USING ERRCODE='55000';
    ELSIF NEW.task_type='flink' AND NEW.engine_runner_origin IS DISTINCT FROM material->>'gatewayOrigin' THEN
      RAISE EXCEPTION 'gateway is outside approved engine material' USING ERRCODE='55000';
    END IF;
  END IF;
  IF NEW.remote_handle IS NOT NULL OR NEW.engine_receipt_json IS NOT NULL THEN
    receipt:=NEW.engine_receipt_json;
    IF (NEW.engine_dispatch_token IS NOT NULL AND jsonb_typeof(receipt)='object'
      AND receipt ?& ARRAY['schemaVersion','engine','instanceId','attemptId','fence','manifestSha256','engineDefinitionSha256','origin','requestKey','remoteHandle','artifactSha256']
      AND receipt-ARRAY['schemaVersion','engine','instanceId','attemptId','fence','manifestSha256','engineDefinitionSha256','origin','requestKey','remoteHandle','artifactSha256']='{}'::jsonb
      AND receipt->>'schemaVersion'='1' AND receipt->>'engine'=NEW.task_type
      AND receipt->>'instanceId'=NEW.instance_id::text AND receipt->>'attemptId'=NEW.id::text
      AND receipt->>'fence'=NEW.fencing_token::text AND receipt->>'manifestSha256'=NEW.manifest_sha256
      AND receipt->>'engineDefinitionSha256'=engine_hash AND receipt->>'origin'=NEW.engine_runner_origin
      AND receipt->>'requestKey'='attempt-'||NEW.id||'-f'||NEW.fencing_token
      AND receipt->>'remoteHandle'=NEW.remote_handle AND length(NEW.remote_handle) BETWEEN 1 AND 512
      AND (CASE WHEN NEW.task_type='spark' THEN receipt->>'artifactSha256'=material->>'artifactSha256' ELSE receipt->'artifactSha256'='null'::jsonb END)) IS NOT TRUE THEN
      RAISE EXCEPTION 'engine receipt must bind the original source and handle' USING ERRCODE='55000';
    END IF;
  END IF;
  IF NEW.engine_result_json IS NOT NULL THEN
    result:=NEW.engine_result_json;
    IF (result->>'schemaVersion'='1' AND result->>'engine'=NEW.task_type
      AND result->>'instanceId'=NEW.instance_id::text AND result->>'attemptId'=NEW.id::text
      AND result->>'fence'=NEW.fencing_token::text AND result->>'manifestSha256'=NEW.manifest_sha256
      AND (CASE WHEN result->>'state'='not_started' THEN NEW.engine_receipt_json IS NULL
          AND NEW.status IN ('failed','cancelled','skipped') ELSE result->>'state'=NEW.status
          AND result->>'receiptSha256'=NEW.engine_receipt_sha256 END)) IS NOT TRUE THEN
      RAISE EXCEPTION 'engine result must bind original attempt and receipt' USING ERRCODE='55000';
    END IF;
  ELSIF NEW.engine_dispatch_token IS NOT NULL AND NEW.status IN ('succeeded','failed','cancelled','timed_out','skipped') THEN
    RAISE EXCEPTION 'engine terminal state requires result evidence' USING ERRCODE='55000';
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER fixed_engine_execution BEFORE INSERT OR UPDATE ON workflow.execution_attempt
FOR EACH ROW EXECUTE FUNCTION workflow.guard_engine_execution();
