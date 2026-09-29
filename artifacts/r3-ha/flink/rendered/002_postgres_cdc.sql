-- R2 PostgreSQL CDC template. This is an unbounded job and remains RUNNING.
-- Use this template for snapshot + WAL recovery tests after the R1 bounded gate.
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
SET 'state.savepoints.dir' = 's3://warehouse/savepoints/';
SET 'execution.checkpointing.externalized-checkpoint-retention' = 'RETAIN_ON_CANCELLATION';
SET 'restart-strategy.type' = 'fixed-delay';
SET 'restart-strategy.fixed-delay.attempts' = '3';
SET 'restart-strategy.fixed-delay.delay' = '5 s';
SET 'execution.runtime-mode' = 'streaming';
DROP TABLE IF EXISTS bronze.r1_orders_cdc;
CREATE TABLE IF NOT EXISTS bronze.r1_orders_cdc (
  id BIGINT,
  category STRING,
  amount DECIMAL(18, 2),
  event_time TIMESTAMP(3),
  PRIMARY KEY (id) NOT ENFORCED
) WITH (
  'format-version' = '2',
  -- CDC UPDATE/DELETE records must be committed as equality-key upserts.
  'write.upsert.enabled' = 'true'
);
CREATE TEMPORARY TABLE r1_source_orders_cdc (
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
  'slot.name' = 'aetheris_r1_fixture',
  'scan.startup.mode' = 'initial'
);
INSERT INTO bronze.r1_orders_cdc SELECT id, category, amount, event_time FROM r1_source_orders_cdc;
