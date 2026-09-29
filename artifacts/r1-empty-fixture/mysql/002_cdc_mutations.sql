-- R2 MySQL CDC mutation fixture. Run after the mysql-cdc job is RUNNING.
START TRANSACTION;
UPDATE r1_mysql_source.source_orders SET amount = 21.50 WHERE id = 1002;
DELETE FROM r1_mysql_source.source_orders WHERE id = 1003;
INSERT INTO r1_mysql_source.source_orders (id, category, amount, event_time)
VALUES (1006, 'C', 6.00, '2026-01-01 00:05:00.000');
COMMIT;
