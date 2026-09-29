-- Deterministic R1 source fixture. Safe to run only in an isolated source database.
CREATE SCHEMA IF NOT EXISTS r1_fixture;
DROP TABLE IF EXISTS r1_fixture.source_orders;
CREATE TABLE r1_fixture.source_orders (
    id BIGINT PRIMARY KEY,
    category VARCHAR(32) NOT NULL,
    amount NUMERIC(18, 2) NOT NULL,
    event_time TIMESTAMPTZ NOT NULL
);
-- Preserve the previous row image so CDC UPDATE/DELETE events can be
-- applied deterministically by the Bronze upsert sink.
ALTER TABLE r1_fixture.source_orders REPLICA IDENTITY FULL;
INSERT INTO r1_fixture.source_orders (id, category, amount, event_time) VALUES
    (1001, 'A', '10.00', '2026-01-01T00:00:00Z'::timestamptz),
    (1002, 'A', '20.50', '2026-01-01T00:01:00Z'::timestamptz),
    (1003, 'B', '7.25', '2026-01-01T00:02:00Z'::timestamptz),
    (1004, 'B', '12.75', '2026-01-01T00:03:00Z'::timestamptz),
    (1005, 'C', '4.00', '2026-01-01T00:04:00Z'::timestamptz);
