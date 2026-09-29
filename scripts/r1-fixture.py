#!/usr/bin/env python3
"""Generate and reconcile the deterministic R1 PostgreSQL -> Iceberg fixture.

The script deliberately keeps engine execution outside this process.  Flink,
Spark and Trino can each write a JSON result to the same contract, then this
tool canonicalises and compares those results.  This makes the acceptance
check reproducible in CI and avoids trusting row order or a driver's numeric
formatting.

Examples:
    python scripts/r1-fixture.py generate --output artifacts/r1-fixture
    python scripts/r1-fixture.py reconcile --fixture artifacts/r1-fixture \
        --spark-result artifacts/r1-fixture/results/spark.json \
        --trino-result artifacts/r1-fixture/results/trino.json \
        --report artifacts/r1-fixture/reconcile.json
"""

from __future__ import annotations

import argparse
import hashlib
import json
import shutil
import sys
from decimal import Decimal, InvalidOperation
from pathlib import Path
from typing import Any


SOURCE_ROWS = [
    {"id": 1001, "category": "A", "amount": "10.00", "event_time": "2026-01-01T00:00:00Z"},
    {"id": 1002, "category": "A", "amount": "20.50", "event_time": "2026-01-01T00:01:00Z"},
    {"id": 1003, "category": "B", "amount": "7.25", "event_time": "2026-01-01T00:02:00Z"},
    {"id": 1004, "category": "B", "amount": "12.75", "event_time": "2026-01-01T00:03:00Z"},
    {"id": 1005, "category": "C", "amount": "4.00", "event_time": "2026-01-01T00:04:00Z"},
]
SOURCE_COLUMNS = ("id", "category", "amount", "event_time")
AGG_COLUMNS = ("category", "order_count", "total_amount")


class FixtureError(ValueError):
    """Raised for a malformed or failed fixture contract."""


def _money(value: Any) -> Decimal:
    if isinstance(value, bool) or value is None:
        raise FixtureError(f"amount is not numeric: {value!r}")
    try:
        amount = Decimal(str(value)).quantize(Decimal("0.01"))
    except (InvalidOperation, ValueError) as error:
        raise FixtureError(f"amount is not numeric: {value!r}") from error
    if not amount.is_finite():
        raise FixtureError(f"amount is not finite: {value!r}")
    return amount


def expected_aggregate() -> list[list[Any]]:
    grouped: dict[str, tuple[int, Decimal]] = {}
    for row in SOURCE_ROWS:
        count, total = grouped.get(row["category"], (0, Decimal("0.00")))
        grouped[row["category"]] = (count + 1, total + _money(row["amount"]))
    return [
        [category, count, f"{total.quantize(Decimal('0.01')):.2f}"]
        for category, (count, total) in sorted(grouped.items())
    ]


def _digest(rows: list[list[Any]]) -> str:
    payload = json.dumps(rows, ensure_ascii=False, separators=(",", ":"), sort_keys=False)
    return hashlib.sha256(payload.encode("utf-8")).hexdigest()


def expected_contract() -> dict[str, Any]:
    rows = expected_aggregate()
    return {
        "columns": list(AGG_COLUMNS),
        "rows": rows,
        "rowCount": len(rows),
        "checksum": _digest(rows),
    }


def expected_cdc_rows() -> list[list[Any]]:
    """Expected current-state rows after the deterministic R2 mutation."""
    rows = [row.copy() for row in SOURCE_ROWS if row["id"] != 1003]
    rows = [row for row in rows if row["id"] != 1006]
    rows.append({"id": 1006, "category": "C", "amount": "6.00", "event_time": "2026-01-01T00:05:00Z"})
    rows = [row if row["id"] != 1002 else {**row, "amount": "21.50"} for row in rows]
    return [[row["id"], row["category"], f"{_money(row['amount']):.2f}"] for row in sorted(rows, key=lambda item: item["id"])]


def _sql_quote(value: str) -> str:
    return "'" + value.replace("'", "''") + "'"


def _read_env_file(path: Path) -> dict[str, str]:
    """Read the small KEY=VALUE contract used by the isolated R1 compose file."""
    values: dict[str, str] = {}
    try:
        lines = path.read_text(encoding="utf-8").splitlines()
    except OSError as error:
        raise FixtureError(f"cannot read R1 env file: {error}") from error
    for line_number, raw_line in enumerate(lines, start=1):
        line = raw_line.strip()
        if not line or line.startswith("#"):
            continue
        key, separator, value = line.partition("=")
        if not separator or not key.strip():
            raise FixtureError(f"invalid env file line {line_number}: expected KEY=VALUE")
        values[key.strip()] = value.strip().strip('"').strip("'")
    return values


