-- 查询历史：即席查询执行记录，按用户隔离。
CREATE TABLE adhoc_query_history (
    id              BIGSERIAL PRIMARY KEY,
    username        VARCHAR(64)  NOT NULL,
    datasource_id   BIGINT       NOT NULL,
    datasource_name VARCHAR(255),
    sql_text        TEXT         NOT NULL,
    status          VARCHAR(16)  NOT NULL,
    row_count       BIGINT,
    duration_ms     BIGINT,
    error_message   TEXT,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX idx_query_history_username_time ON adhoc_query_history (username, created_at DESC);
