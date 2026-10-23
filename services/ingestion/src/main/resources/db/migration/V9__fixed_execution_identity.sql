-- Fixed execution storage only. Existing rows receive no actor or approval.
-- Canonical definition bytes are PostgreSQL jsonb::text encoded as UTF8 (schemaVersion=1).
CREATE FUNCTION ingestion.execution_identity_valid(value JSONB) RETURNS BOOLEAN
LANGUAGE SQL IMMUTABLE PARALLEL SAFE AS $$
 SELECT (jsonb_typeof(value)='object'
 AND jsonb_typeof(value->'uid')='number' AND (value->>'uid') ~ '^[1-9][0-9]{0,18}$'
 AND (value->>'uid')::numeric <= 9223372036854775807
 AND jsonb_typeof(value->'username')='string' AND length(value->>'username') BETWEEN 1 AND 128
 AND jsonb_typeof(value->'accessVersion')='number' AND (value->>'accessVersion') ~ '^(0|[1-9][0-9]{0,18})$'
 AND (value->>'accessVersion')::numeric <= 9223372036854775807) IS TRUE
$$;
CREATE FUNCTION ingestion.execution_actor_valid(value JSONB) RETURNS BOOLEAN
LANGUAGE SQL IMMUTABLE PARALLEL SAFE AS $$
 SELECT (ingestion.execution_identity_valid(value)
 AND value ?& ARRAY['kind','uid','username','accessVersion','requestId']
 AND value - ARRAY['kind','uid','username','accessVersion','requestId']='{}'::jsonb
 AND value->>'kind' IN ('manual','cron','pipeline','workflow-child')
 AND jsonb_typeof(value->'requestId')='string'
 AND (value->>'requestId') ~ '^[A-Za-z0-9._:-]{1,128}$') IS TRUE
$$;
CREATE FUNCTION ingestion.execution_approval_valid(value JSONB, revision BIGINT, definition_hash TEXT) RETURNS BOOLEAN
LANGUAGE SQL IMMUTABLE PARALLEL SAFE AS $$
 SELECT (ingestion.execution_identity_valid(value)
 AND value ?& ARRAY['uid','username','accessVersion','revision','definitionSha256']
 AND value - ARRAY['uid','username','accessVersion','revision','definitionSha256']='{}'::jsonb
 AND jsonb_typeof(value->'revision')='number'
 AND value->>'revision'=revision::text AND revision>0
 AND jsonb_typeof(value->'definitionSha256')='string'
 AND value->>'definitionSha256'=definition_hash AND definition_hash ~ '^[a-f0-9]{64}$') IS TRUE
$$;
CREATE FUNCTION ingestion.execution_definition_projection(body JSONB) RETURNS JSONB
LANGUAGE SQL IMMUTABLE PARALLEL SAFE AS $$ SELECT jsonb_build_object('schemaVersion',1,'sourceDatasourceId',body->'source_datasource_id','targetDatasourceId',body->'target_datasource_id','sourceSql',body->'source_sql','targetTable',body->'target_table','writeMode',body->'write_mode','batchSize',body->'batch_size') $$;

ALTER TABLE ingestion.ingestion_task
    ADD COLUMN execution_revision BIGINT NOT NULL DEFAULT 1 CHECK (execution_revision>0),
    ADD COLUMN execution_definition_json JSONB,
    ADD COLUMN execution_definition_sha256 CHAR(64),
    ADD COLUMN execution_approval JSONB,
    ADD COLUMN execution_approved_at TIMESTAMPTZ,
    ADD COLUMN cron_actor JSONB,
    ADD COLUMN cron_approved_execution_revision BIGINT,
    ADD COLUMN cron_binding_generation BIGINT NOT NULL DEFAULT 0 CHECK (cron_binding_generation>=0);

