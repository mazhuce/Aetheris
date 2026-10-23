-- Preserve all historical NULLs. They cannot prove which cron binding authorized an old run.
ALTER TABLE quality.quality_check_result
    ADD COLUMN source_schedule_generation BIGINT CHECK(source_schedule_generation>0),
    ADD COLUMN source_binding_generation BIGINT CHECK(source_binding_generation>=0),
    ADD CONSTRAINT source_generation_pair CHECK (
      (source_schedule_generation IS NULL AND source_binding_generation IS NULL) OR
      (source_schedule_generation IS NOT NULL AND source_binding_generation IS NOT NULL));

-- Complements V9's approval/actor/lease/seal guards without modifying its frozen contract.
CREATE FUNCTION quality.guard_execution_source_generation() RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE current_schedule BIGINT; current_binding BIGINT;
BEGIN
    IF TG_OP='UPDATE' AND OLD.manifest_sealed_at IS NOT NULL AND
       ROW(NEW.source_schedule_generation,NEW.source_binding_generation)
       IS DISTINCT FROM ROW(OLD.source_schedule_generation,OLD.source_binding_generation) THEN
        RAISE EXCEPTION 'sealed source generation is immutable' USING ERRCODE='55000';
    END IF;
    IF NEW.manifest_sealed_at IS NULL THEN
        IF NEW.source_schedule_generation IS NOT NULL OR NEW.source_binding_generation IS NOT NULL THEN
            RAISE EXCEPTION 'source generation requires a sealed execution' USING ERRCODE='55000';
        END IF;
    ELSIF TG_OP='INSERT' OR OLD.manifest_sealed_at IS NULL THEN
        IF NEW.invocation_json->>'kind'='cron' THEN
            SELECT schedule_version,cron_binding_generation INTO current_schedule,current_binding
              FROM quality.quality_rule WHERE id=NEW.rule_id FOR SHARE;
            IF NOT FOUND OR NEW.source_schedule_generation IS NULL OR NEW.source_binding_generation IS NULL OR
               NEW.source_schedule_generation IS DISTINCT FROM current_schedule OR NEW.source_binding_generation IS DISTINCT FROM current_binding THEN
                RAISE EXCEPTION 'cron generation does not match current source' USING ERRCODE='55000';
            END IF;
        ELSIF NEW.source_schedule_generation IS NOT NULL OR NEW.source_binding_generation IS NOT NULL THEN
            RAISE EXCEPTION 'non-cron execution has no source schedule generation' USING ERRCODE='55000';
        END IF;
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER fixed_execution_source_generation_guard BEFORE INSERT OR UPDATE ON quality.quality_check_result
    FOR EACH ROW EXECUTE FUNCTION quality.guard_execution_source_generation();
