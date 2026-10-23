ALTER TABLE quality.quality_check_result
    ADD COLUMN lease_owner VARCHAR(64),
    ADD COLUMN lease_expires_at TIMESTAMPTZ NOT NULL DEFAULT (clock_timestamp() + INTERVAL '120 seconds'),
    ADD COLUMN execution_snapshot TEXT NOT NULL DEFAULT '{}';
UPDATE quality.quality_check_result SET status='unknown', failure_code='UPGRADE_RECONCILIATION_REQUIRED',
    message='升级前检查结果待核实；禁止自动重放', finished_at=clock_timestamp()
    WHERE status='running';
DROP INDEX quality.uq_quality_result_active_rule;
CREATE UNIQUE INDEX uq_quality_result_active_rule ON quality.quality_check_result(rule_id)
    WHERE status IN ('running','unknown');
CREATE INDEX quality_result_lease_expiry ON quality.quality_check_result(lease_expires_at) WHERE status='running';

CREATE TABLE quality.execution_reconciliation_audit (
    id BIGSERIAL PRIMARY KEY,
    run_id BIGINT NOT NULL, rule_id BIGINT NOT NULL,
    previous_status VARCHAR(16) NOT NULL, resolved_status VARCHAR(16) NOT NULL,
    actor VARCHAR(128) NOT NULL, evidence TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp()
);
INSERT INTO quality.execution_reconciliation_audit(run_id,rule_id,previous_status,resolved_status,actor,evidence)
SELECT id,rule_id,'running','unknown','system:migration','升级前检查结果待核实；禁止自动重放'
FROM quality.quality_check_result WHERE failure_code='UPGRADE_RECONCILIATION_REQUIRED';
UPDATE quality.quality_rule rule SET last_check_status='unknown',last_violation_count=NULL,
    last_error='升级前检查结果待核实；禁止自动重放'
FROM quality.quality_check_result result WHERE result.rule_id=rule.id AND result.failure_code='UPGRADE_RECONCILIATION_REQUIRED';

CREATE FUNCTION quality.block_unresolved_rule_delete() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    IF EXISTS (SELECT 1 FROM quality.quality_check_result WHERE rule_id=OLD.id AND status IN ('running','unknown')) THEN
        RAISE EXCEPTION '仍有执行中或待对账检查，禁止删除质量规则' USING ERRCODE='55000';
    END IF;
    RETURN OLD;
END $$;
CREATE TRIGGER block_unresolved_rule_delete BEFORE DELETE ON quality.quality_rule
    FOR EACH ROW EXECUTE FUNCTION quality.block_unresolved_rule_delete();
