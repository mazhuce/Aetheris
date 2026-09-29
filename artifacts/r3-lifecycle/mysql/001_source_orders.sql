-- Deterministic R2 MySQL source fixture. Safe to run only in the isolated source database.
CREATE DATABASE IF NOT EXISTS r1_mysql_source;
USE r1_mysql_source;
DROP TABLE IF EXISTS source_orders;
CREATE TABLE source_orders (
    id BIGINT NOT NULL PRIMARY KEY,
    category VARCHAR(32) NOT NULL,
    amount DECIMAL(18, 2) NOT NULL,
    event_time TIMESTAMP(3) NOT NULL
);
INSERT INTO source_orders (id, category, amount, event_time) VALUES
    (1001, 'A', '10.00', '2026-01-01T00:00:00'),
    (1002, 'A', '20.50', '2026-01-01T00:01:00'),
    (1003, 'B', '7.25', '2026-01-01T00:02:00'),
    (1004, 'B', '12.75', '2026-01-01T00:03:00'),
    (1005, 'C', '4.00', '2026-01-01T00:04:00');
