CREATE TABLE IF NOT EXISTS chat_conversation (
    id         BIGSERIAL PRIMARY KEY,
    title      VARCHAR(128) NOT NULL DEFAULT '新对话',
    created_at TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS chat_message (
    id              BIGSERIAL PRIMARY KEY,
    conversation_id BIGINT       NOT NULL,
    role            VARCHAR(16)  NOT NULL,
    content         TEXT         NOT NULL,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_chat_message_conversation ON chat_message (conversation_id);