def render_sql(fixture: Path, env_file: Path, output: Path | None = None) -> list[str]:
    """Render SQL Gateway templates into an ignored directory without changing templates."""
    values = _read_env_file(env_file)
    required = ("R1_SOURCE_POSTGRES_USER", "R1_SOURCE_POSTGRES_PASSWORD")
    missing = [key for key in required if not values.get(key)]
    if missing:
        raise FixtureError(f"R1 env file is missing required values: {', '.join(missing)}")
    if any("replace-with" in values[key] or "PENDING_CAPTURE" in values[key] for key in required):
        raise FixtureError("refusing to render SQL with example or pending R1 credentials")
    destination = output or (fixture / "flink" / "rendered")
    destination.mkdir(parents=True, exist_ok=True)
    replacements = {
        "${R1_SOURCE_POSTGRES_USER}": values["R1_SOURCE_POSTGRES_USER"].replace("'", "''"),
        "${R1_SOURCE_POSTGRES_PASSWORD}": values["R1_SOURCE_POSTGRES_PASSWORD"].replace("'", "''"),
        # The isolated profile may reuse the source password until dedicated
        # MySQL credentials are supplied; committed templates never contain a
        # credential value.
        "${R1_SOURCE_MYSQL_PASSWORD}": values.get("R1_SOURCE_MYSQL_PASSWORD", values["R1_SOURCE_POSTGRES_PASSWORD"]).replace("'", "''"),
    }
    rendered: list[str] = []
    for name in ("001_bronze_orders.sql", "002_postgres_cdc.sql", "003_mysql_cdc.sql", "004_r3_flink_lifecycle.sql"):
        source = fixture / "flink" / name
        if not source.is_file():
            raise FixtureError(f"missing Flink template: {source}")
        text = source.read_text(encoding="utf-8")
        for placeholder, value in replacements.items():
            text = text.replace(placeholder, value)
        target = destination / name
        target.write_text(text, encoding="utf-8")
        rendered.append(str(target))
    return rendered


def postgres_sql() -> str:
    values = ",\n".join(
        "    (%d, %s, %s, %s::timestamptz)"
        % (row["id"], _sql_quote(row["category"]), _sql_quote(row["amount"]), _sql_quote(row["event_time"]))
        for row in SOURCE_ROWS
    )
    return f"""-- Deterministic R1 source fixture. Safe to run only in an isolated source database.
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
{values};
"""


def postgres_cdc_mutation_sql() -> str:
    """Return the deterministic UPDATE/DELETE/INSERT sequence for R2."""
    return """-- R2 PostgreSQL CDC mutation fixture. Run only after the unbounded CDC job is RUNNING.
BEGIN;
UPDATE r1_fixture.source_orders SET amount = 21.50 WHERE id = 1002;
DELETE FROM r1_fixture.source_orders WHERE id = 1003;
INSERT INTO r1_fixture.source_orders (id, category, amount, event_time)
VALUES (1006, 'C', 6.00, '2026-01-01T00:05:00Z'::timestamptz);
COMMIT;
"""


def mysql_sql() -> str:
    values = ",\n".join(
        "    (%d, %s, %s, %s)"
        % (row["id"], _sql_quote(row["category"]), _sql_quote(row["amount"]), _sql_quote(row["event_time"].replace("Z", "")))
        for row in SOURCE_ROWS
    )
    return f"""-- Deterministic R2 MySQL source fixture. Safe to run only in the isolated source database.
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
{values};
"""


def mysql_cdc_mutation_sql() -> str:
    return """-- R2 MySQL CDC mutation fixture. Run after the mysql-cdc job is RUNNING.
START TRANSACTION;
UPDATE r1_mysql_source.source_orders SET amount = 21.50 WHERE id = 1002;
DELETE FROM r1_mysql_source.source_orders WHERE id = 1003;
INSERT INTO r1_mysql_source.source_orders (id, category, amount, event_time)
VALUES (1006, 'C', 6.00, '2026-01-01 00:05:00.000');
COMMIT;
"""


def flink_sql() -> str:
    return """-- R1 bounded Flink SQL template. Supply the approved JDBC and Iceberg runtime JARs.
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
SET 'state.savepoints.dir' = 's3://warehouse/savepoints/';
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
  'username' = '${R1_SOURCE_POSTGRES_USER}',
  'password' = '${R1_SOURCE_POSTGRES_PASSWORD}'
);
INSERT INTO bronze.r1_orders SELECT id, category, amount, event_time FROM r1_source_orders;
"""


