-- R2 MySQL CDC template. Requires source-mysql in the isolated R1 profile.
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
DROP TABLE IF EXISTS bronze.r1_orders_mysql_cdc;
CREATE TABLE IF NOT EXISTS bronze.r1_orders_mysql_cdc (
  id BIGINT,
  category STRING,
  amount DECIMAL(18, 2),
  event_time TIMESTAMP(3),
  PRIMARY KEY (id) NOT ENFORCED
) WITH (
  'format-version' = '2',
  'write.upsert.enabled' = 'true'
);
CREATE TEMPORARY TABLE r1_source_orders_mysql_cdc (
  id BIGINT,
  category STRING,
  amount DECIMAL(18, 2),
  event_time TIMESTAMP(3),
  PRIMARY KEY (id) NOT ENFORCED
) WITH (
  'connector' = 'mysql-cdc',
  'hostname' = 'source-mysql',
  'port' = '3306',
  'username' = 'r1_mysql_fixture',
  'password' = '${R1_SOURCE_MYSQL_PASSWORD}',
  'database-name' = 'r1_mysql_source',
  'table-name' = 'source_orders',
  'server-id' = '5401-5404',
  'scan.startup.mode' = 'initial',
  'server-time-zone' = 'UTC'
);
INSERT INTO bronze.r1_orders_mysql_cdc SELECT id, category, amount, event_time FROM r1_source_orders_mysql_cdc;
