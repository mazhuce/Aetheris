-- Historical usernames, node ids and latest task instances are not authority.
-- Old rows remain unsealed and cannot pass the new source lookup.
ALTER TABLE workflow.pipeline_run
  ADD COLUMN invocation_json JSONB,
  ADD COLUMN plan_json JSONB,
  ADD COLUMN plan_sha256 CHAR(64),
  ADD COLUMN execution_fence BIGINT NOT NULL DEFAULT 0 CHECK (execution_fence>=0),
  ADD COLUMN worker_id VARCHAR(128),
  ADD CONSTRAINT pipeline_plan_seal CHECK (
    (invocation_json IS NULL AND plan_json IS NULL AND plan_sha256 IS NULL)
    OR (workflow.execution_actor_valid(invocation_json) AND invocation_json->>'kind'='pipeline'
      AND plan_json IS NOT NULL AND plan_sha256=encode(sha256(convert_to(plan_json::text,'UTF8')),'hex')) IS TRUE);
ALTER TABLE workflow.pipeline_node_run
  ADD COLUMN execution_revision BIGINT,
  ADD COLUMN definition_sha256 CHAR(64),
  ADD COLUMN definition_json JSONB,
  ADD COLUMN approval_json JSONB,
  ADD COLUMN dependency_evidence JSONB NOT NULL DEFAULT '[]'::jsonb CHECK (jsonb_typeof(dependency_evidence)='array'),
  ADD COLUMN attempt_id BIGINT REFERENCES workflow.execution_attempt(id),
  ADD COLUMN fencing_token BIGINT,
  ADD CONSTRAINT pipeline_node_definition_seal CHECK (
    (execution_revision IS NULL AND definition_sha256 IS NULL AND definition_json IS NULL AND approval_json IS NULL)
    OR (execution_revision>0 AND definition_sha256=encode(sha256(convert_to(definition_json::text,'UTF8')),'hex')
      AND workflow.execution_approval_valid(approval_json,execution_revision,definition_sha256)) IS TRUE),
  ADD CONSTRAINT pipeline_node_attempt_binding CHECK (
    (attempt_id IS NULL AND fencing_token IS NULL)
    OR (instance_id IS NOT NULL AND attempt_id>0 AND fencing_token>0) IS TRUE);
ALTER TABLE workflow.execution_attempt
  ADD COLUMN pipeline_run_id BIGINT REFERENCES workflow.pipeline_run(id),
  ADD COLUMN pipeline_node_id BIGINT REFERENCES workflow.pipeline_node_run(id),
  ADD COLUMN pipeline_fence BIGINT,
  ADD CONSTRAINT pipeline_attempt_references CHECK (
    (pipeline_run_id IS NULL AND pipeline_node_id IS NULL AND pipeline_fence IS NULL)
    OR (pipeline_run_id>0 AND pipeline_node_id>0 AND pipeline_fence>0) IS TRUE);
CREATE INDEX pipeline_node_attempt ON workflow.pipeline_node_run(attempt_id) WHERE attempt_id IS NOT NULL;
CREATE UNIQUE INDEX uq_pipeline_sealed_unconfirmed_target_date ON workflow.pipeline_run(target_task_id,biz_date)
  WHERE plan_json IS NOT NULL AND status IN ('queued','running','unknown');

CREATE FUNCTION workflow.pipeline_plan_current(body JSONB) RETURNS BOOLEAN LANGUAGE SQL STABLE AS $$
  SELECT (jsonb_typeof(body)='object' AND body ?& ARRAY['schemaVersion','targetTaskId','bizDate','actor','nodes','executionOrder']
    AND body-ARRAY['schemaVersion','targetTaskId','bizDate','actor','nodes','executionOrder']='{}'::jsonb
    AND body->'schemaVersion'='1'::jsonb AND jsonb_typeof(body->'nodes')='array'
    AND jsonb_typeof(body->'executionOrder')='array' AND jsonb_array_length(body->'nodes') BETWEEN 1 AND 512
    AND jsonb_array_length(body->'executionOrder') BETWEEN 1 AND 512
    AND NOT EXISTS (
      SELECT 1 FROM jsonb_array_elements(body->'nodes') frozen
      LEFT JOIN workflow.task task ON task.id=(frozen->>'taskId')::bigint
      WHERE task.id IS NULL OR task.status<>'enabled' OR task.execution_approval IS NULL
        OR task.execution_revision::text IS DISTINCT FROM frozen->>'revision'
        OR task.execution_definition_sha256 IS DISTINCT FROM frozen->>'definitionSha256'
        OR task.execution_definition_json IS DISTINCT FROM frozen->'execution'
        OR task.execution_approval IS DISTINCT FROM frozen->'approval')) IS TRUE
