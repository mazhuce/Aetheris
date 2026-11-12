-- 学习域：课程目录、实验、沙箱、提交、判分、进度与凭证。
-- 所有表落在 learning schema；教学数据在独立的 teach schema（见 V2）。

CREATE TABLE IF NOT EXISTS learning.learning_path (
    id              BIGSERIAL PRIMARY KEY,
    code            VARCHAR(64)  NOT NULL UNIQUE,
    title           VARCHAR(160) NOT NULL,
    description     TEXT,
    target_role     VARCHAR(120),
    level           VARCHAR(16)  NOT NULL,
    version         INTEGER      NOT NULL DEFAULT 1,
    status          VARCHAR(24)  NOT NULL DEFAULT 'published',
    estimated_hours INTEGER      NOT NULL DEFAULT 0,
    order_no        INTEGER      NOT NULL DEFAULT 0,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS learning.skill_node (
    id               BIGSERIAL PRIMARY KEY,
    path_code        VARCHAR(64) NOT NULL REFERENCES learning.learning_path(code) ON DELETE CASCADE,
    code             VARCHAR(64) NOT NULL UNIQUE,
    parent_code      VARCHAR(64),
    title            VARCHAR(160) NOT NULL,
    level            VARCHAR(16) NOT NULL,
    knowledge_points JSONB NOT NULL DEFAULT '[]'::jsonb,
    outcome          TEXT,
    order_no         INTEGER NOT NULL DEFAULT 0
);

CREATE TABLE IF NOT EXISTS learning.course (
    id          BIGSERIAL PRIMARY KEY,
    path_code   VARCHAR(64) NOT NULL REFERENCES learning.learning_path(code) ON DELETE CASCADE,
    code        VARCHAR(64) NOT NULL UNIQUE,
    title       VARCHAR(160) NOT NULL,
    summary     TEXT,
    version     INTEGER NOT NULL DEFAULT 1,
    status      VARCHAR(24) NOT NULL DEFAULT 'published',
    author      VARCHAR(120),
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS learning.course_module (
    id          BIGSERIAL PRIMARY KEY,
    course_code VARCHAR(64) NOT NULL REFERENCES learning.course(code) ON DELETE CASCADE,
    code        VARCHAR(64) NOT NULL UNIQUE,
    title       VARCHAR(160) NOT NULL,
    objective   TEXT,
    level       VARCHAR(16) NOT NULL,
    order_no    INTEGER NOT NULL DEFAULT 0
);

CREATE TABLE IF NOT EXISTS learning.lab (
    id                BIGSERIAL PRIMARY KEY,
    module_code       VARCHAR(64) NOT NULL REFERENCES learning.course_module(code) ON DELETE CASCADE,
    code              VARCHAR(64) NOT NULL UNIQUE,
    title             VARCHAR(200) NOT NULL,
    level             VARCHAR(16) NOT NULL,
    difficulty        VARCHAR(16) NOT NULL DEFAULT 'easy',
    task_type         VARCHAR(32) NOT NULL DEFAULT 'SQL',
    summary           TEXT,
    scenario          TEXT,
    objective         TEXT,
    steps             JSONB NOT NULL DEFAULT '[]'::jsonb,
    table_refs        JSONB NOT NULL DEFAULT '[]'::jsonb,
    starter_sql       TEXT,
    expected_sql      TEXT,
    static_policy     JSONB NOT NULL DEFAULT '{}'::jsonb,
    rubric            JSONB NOT NULL DEFAULT '[]'::jsonb,
    pass_score        INTEGER NOT NULL DEFAULT 70,
    estimated_minutes INTEGER NOT NULL DEFAULT 20,
    status            VARCHAR(24) NOT NULL DEFAULT 'published',
    version           INTEGER NOT NULL DEFAULT 1,
    order_no          INTEGER NOT NULL DEFAULT 0,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS learning.lab_skill (
    lab_code   VARCHAR(64) NOT NULL REFERENCES learning.lab(code) ON DELETE CASCADE,
    skill_code VARCHAR(64) NOT NULL REFERENCES learning.skill_node(code) ON DELETE CASCADE,
    weight     INTEGER NOT NULL DEFAULT 100,
    PRIMARY KEY (lab_code, skill_code)
);

CREATE TABLE IF NOT EXISTS learning.sandbox_instance (    id            BIGSERIAL PRIMARY KEY,
    learner_uid   BIGINT NOT NULL,
    learner_name  VARCHAR(120) NOT NULL,
    lab_code      VARCHAR(64) NOT NULL,
    schema_name   VARCHAR(63) NOT NULL UNIQUE,
    status        VARCHAR(24) NOT NULL DEFAULT 'active',
    quota_cpu     NUMERIC(6,2) NOT NULL DEFAULT 1.0,
    quota_mem_mb  INTEGER NOT NULL DEFAULT 512,
    expires_at    TIMESTAMPTZ NOT NULL,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_reset_at TIMESTAMPTZ,
    dropped_at    TIMESTAMPTZ
);
CREATE INDEX IF NOT EXISTS idx_sandbox_uid ON learning.sandbox_instance (learner_uid, status);
CREATE INDEX IF NOT EXISTS idx_sandbox_expiry ON learning.sandbox_instance (status, expires_at);
CREATE UNIQUE INDEX IF NOT EXISTS uq_sandbox_active_uid
    ON learning.sandbox_instance (learner_uid) WHERE status = 'active';

CREATE TABLE IF NOT EXISTS learning.submission (
    id            BIGSERIAL PRIMARY KEY,
    learner_uid   BIGINT NOT NULL,
    learner_name  VARCHAR(120) NOT NULL,
    lab_code      VARCHAR(64) NOT NULL,
    lab_version   INTEGER NOT NULL,
    attempt       INTEGER NOT NULL DEFAULT 1,
    sql_text      TEXT NOT NULL,
    notes         TEXT,
    status        VARCHAR(24) NOT NULL DEFAULT 'grading',
    score         INTEGER,
    passed        BOOLEAN,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    graded_at     TIMESTAMPTZ
);
CREATE INDEX IF NOT EXISTS idx_submission_uid_lab ON learning.submission (learner_uid, lab_code, created_at DESC);

CREATE TABLE IF NOT EXISTS learning.grading_result (
    id            BIGSERIAL PRIMARY KEY,
    submission_id BIGINT NOT NULL UNIQUE REFERENCES learning.submission(id) ON DELETE CASCADE,
    score         INTEGER NOT NULL,
    passed        BOOLEAN NOT NULL,
    summary       TEXT,
    evidence      JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS learning.grading_item (
    id                BIGSERIAL PRIMARY KEY,
    grading_result_id BIGINT NOT NULL REFERENCES learning.grading_result(id) ON DELETE CASCADE,
    item_key          VARCHAR(64) NOT NULL,
    name              VARCHAR(160) NOT NULL,
    weight            INTEGER NOT NULL,
    passed            BOOLEAN NOT NULL,
    detail            TEXT,
    evidence          JSONB NOT NULL DEFAULT '{}'::jsonb
);
CREATE INDEX IF NOT EXISTS idx_grading_item_result ON learning.grading_item (grading_result_id);

CREATE TABLE IF NOT EXISTS learning.execution_evidence (
    id               BIGSERIAL PRIMARY KEY,
    submission_id    BIGINT NOT NULL REFERENCES learning.submission(id) ON DELETE CASCADE,
    sandbox_schema   VARCHAR(63) NOT NULL,
    statement_hash   VARCHAR(64) NOT NULL,
    row_count        INTEGER,
    checksum         VARCHAR(64),
    duration_ms      INTEGER,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS learning.learner_skill_progress (
    id           BIGSERIAL PRIMARY KEY,
    learner_uid  BIGINT NOT NULL,
    skill_code   VARCHAR(64) NOT NULL,
    status       VARCHAR(24) NOT NULL DEFAULT 'not_started',
    best_score   INTEGER NOT NULL DEFAULT 0,
    attempts     INTEGER NOT NULL DEFAULT 0,
    last_lab_code VARCHAR(64),
    updated_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (learner_uid, skill_code)
);

CREATE TABLE IF NOT EXISTS learning.credential (
    id         BIGSERIAL PRIMARY KEY,
    learner_uid BIGINT NOT NULL,
    type       VARCHAR(32) NOT NULL,
    title      VARCHAR(200) NOT NULL,
    issued_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    evidence   JSONB NOT NULL DEFAULT '{}'::jsonb
);
CREATE INDEX IF NOT EXISTS idx_credential_uid ON learning.credential (learner_uid, issued_at DESC);

CREATE TABLE IF NOT EXISTS learning.tutor_hint_log (
    id         BIGSERIAL PRIMARY KEY,
    learner_uid BIGINT NOT NULL,
    lab_code   VARCHAR(64) NOT NULL,
    level      INTEGER NOT NULL,
    reason     VARCHAR(120),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_hint_uid_lab ON learning.tutor_hint_log (learner_uid, lab_code, created_at DESC);
