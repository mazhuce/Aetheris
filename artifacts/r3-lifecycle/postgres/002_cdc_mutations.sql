-- R2 PostgreSQL CDC mutation fixture. Run only after the unbounded CDC job is RUNNING.
BEGIN;
UPDATE r1_fixture.source_orders SET amount = 21.50 WHERE id = 1002;
DELETE FROM r1_fixture.source_orders WHERE id = 1003;
INSERT INTO r1_fixture.source_orders (id, category, amount, event_time)
VALUES (1006, 'C', 6.00, '2026-01-01T00:05:00Z'::timestamptz);
COMMIT;
