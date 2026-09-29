-- R1 bounded Flink SQL template. Supply the approved JDBC and Iceberg runtime JARs.
-- Run from the Flink SQL Gateway after the R1 catalog is reachable.
CREATE CATALOG lakehouse WITH (
  'type' = 'iceberg',
  'catalog-type' = 'rest',
  'uri' = 'http://catalog:8181',
  'warehouse' = 's3://warehouse/',
  's3.endpoint' = 'http://object-store:9000',
  's3.path-style-access' = 'true',
  's3.region' = 'us-east-1'
);
USE CATALOG lakehouse;
CREATE DATABASE IF NOT EXISTS bronze;
SET 'execution.checkpointing.interval' = '1 s';
SET 'execution.checkpointing.storage' = 'filesystem';
SET 'state.checkpoints.dir' = 's3://warehouse/checkpoints/';
SET 'execution.checkpointing.savepoint-dir' = 's3://warehouse/savepoints/';
SET 'execution.checkpointing.externalized-checkpoint-retention' = 'RETAIN_ON_CANCELLATION';
SET 'restart-strategy.type' = 'fixed-delay';
SET 'restart-strategy.fixed-delay.attempts' = '3';
SET 'restart-strategy.fixed-delay.delay' = '5 s';
SET 'execution.runtime-mode' = 'streaming';
SET 'pipeline.operator-chaining' = 'false';
DROP TABLE IF EXISTS bronze.r1_orders;
CREATE TABLE IF NOT EXISTS bronze.r1_orders (
  id BIGINT,
  category STRING,
  amount DECIMAL(18, 2),
  event_time TIMESTAMP(3)
) WITH ('format-version' = '2');

-- The JDBC source is bounded, so the INSERT reaches FINISHED and can be used
-- for the R1 cross-engine read/rebuild gate. Replace the placeholders using
-- the secret manager; do not commit credentials to a fixture file.
CREATE TEMPORARY TABLE r1_source_orders (
  id BIGINT,
  category STRING,
  amount DECIMAL(18, 2),
  event_time TIMESTAMP(3)
) WITH (
  'connector' = 'jdbc',
  'url' = 'jdbc:postgresql://source-postgres:5432/r1_source',
  'table-name' = 'r1_fixture.source_orders',
  'username' = 'r1_fixture',
  'password' = 'AetherisR1Pg20260919x7'
);
INSERT INTO bronze.r1_orders SELECT id, category, amount, event_time FROM r1_source_orders;
