-- One short-transaction claim per owned conversation. No transaction spans LLM/tool I/O.
ALTER TABLE ai.chat_conversation ADD COLUMN active_run_id UUID, ADD COLUMN run_deadline TIMESTAMPTZ;
ALTER TABLE ai.chat_conversation ADD CONSTRAINT conversation_run_pair
    CHECK ((active_run_id IS NULL) = (run_deadline IS NULL));
