ALTER TABLE chat_conversation
    ADD COLUMN IF NOT EXISTS owner_username VARCHAR(64);

UPDATE chat_conversation
SET owner_username = 'admin'
WHERE owner_username IS NULL;

ALTER TABLE chat_conversation
    ALTER COLUMN owner_username SET NOT NULL;

CREATE INDEX IF NOT EXISTS idx_chat_conversation_owner
    ON chat_conversation (owner_username, updated_at DESC);