$$;

CREATE FUNCTION workflow.pipeline_success_evidence(node_key BIGINT) RETURNS BOOLEAN LANGUAGE SQL STABLE AS $$
  SELECT EXISTS(SELECT 1 FROM workflow.pipeline_node_run node
    JOIN workflow.task_instance instance ON instance.id=node.instance_id
    JOIN workflow.execution_attempt attempt ON attempt.id=node.attempt_id AND attempt.instance_id=instance.id
    JOIN workflow.pipeline_run run ON run.id=node.pipeline_run_id
    WHERE node.id=node_key AND node.status='succeeded' AND node.execution_revision IS NOT NULL
      AND instance.active_attempt_id=attempt.id AND instance.fencing_token=node.fencing_token
      AND attempt.fencing_token=node.fencing_token AND instance.status='success' AND attempt.status='succeeded'
      AND attempt.finished_at IS NOT NULL AND instance.finished_at IS NOT NULL
      AND attempt.pipeline_node_id=node.id AND attempt.pipeline_run_id=run.id
      AND attempt.invocation_json=run.invocation_json AND attempt.execution_revision=node.execution_revision
      AND attempt.approval_json=node.approval_json AND attempt.manifest_sealed_at IS NOT NULL
      AND attempt.metadata_receipt_id IS NOT NULL)
$$;

CREATE FUNCTION workflow.pipeline_retry_ancestor(run_key BIGINT,source_key BIGINT) RETURNS BOOLEAN LANGUAGE SQL STABLE AS $$
  WITH RECURSIVE ancestry AS (
    SELECT retry_of_run_id id FROM workflow.pipeline_run WHERE id=run_key
    UNION SELECT parent.retry_of_run_id FROM workflow.pipeline_run parent JOIN ancestry child ON parent.id=child.id)
  SELECT EXISTS(SELECT 1 FROM ancestry WHERE id=source_key)
$$;
CREATE FUNCTION workflow.pipeline_dependencies_ready(run_key BIGINT,node_key BIGINT) RETURNS BOOLEAN LANGUAGE SQL STABLE AS $$
  SELECT NOT EXISTS (
    SELECT 1 FROM workflow.pipeline_node_run node
    CROSS JOIN LATERAL jsonb_array_elements_text(COALESCE(node.definition_json->'dependencies'->'upstreamTaskIds','[]'::jsonb)) edge
    WHERE node.id=node_key AND node.pipeline_run_id=run_key AND NOT (
      EXISTS(SELECT 1 FROM workflow.pipeline_node_run upstream
        WHERE upstream.pipeline_run_id=run_key AND upstream.task_id=edge.value::bigint
          AND upstream.sequence_no<node.sequence_no AND workflow.pipeline_success_evidence(upstream.id))
      OR EXISTS(SELECT 1 FROM jsonb_array_elements(node.dependency_evidence) proof
        JOIN workflow.pipeline_node_run upstream ON upstream.id=(proof->>'nodeId')::bigint
        JOIN workflow.pipeline_run run ON run.id=run_key
        WHERE upstream.task_id=edge.value::bigint AND upstream.pipeline_run_id=(proof->>'sourceRunId')::bigint
          AND workflow.pipeline_retry_ancestor(run_key,upstream.pipeline_run_id)
          AND upstream.instance_id::text=proof->>'instanceId' AND upstream.attempt_id::text=proof->>'attemptId'
          AND upstream.fencing_token::text=proof->>'fence' AND workflow.pipeline_success_evidence(upstream.id))))
$$;

