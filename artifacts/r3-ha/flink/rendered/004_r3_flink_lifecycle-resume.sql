SET 'execution.savepoint.path' = 's3://warehouse/savepoints/r3-pause/savepoint-b54d80-02986fff2143';
-- R3 Flink lifecycle fixture. This statement remains RUNNING.
-- It is intentionally savepoint-compatible and must only run in the isolated R1 profile.
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
CREATE TABLE IF NOT EXISTS bronze.r3_orders_lifecycle (
  id BIGINT,
  category STRING,
  amount DECIMAL(18, 2),
  event_time TIMESTAMP(3),
  PRIMARY KEY (id) NOT ENFORCED
) WITH (
  'format-version' = '2',
  'write.upsert.enabled' = 'true'
);
CREATE TEMPORARY TABLE r3_source_orders_cdc (
  id BIGINT,
  category STRING,
  amount DECIMAL(18, 2),
  event_time TIMESTAMP(3),
  PRIMARY KEY (id) NOT ENFORCED
) WITH (
  'connector' = 'postgres-cdc',
  'hostname' = 'source-postgres',
  'port' = '5432',
  'username' = 'r1_fixture',
  'password' = 'AetherisR1Pg20260919x7',
  'database-name' = 'r1_source',
  'schema-name' = 'r1_fixture',
  'table-name' = 'source_orders',
  'decoding.plugin.name' = 'pgoutput',
  'slot.name' = 'aetheris_r3_lifecycle',
  'scan.startup.mode' = 'initial'
);
INSERT INTO bronze.r3_orders_lifecycle
SELECT id, category, amount, event_time FROM r3_source_orders_cdc;