def flink_cdc_sql() -> str:
    return """-- R2 PostgreSQL CDC template. This is an unbounded job and remains RUNNING.
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
  'username' = '${R1_SOURCE_POSTGRES_USER}',
  'password' = '${R1_SOURCE_POSTGRES_PASSWORD}',
  'database-name' = 'r1_source',
  'schema-name' = 'r1_fixture',
  'table-name' = 'source_orders',
  'decoding.plugin.name' = 'pgoutput',
  'slot.name' = 'aetheris_r1_fixture',
  'scan.startup.mode' = 'initial'
);
INSERT INTO bronze.r1_orders_cdc SELECT id, category, amount, event_time FROM r1_source_orders_cdc;
"""


def flink_mysql_cdc_sql() -> str:
    return """-- R2 MySQL CDC template. Requires source-mysql in the isolated R1 profile.
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
"""


def flink_lifecycle_sql() -> str:
    """Return the R3 streaming lifecycle fixture.

    The source and sink names are deliberately unique to R3. The statement
    is restartable from a savepoint without dropping the sink table first,
    which lets the acceptance runner verify pause/resume and replacement SQL
    against the same operator state.
    """
    return """-- R3 Flink lifecycle fixture. This statement remains RUNNING.
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
  'username' = '${R1_SOURCE_POSTGRES_USER}',
  'password' = '${R1_SOURCE_POSTGRES_PASSWORD}',
  'database-name' = 'r1_source',
  'schema-name' = 'r1_fixture',
  'table-name' = 'source_orders',
  'decoding.plugin.name' = 'pgoutput',
  'slot.name' = 'aetheris_r3_lifecycle',
  'scan.startup.mode' = 'initial'
);
INSERT INTO bronze.r3_orders_lifecycle
SELECT id, category, amount, event_time FROM r3_source_orders_cdc;
"""


def query_sql(engine: str) -> str:
    if engine not in {"spark", "trino"}:
        raise FixtureError(f"unsupported engine: {engine}")
    catalog = "iceberg" if engine == "trino" else "lakehouse"
    table = "bronze.r1_orders"
    return f"""-- R1 {engine} reconciliation query; export columns and rows as JSON.
SELECT category,
       COUNT(*) AS order_count,
       CAST(SUM(amount) AS DECIMAL(18, 2)) AS total_amount
FROM {catalog}.{table}
GROUP BY category
ORDER BY category;
"""