CREATE FUNCTION workflow.guard_pipeline_run() RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE field TEXT;
BEGIN
  IF TG_OP='DELETE' THEN
    IF OLD.plan_json IS NOT NULL THEN RAISE EXCEPTION 'sealed pipeline must be retained' USING ERRCODE='55000'; END IF;
    RETURN OLD;
  END IF;
  IF TG_OP='UPDATE' AND OLD.plan_json IS NOT NULL THEN
    FOREACH field IN ARRAY ARRAY['id','target_task_id','biz_date','triggered_by','retry_of_run_id','resume_from_task_id','invocation_json','plan_json','plan_sha256'] LOOP
      IF to_jsonb(NEW)->field IS DISTINCT FROM to_jsonb(OLD)->field THEN
        RAISE EXCEPTION 'sealed pipeline source is immutable' USING ERRCODE='55000';
      END IF;
    END LOOP;
  END IF;
  IF NEW.plan_json IS NOT NULL AND (TG_OP='INSERT' OR OLD.plan_json IS NULL) THEN
    PERFORM pg_advisory_xact_lock(87316,hashtext(NEW.target_task_id::text||'|'||NEW.biz_date));
    IF EXISTS(SELECT 1 FROM workflow.pipeline_run other WHERE other.id<>NEW.id AND other.target_task_id=NEW.target_task_id
      AND other.biz_date=NEW.biz_date AND other.status IN ('queued','running','unknown')) THEN
      RAISE EXCEPTION 'unconfirmed pipeline must be reconciled first' USING ERRCODE='55000';
    END IF;
    IF (workflow.pipeline_plan_current(NEW.plan_json) AND NEW.plan_json->'actor'=NEW.invocation_json
      AND NEW.plan_json->>'bizDate'=NEW.biz_date AND NEW.plan_json->>'targetTaskId'=NEW.target_task_id::text
      AND NEW.triggered_by=NEW.invocation_json->>'username'
      AND NEW.plan_json->'executionOrder'=(SELECT jsonb_agg(task_id ORDER BY sequence_no)
        FROM workflow.pipeline_node_run WHERE pipeline_run_id=NEW.id)
      AND NOT EXISTS(SELECT 1 FROM workflow.pipeline_node_run node WHERE node.pipeline_run_id=NEW.id AND NOT EXISTS (
        SELECT 1 FROM jsonb_array_elements(NEW.plan_json->'nodes') frozen WHERE frozen->>'taskId'=node.task_id::text
          AND frozen->>'revision'=node.execution_revision::text AND frozen->>'definitionSha256'=node.definition_sha256
          AND frozen->'execution'=node.definition_json AND frozen->'approval'=node.approval_json
          AND frozen->'externalDependencies'=node.dependency_evidence))) IS NOT TRUE THEN
      RAISE EXCEPTION 'pipeline seal does not match its frozen nodes' USING ERRCODE='55000';
    END IF;
  END IF;
  IF TG_OP='UPDATE' AND ROW(NEW.execution_fence,NEW.worker_id) IS DISTINCT FROM ROW(OLD.execution_fence,OLD.worker_id)
    AND NOT (OLD.status='queued' AND NEW.status='running' AND NEW.execution_fence=OLD.execution_fence+1
      AND NEW.worker_id IS NOT NULL AND length(NEW.worker_id)>0) THEN
    RAISE EXCEPTION 'pipeline worker must claim a new dispatch fence' USING ERRCODE='55000';
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER pipeline_source_guard BEFORE INSERT OR UPDATE OR DELETE ON workflow.pipeline_run
  FOR EACH ROW EXECUTE FUNCTION workflow.guard_pipeline_run();