CREATE FUNCTION ingestion.guard_execution_definition() RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE definition JSONB;
BEGIN
    definition := ingestion.execution_definition_projection(to_jsonb(NEW));
    IF TG_OP='INSERT' THEN
        NEW.execution_revision := 1;
        NEW.execution_approval := NULL; NEW.execution_approved_at := NULL;
        NEW.cron_actor := NULL; NEW.cron_approved_execution_revision := NULL;
        NEW.cron_binding_generation := 0;
    ELSE
        IF definition IS DISTINCT FROM ingestion.execution_definition_projection(to_jsonb(OLD)) THEN
            NEW.execution_revision := OLD.execution_revision+1;
            NEW.execution_approval := NULL; NEW.execution_approved_at := NULL;
            NEW.cron_actor := NULL; NEW.cron_approved_execution_revision := NULL;
        ELSE NEW.execution_revision := OLD.execution_revision;
        END IF;
        IF ROW(NEW.schedule_cron,NEW.status) IS DISTINCT FROM ROW(OLD.schedule_cron,OLD.status) THEN
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
-- Runs before the existing assign_schedule_generation trigger.
CREATE TRIGGER a00_execution_definition_guard BEFORE INSERT OR UPDATE ON ingestion.ingestion_task
FOR EACH ROW EXECUTE FUNCTION ingestion.guard_execution_definition();
UPDATE ingestion.ingestion_task SET execution_revision=execution_revision;
ALTER TABLE ingestion.ingestion_task
    ALTER COLUMN execution_definition_json SET NOT NULL,
    ALTER COLUMN execution_definition_sha256 SET NOT NULL,
    ADD CONSTRAINT fixed_execution_approval CHECK (
       (execution_approval IS NULL AND execution_approved_at IS NULL)
       OR (execution_approved_at IS NOT NULL AND ingestion.execution_approval_valid(
          execution_approval,execution_revision,execution_definition_sha256)) IS TRUE),
    ADD CONSTRAINT fixed_execution_cron_actor CHECK (
       (cron_actor IS NULL AND cron_approved_execution_revision IS NULL)
       OR (execution_approval IS NOT NULL AND ingestion.execution_actor_valid(cron_actor)
           AND cron_actor->>'kind'='cron' AND cron_approved_execution_revision=execution_revision
           AND status='enabled' AND length(btrim(schedule_cron))>0) IS TRUE);

ALTER TABLE ingestion.ingestion_run
    ADD COLUMN execution_fence BIGINT NOT NULL DEFAULT 1 CHECK (execution_fence=1),
    ADD COLUMN execution_revision BIGINT,
    ADD COLUMN invocation_json JSONB,
    ADD COLUMN approval_json JSONB,
    ADD COLUMN sealed_manifest JSONB,
    ADD COLUMN manifest_sha256 CHAR(64),
    ADD COLUMN manifest_sealed_at TIMESTAMPTZ,
    ADD COLUMN metadata_receipt_id BIGINT CHECK (metadata_receipt_id>0),
    ADD CONSTRAINT fixed_execution_seal CHECK (
       (manifest_sealed_at IS NULL AND sealed_manifest IS NULL AND manifest_sha256 IS NULL
        AND execution_revision IS NULL AND invocation_json IS NULL AND approval_json IS NULL)
       OR (manifest_sealed_at IS NOT NULL AND execution_revision>0
           AND ingestion.execution_actor_valid(invocation_json) AND ingestion.execution_identity_valid(approval_json)
           AND jsonb_typeof(sealed_manifest)='object'
           AND manifest_sha256=encode(sha256(convert_to(sealed_manifest::text,'UTF8')),'hex')) IS TRUE);
