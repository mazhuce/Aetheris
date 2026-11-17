-- 历史种子把 static_policy 存成了 JSON 字符串（jsonb string），
-- 守卫读到的是文本节点，导致 allowWrite/requiredColumns 等约束不生效。
-- 统一转换为 JSON 对象；无法解析的行保留并提示，不让迁移整体失败。
DO $$
DECLARE
    r RECORD;
    converted JSONB;
BEGIN
    FOR r IN SELECT id, static_policy FROM learning.lab WHERE jsonb_typeof(static_policy) = 'string' LOOP
        BEGIN
            converted := (r.static_policy #>> '{}')::jsonb;
            IF jsonb_typeof(converted) = 'object' THEN
                UPDATE learning.lab SET static_policy = converted, updated_at = now() WHERE id = r.id;
            END IF;
        EXCEPTION WHEN others THEN
            RAISE NOTICE 'skip lab %: %', r.id, SQLERRM;
        END;
    END LOOP;
END $$;