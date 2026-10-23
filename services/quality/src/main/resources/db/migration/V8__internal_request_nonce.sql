-- Receiver-owned durable replay claims; no cross-service schema or runtime DDL.
-- Business execution receipts have a separate lifetime and are never deleted here.
CREATE TABLE quality.internal_request_nonce (
    caller VARCHAR(64) NOT NULL,
    nonce VARCHAR(86) NOT NULL,
    expires_at BIGINT NOT NULL CHECK (expires_at > 0),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (caller, nonce)
);
CREATE INDEX internal_request_nonce_expiry_idx ON quality.internal_request_nonce(expires_at);
