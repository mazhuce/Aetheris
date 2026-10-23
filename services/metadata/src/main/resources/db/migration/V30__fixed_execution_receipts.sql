-- Durable admission and unknown-result tombstones. This table does not itself authorize SQL.
CREATE TABLE metadata.execution_receipt (
    id BIGSERIAL PRIMARY KEY,
    origin VARCHAR(16) NOT NULL,
    operation VARCHAR(64) NOT NULL,
    run_id BIGINT NOT NULL CHECK (run_id>0),
    attempt_id BIGINT NOT NULL CHECK (attempt_id>=0),
    source_fence BIGINT NOT NULL CHECK (source_fence>0),
    execution_revision BIGINT NOT NULL CHECK (execution_revision>0),
    manifest_sha256 CHAR(64) NOT NULL CHECK (manifest_sha256 ~ '^[a-f0-9]{64}$'),
    raw_request_sha256 CHAR(64) NOT NULL CHECK (raw_request_sha256 ~ '^[a-f0-9]{64}$'),
    source_manifest_json JSONB NOT NULL CHECK (jsonb_typeof(source_manifest_json)='object'),
    execution_json JSONB NOT NULL CHECK (jsonb_typeof(execution_json)='object'),
    status VARCHAR(16) NOT NULL DEFAULT 'queued' CHECK (status IN ('queued','executing','succeeded','failed','unknown')),
    worker_id VARCHAR(128),
    worker_fence BIGINT NOT NULL DEFAULT 0 CHECK (worker_fence>=0),
    worker_lease_expires_at TIMESTAMPTZ,
    deadline_at TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    started_at TIMESTAMPTZ,
    finished_at TIMESTAMPTZ,
    result_json JSONB,
    safe_error VARCHAR(128) CHECK (safe_error ~ '^[A-Z0-9_.-]{1,128}$'),
    reconciliation_evidence TEXT,
    CONSTRAINT fixed_execution_receipt_unique UNIQUE (origin,operation,run_id,attempt_id,source_fence),
    CONSTRAINT fixed_execution_receipt_origin CHECK (
       (origin='workflow' AND operation='metadata.fixed-sql.submit' AND attempt_id>0)
       OR (origin='ingestion' AND operation='metadata.fixed-transfer.submit' AND attempt_id=0 AND source_fence=1)
       OR (origin='quality' AND operation='metadata.fixed-quality-query.submit' AND attempt_id=0 AND source_fence=1)),
    CONSTRAINT fixed_execution_receipt_manifest CHECK (
       manifest_sha256=encode(sha256(convert_to(source_manifest_json::text,'UTF8')),'hex')),
    CONSTRAINT fixed_execution_receipt_worker CHECK (
       status<>'executing' OR (worker_id IS NOT NULL AND length(worker_id)>0 AND worker_fence>0
           AND started_at IS NOT NULL AND worker_lease_expires_at IS NOT NULL)),
    CONSTRAINT fixed_execution_receipt_result CHECK (result_json IS NULL OR jsonb_typeof(result_json)='object'),
    CONSTRAINT fixed_execution_receipt_terminal CHECK (status NOT IN ('succeeded','failed')
       OR (finished_at IS NOT NULL AND ((status='succeeded' AND result_json IS NOT NULL AND safe_error IS NULL)
           OR (status='failed' AND safe_error IS NOT NULL))))
);
CREATE INDEX fixed_execution_receipt_queued ON metadata.execution_receipt(created_at,id) WHERE status='queued';
CREATE INDEX fixed_execution_receipt_active ON metadata.execution_receipt(status,deadline_at) WHERE status IN ('executing','unknown');

