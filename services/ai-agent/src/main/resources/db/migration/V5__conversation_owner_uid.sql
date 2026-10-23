-- Legacy names (including V2's guessed admin) are display snapshots, never sufficient ownership evidence.
ALTER TABLE ai.chat_conversation ADD COLUMN owner_uid BIGINT CHECK (owner_uid > 0);
CREATE INDEX idx_chat_conversation_owner_uid
    ON ai.chat_conversation(owner_uid,updated_at DESC,id DESC) WHERE owner_uid IS NOT NULL;

-- New records require verified UID ownership. Runtime cannot rebind an existing or legacy conversation.
CREATE FUNCTION ai.guard_chat_conversation_owner_uid() RETURNS TRIGGER
LANGUAGE plpgsql SECURITY INVOKER SET search_path = pg_catalog, ai AS $$
BEGIN
    IF TG_OP = 'INSERT' THEN
        IF NEW.owner_uid IS NULL THEN
            RAISE EXCEPTION 'Chat owner UID is required' USING ERRCODE = '23514';
        END IF;
    ELSIF NEW.owner_uid IS DISTINCT FROM OLD.owner_uid THEN
        RAISE EXCEPTION 'Chat owner UID is immutable' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER chat_conversation_owner_uid_guard BEFORE INSERT OR UPDATE ON ai.chat_conversation
    FOR EACH ROW EXECUTE FUNCTION ai.guard_chat_conversation_owner_uid();