CREATE FUNCTION workflow.guard_pipeline_node() RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE field TEXT; parent_plan JSONB;
BEGIN
  IF TG_OP='DELETE' THEN
    IF OLD.execution_revision IS NOT NULL THEN RAISE EXCEPTION 'sealed pipeline node must be retained' USING ERRCODE='55000'; END IF;
    RETURN OLD;
  END IF;
  IF TG_OP='INSERT' OR OLD.execution_revision IS NULL THEN
    -- Serialize node-set construction against sealing. An invisible/uncommitted parent is not authority.
    SELECT run.plan_json INTO parent_plan FROM workflow.pipeline_run run WHERE run.id=NEW.pipeline_run_id FOR UPDATE;
    IF NOT FOUND OR parent_plan IS NOT NULL THEN
      RAISE EXCEPTION 'sealed or unavailable pipeline cannot acquire additional nodes' USING ERRCODE='55000';
    END IF;
  END IF;
  IF TG_OP='UPDATE' AND OLD.execution_revision IS NOT NULL THEN
    FOREACH field IN ARRAY ARRAY['id','pipeline_run_id','task_id','sequence_no','execution_revision','definition_sha256','definition_json','approval_json','dependency_evidence'] LOOP
      IF to_jsonb(NEW)->field IS DISTINCT FROM to_jsonb(OLD)->field THEN
        RAISE EXCEPTION 'pipeline node definition is immutable' USING ERRCODE='55000';
      END IF;
    END LOOP;
    IF OLD.attempt_id IS NOT NULL AND ROW(NEW.instance_id,NEW.attempt_id,NEW.fencing_token)
      IS DISTINCT FROM ROW(OLD.instance_id,OLD.attempt_id,OLD.fencing_token) THEN
      RAISE EXCEPTION 'pipeline node attempt is immutable' USING ERRCODE='55000';
    END IF;
  END IF;
  IF NEW.attempt_id IS NOT NULL AND NOT EXISTS(SELECT 1 FROM workflow.execution_attempt attempt
    JOIN workflow.task_instance instance ON instance.id=attempt.instance_id
    JOIN workflow.pipeline_run run ON run.id=NEW.pipeline_run_id
    WHERE attempt.id=NEW.attempt_id AND instance.id=NEW.instance_id AND instance.task_id=NEW.task_id
      AND instance.biz_date=run.biz_date AND attempt.fencing_token=NEW.fencing_token) THEN
    RAISE EXCEPTION 'pipeline node attempt does not match its resource' USING ERRCODE='55000';
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER pipeline_node_source_guard BEFORE INSERT OR UPDATE OR DELETE ON workflow.pipeline_node_run
  FOR EACH ROW EXECUTE FUNCTION workflow.guard_pipeline_node();

CREATE FUNCTION workflow.pipeline_binding_valid(value workflow.execution_attempt) RETURNS BOOLEAN LANGUAGE SQL STABLE AS $$
  SELECT EXISTS(SELECT 1 FROM workflow.pipeline_run run
    JOIN workflow.pipeline_node_run node ON node.pipeline_run_id=run.id
    JOIN workflow.task_instance instance ON instance.id=node.instance_id
    JOIN workflow.task task ON task.id=node.task_id
    WHERE run.id=value.pipeline_run_id AND node.id=value.pipeline_node_id
      AND run.status='running' AND run.execution_fence=value.pipeline_fence AND run.worker_id IS NOT NULL
      AND run.lease_expires_at>clock_timestamp() AND node.status='running'
      AND node.attempt_id=value.id AND node.fencing_token=value.fencing_token AND instance.id=value.instance_id
      AND instance.active_attempt_id=value.id AND instance.fencing_token=value.fencing_token
      AND run.invocation_json=value.invocation_json AND value.invocation_json->>'kind'='pipeline'
      AND value.source_schedule_generation IS NULL AND value.source_binding_generation IS NULL
      AND node.execution_revision=value.execution_revision AND node.approval_json=value.approval_json
      AND task.execution_definition_sha256=node.definition_sha256 AND task.execution_definition_json=node.definition_json
      AND task.execution_revision=node.execution_revision AND task.execution_approval=node.approval_json AND task.status='enabled'
      AND run.plan_json->'executionOrder'->(node.sequence_no-1)=to_jsonb(node.task_id)
      AND EXISTS(SELECT 1 FROM jsonb_array_elements(run.plan_json->'nodes') frozen
        WHERE frozen->>'taskId'=node.task_id::text AND frozen->>'revision'=node.execution_revision::text
          AND frozen->>'definitionSha256'=node.definition_sha256 AND frozen->'execution'=node.definition_json
          AND frozen->'approval'=node.approval_json AND frozen->'externalDependencies'=node.dependency_evidence)
      AND run.biz_date=instance.biz_date AND workflow.pipeline_plan_current(run.plan_json)
      AND workflow.pipeline_dependencies_ready(run.id,node.id))
$$;

-- Keep the original manual/cron generation checks, and admit pipeline only via its sealed DB source.
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
    IF NEW.task_type='sql' THEN
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