CREATE FUNCTION metadata.guard_execution_receipt() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP='INSERT' THEN
        IF NEW.status<>'queued' OR NEW.worker_id IS NOT NULL OR NEW.worker_fence<>0
           OR NEW.started_at IS NOT NULL OR NEW.finished_at IS NOT NULL OR NEW.result_json IS NOT NULL
           OR NEW.safe_error IS NOT NULL OR NEW.reconciliation_evidence IS NOT NULL OR NEW.worker_lease_expires_at IS NOT NULL
           OR NEW.deadline_at<=NEW.created_at THEN
            RAISE EXCEPTION 'receipt must start as an undispatched queue entry' USING ERRCODE='55000';
        END IF;
        RETURN NEW;
    END IF;
    IF TG_OP='DELETE' THEN
        RAISE EXCEPTION 'execution receipt tombstones must be retained' USING ERRCODE='55000';
    END IF;
    IF (to_jsonb(NEW)-ARRAY['status','worker_id','worker_fence','worker_lease_expires_at','started_at','finished_at','result_json','safe_error','reconciliation_evidence'])
       IS DISTINCT FROM
       (to_jsonb(OLD)-ARRAY['status','worker_id','worker_fence','worker_lease_expires_at','started_at','finished_at','result_json','safe_error','reconciliation_evidence']) THEN
        RAISE EXCEPTION 'execution receipt source and payload are immutable' USING ERRCODE='55000';
    END IF;
    IF OLD.status<>'queued' AND ROW(NEW.worker_id,NEW.worker_fence,NEW.started_at)
       IS DISTINCT FROM ROW(OLD.worker_id,OLD.worker_fence,OLD.started_at) THEN
        RAISE EXCEPTION 'execution worker cannot be replaced after dispatch' USING ERRCODE='55000';
    END IF;
    IF OLD.status='queued' AND NEW.status='executing'
       AND (NEW.worker_fence<>OLD.worker_fence+1 OR NEW.worker_id IS NULL OR length(NEW.worker_id)=0
           OR NEW.worker_lease_expires_at IS NULL OR NEW.worker_lease_expires_at<=clock_timestamp()
           OR NEW.deadline_at<=clock_timestamp()) THEN
        RAISE EXCEPTION 'execution claim needs a new worker fence' USING ERRCODE='55000';
    END IF;
    IF OLD.status='queued' AND (NEW.status NOT IN ('queued','executing','failed')
       OR (NEW.status='queued' AND (NEW.worker_id IS NOT NULL OR NEW.worker_fence<>0
           OR NEW.started_at IS NOT NULL OR NEW.finished_at IS NOT NULL OR NEW.result_json IS NOT NULL
           OR NEW.worker_lease_expires_at IS NOT NULL))) THEN
        RAISE EXCEPTION 'queued execution has not dispatched' USING ERRCODE='55000';
    END IF;
    IF OLD.status IN ('succeeded','failed') AND NEW IS DISTINCT FROM OLD THEN
        RAISE EXCEPTION 'terminal execution receipt is immutable' USING ERRCODE='55000';
    END IF;
    IF OLD.status='executing' AND NEW.status='queued' THEN
        RAISE EXCEPTION 'dispatched execution cannot be requeued' USING ERRCODE='55000';
    END IF;
    IF OLD.status='executing' AND (NEW.status IN ('succeeded','failed') OR NEW.worker_lease_expires_at IS DISTINCT FROM OLD.worker_lease_expires_at)
       AND (OLD.worker_lease_expires_at IS NULL OR OLD.worker_lease_expires_at<=clock_timestamp()) THEN
        RAISE EXCEPTION 'expired execution worker is fenced' USING ERRCODE='55000';
    END IF;
    IF OLD.status='unknown' AND NEW.worker_lease_expires_at IS DISTINCT FROM OLD.worker_lease_expires_at THEN
        RAISE EXCEPTION 'unknown execution cannot renew worker lease' USING ERRCODE='55000';
    END IF;
    IF OLD.status='unknown' THEN
        IF NEW.status IN ('queued','executing') THEN
            RAISE EXCEPTION 'unknown execution cannot be replayed' USING ERRCODE='55000';
        END IF;
        IF NEW.status IN ('succeeded','failed') AND
           (NEW.reconciliation_evidence IS NULL OR length(btrim(NEW.reconciliation_evidence))<16 OR NEW.finished_at IS NULL) THEN
            RAISE EXCEPTION 'unknown execution requires explicit reconciliation evidence' USING ERRCODE='55000';
        END IF;
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER fixed_execution_receipt_guard BEFORE INSERT OR UPDATE OR DELETE ON metadata.execution_receipt
FOR EACH ROW EXECUTE FUNCTION metadata.guard_execution_receipt();
