-- Usernames can be reused after deletion. Existing unproven personal ownership stays NULL.
ALTER TABLE metadata.adhoc_query_history
    ADD COLUMN owner_uid BIGINT CHECK (owner_uid > 0),
    ADD COLUMN request_id VARCHAR(128) CHECK (request_id ~ '^[A-Za-z0-9._:-]{1,128}$'),
    ADD COLUMN error_code VARCHAR(64);
CREATE INDEX query_history_uid_id ON metadata.adhoc_query_history(owner_uid,id DESC)
    WHERE owner_uid IS NOT NULL;

-- owner_uid is reserved by V31 for published-key provenance. Ordinary USER uses actor_uid.
ALTER TABLE metadata.semantic_query_audit ADD COLUMN actor_uid BIGINT CHECK (actor_uid > 0);
UPDATE metadata.semantic_query_audit SET actor_uid=owner_uid WHERE owner_uid IS NOT NULL;
ALTER TABLE metadata.semantic_query_audit ADD CONSTRAINT query_audit_published_actor
    CHECK (api_key_id IS NULL OR actor_uid=owner_uid);
CREATE INDEX query_audit_actor_time ON metadata.semantic_query_audit(actor_uid,created_at DESC)
    WHERE actor_uid IS NOT NULL;

CREATE FUNCTION metadata.guard_query_history_owner_uid() RETURNS TRIGGER
LANGUAGE plpgsql SECURITY INVOKER SET search_path = pg_catalog, metadata AS $$
BEGIN
    IF TG_OP = 'INSERT' AND NEW.owner_uid IS NULL THEN
        RAISE EXCEPTION 'Query history owner UID is required' USING ERRCODE = '23514';
    ELSIF TG_OP = 'UPDATE' AND NEW.owner_uid IS DISTINCT FROM OLD.owner_uid THEN
        RAISE EXCEPTION 'Query history owner UID is immutable' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER query_history_owner_uid_guard BEFORE INSERT OR UPDATE ON metadata.adhoc_query_history
    FOR EACH ROW EXECUTE FUNCTION metadata.guard_query_history_owner_uid();

CREATE FUNCTION metadata.guard_query_audit_actor_uid() RETURNS TRIGGER
LANGUAGE plpgsql SECURITY INVOKER SET search_path = pg_catalog, metadata AS $$
BEGIN
    IF TG_OP = 'INSERT' AND NEW.actor_uid IS NULL THEN
        RAISE EXCEPTION 'Query audit actor UID is required' USING ERRCODE = '23514';
    ELSIF TG_OP = 'UPDATE' AND (NEW.actor_uid IS DISTINCT FROM OLD.actor_uid
            OR NEW.owner_uid IS DISTINCT FROM OLD.owner_uid) THEN
        RAISE EXCEPTION 'Query audit actor UID is immutable' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER query_audit_actor_uid_guard BEFORE INSERT OR UPDATE ON metadata.semantic_query_audit
    FOR EACH ROW EXECUTE FUNCTION metadata.guard_query_audit_actor_uid();
