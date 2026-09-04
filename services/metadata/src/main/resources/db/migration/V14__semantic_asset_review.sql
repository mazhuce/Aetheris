CREATE TABLE semantic_asset_review (
    id BIGSERIAL PRIMARY KEY,
    asset_type VARCHAR(32) NOT NULL,
    asset_id BIGINT NOT NULL,
    version_no INTEGER NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'pending',
    requested_by VARCHAR(64) NOT NULL,
    reviewed_by VARCHAR(64),
    change_note VARCHAR(2000) NOT NULL,
    review_comment VARCHAR(2000),
    publish_window_start TIMESTAMPTZ,
    publish_window_end TIMESTAMPTZ,
    requested_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    reviewed_at TIMESTAMPTZ,
    published_at TIMESTAMPTZ,
    CONSTRAINT ck_semantic_review_type CHECK (asset_type IN ('logical_model','semantic_dimension','metric_definition')),
    CONSTRAINT ck_semantic_review_status CHECK (status IN ('pending','approved','rejected','withdrawn','published')),
    CONSTRAINT ck_semantic_review_window CHECK (publish_window_end IS NULL OR publish_window_start IS NULL OR publish_window_end > publish_window_start)
);
CREATE INDEX idx_semantic_review_asset ON semantic_asset_review(asset_type, asset_id, version_no, requested_at DESC);
CREATE INDEX idx_semantic_review_status ON semantic_asset_review(status, requested_at DESC);
CREATE UNIQUE INDEX uq_semantic_review_pending ON semantic_asset_review(asset_type, asset_id, version_no) WHERE status = 'pending';
