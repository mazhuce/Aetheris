-- Deterministic R2 MySQL source fixture. The official MySQL image runs this on first init.
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
    (1001, 'A', 10.00, '2026-01-01 00:00:00.000'),
    (1002, 'A', 20.50, '2026-01-01 00:01:00.000'),
    (1003, 'B', 7.25, '2026-01-01 00:02:00.000'),
    (1004, 'B', 12.75, '2026-01-01 00:03:00.000'),
    (1005, 'C', 4.00, '2026-01-01 00:04:00.000');

-- The MYSQL_USER account is created by the official image before init scripts.
GRANT REPLICATION SLAVE, REPLICATION CLIENT ON *.* TO 'r1_mysql_fixture'@'%';
FLUSH PRIVILEGES;