CREATE FUNCTION ingestion.guard_sealed_execution() RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE field TEXT; resource BIGINT; definition RECORD;
BEGIN
    IF TG_OP='DELETE' THEN
        IF OLD.manifest_sealed_at IS NOT NULL THEN
            RAISE EXCEPTION 'sealed execution must be retained' USING ERRCODE='55000';
        END IF;
        RETURN OLD;
    END IF;
    IF TG_OP='UPDATE' AND OLD.manifest_sealed_at IS NOT NULL THEN
        FOREACH field IN ARRAY ARRAY['id','task_id','execution_snapshot','triggered_by','request_key','replay_of_run_id','execution_fence','execution_revision','invocation_json','approval_json','sealed_manifest','manifest_sha256','manifest_sealed_at'] LOOP
            IF to_jsonb(NEW)->field IS DISTINCT FROM to_jsonb(OLD)->field THEN
                RAISE EXCEPTION 'sealed execution is immutable' USING ERRCODE='55000';
            END IF;
        END LOOP;
        IF OLD.metadata_receipt_id IS NOT NULL AND NEW.metadata_receipt_id IS DISTINCT FROM OLD.metadata_receipt_id THEN
            RAISE EXCEPTION 'execution receipt binding is immutable' USING ERRCODE='55000';
        END IF;
    END IF;
    IF NEW.manifest_sealed_at IS NOT NULL AND (TG_OP='INSERT' OR OLD.manifest_sealed_at IS NULL) THEN
        resource := NEW.task_id;
        SELECT execution_revision,execution_approval,execution_definition_json,execution_definition_sha256,status,cron_actor,cron_approved_execution_revision
          INTO definition FROM ingestion.ingestion_task WHERE id=resource FOR SHARE;
        IF NOT FOUND OR definition.status<>'enabled' OR definition.execution_approval IS NULL
           OR NEW.execution_revision IS DISTINCT FROM definition.execution_revision
           OR NEW.approval_json IS DISTINCT FROM definition.execution_approval THEN
            RAISE EXCEPTION 'current approved definition is required to seal' USING ERRCODE='55000';
        END IF;
        IF NEW.lease_owner IS NULL OR NEW.lease_expires_at<=clock_timestamp() OR NEW.status<>'running' THEN
            RAISE EXCEPTION 'active fenced source lease is required to seal' USING ERRCODE='55000';
        END IF;
        IF NOT (ingestion.execution_actor_valid(NEW.invocation_json)
           AND ingestion.execution_approval_valid(NEW.approval_json,NEW.execution_revision,definition.execution_definition_sha256)
           AND NEW.sealed_manifest ?& ARRAY['schemaVersion','origin','operation','resourceId','runId','attemptId','fence','executionRevision','definitionSha256','actor','approval','execution','parameters','parent']
           AND NEW.sealed_manifest - ARRAY['schemaVersion','origin','operation','resourceId','runId','attemptId','fence','executionRevision','definitionSha256','actor','approval','execution','parameters','parent']='{}'::jsonb
           AND NEW.sealed_manifest->>'schemaVersion'='1'
           AND jsonb_typeof(NEW.sealed_manifest->'schemaVersion')='number'
           AND jsonb_typeof(NEW.sealed_manifest->'resourceId')='number'
           AND jsonb_typeof(NEW.sealed_manifest->'runId')='number'
           AND jsonb_typeof(NEW.sealed_manifest->'attemptId')='number'
           AND jsonb_typeof(NEW.sealed_manifest->'fence')='number'
           AND jsonb_typeof(NEW.sealed_manifest->'executionRevision')='number'
           AND NEW.sealed_manifest->>'origin'='ingestion'
           AND NEW.sealed_manifest->>'operation'='metadata.fixed-transfer.submit'
           AND NEW.sealed_manifest->>'resourceId'=resource::text
           AND NEW.sealed_manifest->>'runId'=NEW.id::text
           AND NEW.sealed_manifest->>'attemptId'=(0)::text
           AND NEW.sealed_manifest->>'fence'=NEW.execution_fence::text
           AND NEW.sealed_manifest->>'executionRevision'=NEW.execution_revision::text
           AND NEW.sealed_manifest->>'definitionSha256'=definition.execution_definition_sha256
           AND NEW.sealed_manifest->'actor'=NEW.invocation_json
           AND NEW.sealed_manifest->'approval'=NEW.approval_json
           AND NEW.sealed_manifest->'execution'=definition.execution_definition_json
           AND NEW.sealed_manifest->'parameters'='{}'::jsonb
           AND NEW.execution_snapshot::jsonb=definition.execution_definition_json
           AND NEW.invocation_json->>'kind'<>'workflow-child' AND NEW.sealed_manifest->'parent'='null'::jsonb
           ) IS TRUE THEN
            RAISE EXCEPTION 'sealed manifest does not match its trusted source binding' USING ERRCODE='55000';
        END IF;
        IF NEW.invocation_json->>'kind'='cron' AND
           (NEW.invocation_json IS DISTINCT FROM definition.cron_actor
            OR definition.cron_approved_execution_revision IS DISTINCT FROM NEW.execution_revision) THEN
            RAISE EXCEPTION 'cron actor is not currently bound' USING ERRCODE='55000';
        END IF;
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER fixed_execution_seal_guard BEFORE INSERT OR UPDATE OR DELETE ON ingestion.ingestion_run
FOR EACH ROW EXECUTE FUNCTION ingestion.guard_sealed_execution();

