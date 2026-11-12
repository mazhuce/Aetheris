-- M3/M4 学习域扩展：项目、内容审核、MCP 审批、AI 导师对话、过程事件。
-- 全部为增量迁移，不改变 M2 已发布任务的判分行为。

CREATE TABLE IF NOT EXISTS learning.project (
    id           BIGSERIAL PRIMARY KEY,
    code         VARCHAR(64) NOT NULL UNIQUE,
    title        VARCHAR(200) NOT NULL,
    summary      TEXT,
    level        VARCHAR(16) NOT NULL,
    scenario     TEXT,
    deliverables JSONB NOT NULL DEFAULT '[]'::jsonb,
    status       VARCHAR(24) NOT NULL DEFAULT 'published',
    order_no     INTEGER NOT NULL DEFAULT 0,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);

ALTER TABLE learning.lab
    ADD COLUMN IF NOT EXISTS project_code VARCHAR(64) REFERENCES learning.project(code) ON DELETE SET NULL,
    ADD COLUMN IF NOT EXISTS author VARCHAR(120),
    ADD COLUMN IF NOT EXISTS review_status VARCHAR(24) NOT NULL DEFAULT 'published',
    ADD COLUMN IF NOT EXISTS review_note TEXT,
    ADD COLUMN IF NOT EXISTS published_at TIMESTAMPTZ;

CREATE INDEX IF NOT EXISTS idx_lab_project ON learning.lab (project_code, order_no);
CREATE INDEX IF NOT EXISTS idx_lab_review ON learning.lab (review_status, updated_at DESC);

-- MCP 发起的高风险动作只落审批单，绝不直接执行。
CREATE TABLE IF NOT EXISTS learning.approval_request (
    id                BIGSERIAL PRIMARY KEY,
    type              VARCHAR(32) NOT NULL,
    target_code       VARCHAR(64) NOT NULL,
    payload           JSONB NOT NULL DEFAULT '{}'::jsonb,
    reason            TEXT,
    status            VARCHAR(24) NOT NULL DEFAULT 'pending',
    requested_by      BIGINT NOT NULL,
    requested_by_name VARCHAR(120) NOT NULL,
    decided_by        BIGINT,
    decided_by_name   VARCHAR(120),
    decided_at        TIMESTAMPTZ,
    decision_note     TEXT,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_approval_status ON learning.approval_request (status, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_approval_requester ON learning.approval_request (requested_by, created_at DESC);

CREATE TABLE IF NOT EXISTS learning.tutor_message (
    id           BIGSERIAL PRIMARY KEY,
    learner_uid  BIGINT NOT NULL,
    lab_code     VARCHAR(64) NOT NULL,
    role         VARCHAR(16) NOT NULL,
    content      TEXT NOT NULL,
    model        VARCHAR(64),
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_tutor_uid_lab ON learning.tutor_message (learner_uid, lab_code, created_at);

CREATE TABLE IF NOT EXISTS learning.attempt_event (
    id            BIGSERIAL PRIMARY KEY,
    submission_id BIGINT NOT NULL REFERENCES learning.submission(id) ON DELETE CASCADE,
    event_type    VARCHAR(32) NOT NULL,
    detail        VARCHAR(500),
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_attempt_event_submission ON learning.attempt_event (submission_id, created_at);
