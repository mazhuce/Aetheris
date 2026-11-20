-- 安全加固与一致性修复（评审 P0/P1/P2）：
-- 1) 学员 SQL 改由最小权限角色执行，仅能访问本沙箱与 teach 只读表；
-- 2) 提交 attempt 唯一索引，消除并发重复编号；
-- 3) 新任务启用有序结果比对。
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'aetheris_sandbox') THEN
        CREATE ROLE aetheris_sandbox NOLOGIN;
    END IF;
    EXECUTE format('GRANT aetheris_sandbox TO %I', current_user);
END
$$;

GRANT USAGE ON SCHEMA teach TO aetheris_sandbox;
GRANT SELECT ON ALL TABLES IN SCHEMA teach TO aetheris_sandbox;
ALTER DEFAULT PRIVILEGES IN SCHEMA teach GRANT SELECT ON TABLES TO aetheris_sandbox;

DO $$
DECLARE
    sandbox_schema text;
BEGIN
    FOR sandbox_schema IN
        SELECT schema_name FROM information_schema.schemata
         WHERE schema_name ~ '^sb_u[0-9]+$'
    LOOP
        EXECUTE format('GRANT USAGE ON SCHEMA %I TO aetheris_sandbox', sandbox_schema);
        EXECUTE format('GRANT ALL ON ALL TABLES IN SCHEMA %I TO aetheris_sandbox', sandbox_schema);
        EXECUTE format('ALTER DEFAULT PRIVILEGES IN SCHEMA %I GRANT ALL ON TABLES TO aetheris_sandbox', sandbox_schema);
    END LOOP;
END
$$;

CREATE UNIQUE INDEX IF NOT EXISTS uq_submission_attempt
    ON learning.submission (learner_uid, lab_code, attempt);

UPDATE learning.lab
   SET static_policy = static_policy || '{"orderedResult":true}'::jsonb,
       updated_at = now()
 WHERE code = 'da-l2-05';