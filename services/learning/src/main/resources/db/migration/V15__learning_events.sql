-- 学习漏斗事件：只记录允许的低敏事件，供运营统计使用。
CREATE TABLE IF NOT EXISTS learning.learning_event (
    id          BIGSERIAL PRIMARY KEY,
    learner_uid BIGINT       NOT NULL,
    event       VARCHAR(64)  NOT NULL,
    path_code   VARCHAR(64),
    lab_code    VARCHAR(64),
    payload     JSONB,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_learning_event_event_time
    ON learning.learning_event (event, created_at);