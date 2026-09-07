-- Dedicated M0 fixture; owner=5b8e553a2067a4eb. Run with beeline --force=false.
-- Existing database is an error: do not add IF NOT EXISTS or reuse business data.
CREATE DATABASE aetheris_smoke_5b8e553a2067a4eb COMMENT 'Aetheris M0 isolated smoke fixture 5b8e553a2067a4eb';
CREATE TABLE aetheris_smoke_5b8e553a2067a4eb.source_orders (id INT, category STRING, amount INT)
STORED AS PARQUET TBLPROPERTIES ('aetheris.smoke.owner'='5b8e553a2067a4eb');
INSERT INTO aetheris_smoke_5b8e553a2067a4eb.source_orders VALUES (1, 'A', 10), (2, 'A', 20), (3, 'B', 7);
CREATE TABLE aetheris_smoke_5b8e553a2067a4eb.target_totals (category STRING, order_count BIGINT, total_amount BIGINT)
PARTITIONED BY (run_id STRING) STORED AS PARQUET
TBLPROPERTIES ('aetheris.smoke.owner'='5b8e553a2067a4eb');
