-- Names remain display snapshots. Existing unbound rows are deliberately not guessed into either inbox.
ALTER TABLE system.notification
    ADD COLUMN inbox_kind VARCHAR(16),
    ADD COLUMN recipient_uid BIGINT,
    ADD COLUMN event_id UUID,
    ADD COLUMN event_sha256 CHAR(64),
    ADD COLUMN ack_uid BIGINT,
    ADD COLUMN ack_time TIMESTAMPTZ;
ALTER TABLE system.notification ADD CONSTRAINT notification_inbox_binding CHECK ((
    (inbox_kind IS NULL AND recipient_uid IS NULL AND event_id IS NULL AND event_sha256 IS NULL AND ack_uid IS NULL AND ack_time IS NULL)
 OR (inbox_kind='personal' AND recipient_uid IS NOT NULL AND recipient_uid>0 AND event_id IS NULL AND event_sha256 IS NULL AND ack_uid IS NULL AND ack_time IS NULL)
 OR (inbox_kind='operations' AND recipient='operations' AND recipient_uid IS NULL AND event_id IS NOT NULL
     AND event_sha256 IS NOT NULL AND event_sha256 ~ '^[0-9a-f]{64}$'
     AND ((is_read=false AND ack_uid IS NULL AND ack_time IS NULL)
       OR (is_read=true AND ack_uid IS NOT NULL AND ack_uid>0 AND ack_time IS NOT NULL)))
) IS TRUE);
CREATE UNIQUE INDEX notification_event_identity ON system.notification(source,event_id);
CREATE INDEX notification_personal_uid ON system.notification(recipient_uid,is_read,id DESC) WHERE inbox_kind='personal';
CREATE INDEX notification_operations_unread ON system.notification(is_read,id) WHERE inbox_kind='operations';
CREATE FUNCTION system.guard_notification_binding() RETURNS TRIGGER
LANGUAGE plpgsql SECURITY INVOKER SET search_path=pg_catalog,system AS $$
BEGIN
    IF TG_OP='INSERT' THEN
        IF NEW.inbox_kind IS NULL THEN
            RAISE EXCEPTION 'Notification inbox binding is required' USING ERRCODE='23514';
        END IF;
    ELSE
        IF ROW(NEW.inbox_kind,NEW.recipient_uid,NEW.recipient,NEW.source,NEW.source_id,NEW.event_id,NEW.event_sha256,NEW.level,NEW.title,NEW.message,NEW.link_path,NEW.created_at)
           IS DISTINCT FROM ROW(OLD.inbox_kind,OLD.recipient_uid,OLD.recipient,OLD.source,OLD.source_id,OLD.event_id,OLD.event_sha256,OLD.level,OLD.title,OLD.message,OLD.link_path,OLD.created_at) THEN
            RAISE EXCEPTION 'Notification binding and content are immutable' USING ERRCODE='23514';
        END IF;
        IF OLD.is_read AND ROW(NEW.is_read,NEW.ack_uid,NEW.ack_time) IS DISTINCT FROM ROW(OLD.is_read,OLD.ack_uid,OLD.ack_time) THEN
            RAISE EXCEPTION 'Notification acknowledgement cannot be reset' USING ERRCODE='23514';
        END IF;
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER notification_binding_guard BEFORE INSERT OR UPDATE ON system.notification
    FOR EACH ROW EXECUTE FUNCTION system.guard_notification_binding();
