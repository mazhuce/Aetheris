-- Git discovery and audit evidence is append-only. Corrections must be recorded
-- as a new synchronization/status event instead of rewriting history.

CREATE OR REPLACE FUNCTION reject_git_source_history_mutation()
RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION 'git source history is append-only';
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER source_revision_immutable
    BEFORE UPDATE OR DELETE ON source_revision
    FOR EACH ROW EXECUTE FUNCTION reject_git_source_history_mutation();

CREATE TRIGGER source_repository_event_immutable
    BEFORE UPDATE OR DELETE ON source_repository_event
    FOR EACH ROW EXECUTE FUNCTION reject_git_source_history_mutation();