def generate(output: Path, force: bool = False) -> dict[str, Any]:
    if output.exists() and any(output.iterdir()) and not force:
        raise FixtureError(f"fixture output is not empty: {output}; use --force to replace it")
    if output.exists() and force:
        shutil.rmtree(output)
    (output / "postgres").mkdir(parents=True, exist_ok=True)
    (output / "mysql").mkdir(parents=True, exist_ok=True)
    (output / "flink").mkdir(parents=True, exist_ok=True)
    (output / "spark").mkdir(parents=True, exist_ok=True)
    (output / "trino").mkdir(parents=True, exist_ok=True)
    (output / "results").mkdir(parents=True, exist_ok=True)
    manifest = {
        "fixture": "aetheris-r1-postgres-iceberg-v1",
        "formatVersion": 2,
        "sourceTable": "r1_fixture.source_orders",
        "bronzeTable": "bronze.r1_orders",
        "sourceColumns": list(SOURCE_COLUMNS),
        "sourceRowCount": len(SOURCE_ROWS),
        "expected": expected_contract(),
        "cdcMutation": {
            "operations": ["UPDATE id=1002 amount=21.50", "DELETE id=1003", "INSERT id=1006"],
            "expectedSourceRowCount": 5,
            "expectedSourceIds": [1001, 1002, 1004, 1005, 1006],
            "expectedRows": expected_cdc_rows(),
        },
    }
    (output / "manifest.json").write_text(json.dumps(manifest, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
    (output / "postgres" / "001_source_orders.sql").write_text(postgres_sql(), encoding="utf-8")
    (output / "postgres" / "002_cdc_mutations.sql").write_text(postgres_cdc_mutation_sql(), encoding="utf-8")
    (output / "flink" / "001_bronze_orders.sql").write_text(flink_sql(), encoding="utf-8")
    (output / "flink" / "002_postgres_cdc.sql").write_text(flink_cdc_sql(), encoding="utf-8")
    (output / "flink" / "003_mysql_cdc.sql").write_text(flink_mysql_cdc_sql(), encoding="utf-8")
    (output / "flink" / "004_r3_flink_lifecycle.sql").write_text(flink_lifecycle_sql(), encoding="utf-8")
    (output / "mysql" / "001_source_orders.sql").write_text(mysql_sql(), encoding="utf-8")
    (output / "mysql" / "002_cdc_mutations.sql").write_text(mysql_cdc_mutation_sql(), encoding="utf-8")
    for engine in ("spark", "trino"):
        (output / engine / "001_reconcile.sql").write_text(query_sql(engine), encoding="utf-8")
    return manifest


def _rows_from_payload(payload: Any) -> tuple[list[str], list[list[Any]]]:
    if isinstance(payload, dict):
        columns = payload.get("columns")
        payload_rows = payload.get("rows")
        if columns is None or payload_rows is None:
            raise FixtureError("result JSON must contain columns and rows")
    elif isinstance(payload, list):
        columns = list(AGG_COLUMNS)
        payload_rows = payload
    else:
        raise FixtureError("result JSON must be an object or row array")
    if not isinstance(columns, list) or [str(column) for column in columns] != list(AGG_COLUMNS):
        raise FixtureError(f"result columns must be exactly {list(AGG_COLUMNS)}")
    if not isinstance(payload_rows, list):
        raise FixtureError("result rows must be an array")
    rows: list[list[Any]] = []
    for row in payload_rows:
        if isinstance(row, dict):
            try:
                values = [row[column] for column in AGG_COLUMNS]
            except KeyError as error:
                raise FixtureError(f"result row misses column {error.args[0]}") from error
        elif isinstance(row, list) and len(row) == len(AGG_COLUMNS):
            values = row
        else:
            raise FixtureError("each result row must be an object or three-cell array")
        category = str(values[0])
        if not category:
            raise FixtureError("category must not be empty")
        if isinstance(values[1], bool):
            raise FixtureError("order_count must be an integer")
        try:
            count = int(values[1])
        except (TypeError, ValueError) as error:
            raise FixtureError("order_count must be an integer") from error
        if count < 0 or str(values[1]).strip() not in {str(count), f"{count}.0"}:
            raise FixtureError("order_count must be a non-negative integer")
        amount = f"{_money(values[2]):.2f}"
        rows.append([category, count, amount])
    return list(AGG_COLUMNS), sorted(rows, key=lambda row: row[0])


def reconcile(fixture: Path, spark_result: Path, trino_result: Path, report: Path | None) -> dict[str, Any]:
    try:
        manifest = json.loads((fixture / "manifest.json").read_text(encoding="utf-8"))
        expected = manifest["expected"]
        expected_rows = expected["rows"]
        expected_digest = expected["checksum"]
    except (OSError, json.JSONDecodeError, KeyError, TypeError) as error:
        raise FixtureError(f"invalid fixture manifest: {error}") from error
    engines: dict[str, Any] = {}
    for engine, path in (("spark", spark_result), ("trino", trino_result)):
        try:
            # Windows PowerShell 5 emits a UTF-8 BOM for Set-Content
            # -Encoding UTF8. Engine result files are produced by those
            # acceptance scripts, so accept both BOM and non-BOM JSON.
            payload = json.loads(path.read_text(encoding="utf-8-sig"))
        except (OSError, json.JSONDecodeError) as error:
            raise FixtureError(f"cannot read {engine} result: {error}") from error
        columns, rows = _rows_from_payload(payload)
        digest = _digest(rows)
        engines[engine] = {"columns": columns, "rows": rows, "rowCount": len(rows), "checksum": digest,
                           "matchesExpected": rows == expected_rows and digest == expected_digest}
    passed = all(result["matchesExpected"] for result in engines.values())
    if engines["spark"]["checksum"] != engines["trino"]["checksum"]:
        passed = False
    result = {
        "fixture": manifest.get("fixture"),
        "bronzeTable": manifest.get("bronzeTable"),
        "status": "passed" if passed else "failed",
        "expected": expected,
        "engines": engines,
    }
    if report:
        report.parent.mkdir(parents=True, exist_ok=True)
        report.write_text(json.dumps(result, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
    return result


def _cdc_rows_from_payload(payload: Any) -> list[list[Any]]:
    """Normalize a Trino JSON row payload for the R2 current-state contract."""
    if isinstance(payload, dict):
        payload_rows = payload.get("rows")
        if payload_rows is None:
            raise FixtureError("CDC result object must contain rows")
    elif isinstance(payload, list):
        payload_rows = payload
    else:
        raise FixtureError("CDC result JSON must be an object or row array")
    rows: list[list[Any]] = []
    for item in payload_rows:
        if isinstance(item, dict):
            values = [item.get("id"), item.get("category"), item.get("amount")]
        elif isinstance(item, list) and len(item) >= 3:
            values = item[:3]
        else:
            raise FixtureError("each CDC row must contain id, category and amount")
        try:
            row_id = int(values[0])
        except (TypeError, ValueError) as error:
            raise FixtureError("CDC id must be an integer") from error
        category = str(values[1])
        if not category:
            raise FixtureError("CDC category must not be empty")
        rows.append([row_id, category, f"{_money(values[2]):.2f}"])
    return sorted(rows, key=lambda row: row[0])


def verify_cdc(
    fixture: Path,
    result_path: Path,
    report: Path | None,
    source_kind: str = "postgres",
) -> dict[str, Any]:
    try:
        manifest = json.loads((fixture / "manifest.json").read_text(encoding="utf-8"))
        expected = manifest["cdcMutation"]["expectedRows"]
        payload = json.loads(result_path.read_text(encoding="utf-8-sig"))
    except (OSError, json.JSONDecodeError, KeyError, TypeError) as error:
        raise FixtureError(f"invalid CDC result or manifest: {error}") from error
    actual = _cdc_rows_from_payload(payload)
    id_counts: dict[int, int] = {}
    for row in actual:
        id_counts[row[0]] = id_counts.get(row[0], 0) + 1
    duplicate_ids = sorted(row_id for row_id, count in id_counts.items() if count > 1)
    expected_ids = [int(row[0]) for row in expected]
    expected_unique = len(expected_ids) == len(set(expected_ids))
    table = "bronze.r1_orders_mysql_cdc" if source_kind == "mysql" else "bronze.r1_orders_cdc"
    result = {
        "fixture": manifest.get("fixture"),
        "sourceKind": source_kind,
        "bronzeTable": table,
        "status": "passed" if actual == expected and not duplicate_ids and expected_unique else "failed",
        "expectedRows": expected,
        "actualRows": actual,
        "rowCount": len(actual),
        "uniqueKeyCount": len(id_counts),
        "duplicateIds": duplicate_ids,
        "deduplication": {
            "key": "id",
            "passed": not duplicate_ids and expected_unique,
        },
    }
    if report:
        report.parent.mkdir(parents=True, exist_ok=True)
        report.write_text(json.dumps(result, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
    return result


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest="command", required=True)
    generate_parser = sub.add_parser("generate")
    generate_parser.add_argument("--output", type=Path, required=True)
    generate_parser.add_argument("--force", action="store_true")
    render_parser = sub.add_parser("render")
    render_parser.add_argument("--fixture", type=Path, required=True)
    render_parser.add_argument("--env-file", type=Path, required=True)
    render_parser.add_argument("--output", type=Path)
    reconcile_parser = sub.add_parser("reconcile")
    reconcile_parser.add_argument("--fixture", type=Path, required=True)
    reconcile_parser.add_argument("--spark-result", type=Path, required=True)
    reconcile_parser.add_argument("--trino-result", type=Path, required=True)
    reconcile_parser.add_argument("--report", type=Path)
    verify_cdc_parser = sub.add_parser("verify-cdc")
    verify_cdc_parser.add_argument("--fixture", type=Path, required=True)
    verify_cdc_parser.add_argument("--result", type=Path, required=True)
    verify_cdc_parser.add_argument("--report", type=Path)
    verify_cdc_parser.add_argument("--source-kind", choices=("postgres", "mysql"), default="postgres")
    args = parser.parse_args(argv)
    try:
        if args.command == "generate":
            manifest = generate(args.output, args.force)
            print(json.dumps({"status": "generated", "fixture": manifest["fixture"], "output": str(args.output)}, ensure_ascii=False))
            return 0
        if args.command == "render":
            rendered = render_sql(args.fixture, args.env_file, args.output)
            print(json.dumps({"status": "rendered", "files": rendered}, ensure_ascii=False))
            return 0
        if args.command == "verify-cdc":
            result = verify_cdc(args.fixture, args.result, args.report, args.source_kind)
        else:
            result = reconcile(args.fixture, args.spark_result, args.trino_result, args.report)
        print(json.dumps(result, ensure_ascii=False))
        return 0 if result["status"] == "passed" else 1
    except (FixtureError, OSError, json.JSONDecodeError) as error:
        print(f"R1 fixture failed: {error}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