-- Binding and definition changes invalidate Quartz generation in the same transaction.
CREATE OR REPLACE FUNCTION ingestion.assign_schedule_generation() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP='INSERT' THEN NEW.schedule_version := nextval('ingestion.schedule_generation_seq');
    ELSIF ROW(NEW.name,NEW.schedule_cron,NEW.status,NEW.cron_binding_generation,NEW.execution_revision)
       IS DISTINCT FROM ROW(OLD.name,OLD.schedule_cron,OLD.status,OLD.cron_binding_generation,OLD.execution_revision) THEN
        NEW.schedule_version := nextval('ingestion.schedule_generation_seq');
    ELSE NEW.schedule_version := OLD.schedule_version;
    END IF;
    RETURN NEW;
END $$;
CREATE OR REPLACE FUNCTION ingestion.enqueue_schedule_desired_state() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE item_id BIGINT; item_generation BIGINT; is_deleted BOOLEAN;
BEGIN
    IF TG_OP='DELETE' THEN
        item_id := OLD.id; item_generation := nextval('ingestion.schedule_generation_seq'); is_deleted := true;
    ELSE
        IF TG_OP='UPDATE' AND NEW.schedule_version=OLD.schedule_version THEN RETURN NEW; END IF;
        item_id := NEW.id; item_generation := NEW.schedule_version; is_deleted := false;
    END IF;
    INSERT INTO ingestion.scheduler_sync_outbox(resource_id,generation,deleted,name,cron,enabled)
    VALUES(item_id,item_generation,is_deleted,CASE WHEN is_deleted THEN NULL ELSE NEW.name END,
       CASE WHEN is_deleted THEN NULL ELSE NEW.schedule_cron END,
       CASE WHEN is_deleted THEN false ELSE NEW.status='enabled' AND NEW.execution_approval IS NOT NULL
           AND NEW.cron_actor IS NOT NULL AND NEW.cron_approved_execution_revision=NEW.execution_revision END)
    ON CONFLICT(resource_id) DO UPDATE SET generation=EXCLUDED.generation,deleted=EXCLUDED.deleted,
       name=EXCLUDED.name,cron=EXCLUDED.cron,enabled=EXCLUDED.enabled,status='pending',
       attempts=0,next_attempt_at=now(),last_error=NULL,updated_at=now(),applied_at=NULL
    WHERE scheduler_sync_outbox.generation<EXCLUDED.generation;
    IF TG_OP='DELETE' THEN RETURN OLD; END IF;
    RETURN NEW;
END $$;
-- Historical enabled schedules have no approval/actor. Enqueue their explicit removal.
ALTER TABLE ingestion.ingestion_task DISABLE TRIGGER assign_schedule_generation;
UPDATE ingestion.ingestion_task SET schedule_version=nextval('ingestion.schedule_generation_seq');
ALTER TABLE ingestion.ingestion_task ENABLE TRIGGER assign_schedule_generation;
