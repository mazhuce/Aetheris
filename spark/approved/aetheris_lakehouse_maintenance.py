#!/usr/bin/env python3
"""Managed Iceberg maintenance entrypoint.

The workflow service supplies an already validated, single Spark/Iceberg
statement.  This file intentionally does not build SQL from user input: the
runner validates the operation/statement contract and this process executes
only that statement, then writes a bounded machine-readable result.
"""

from __future__ import annotations

import argparse
import json
import os
import re
from pathlib import Path

from pyspark.sql import SparkSession


OPERATIONS = {
    "expire_snapshots", "rewrite_data_files", "remove_orphan_files",
    "storage_estimate", "time_travel", "rollback",
}
SCHEMA_OPERATIONS = {"add_column", "widen_type", "rename_column", "drop_column", "comment"}
SCHEMA_IDENTIFIER = r"`[A-Za-z_][A-Za-z0-9_]{0,127}`"
SCHEMA_TABLE = rf"(?:{SCHEMA_IDENTIFIER}\.){{1,7}}{SCHEMA_IDENTIFIER}"
SCHEMA_TYPE = r"(?:BOOLEAN|TINYINT|SMALLINT|INT|INTEGER|BIGINT|FLOAT|DOUBLE|STRING|DATE|TIMESTAMP|BINARY|UUID|DECIMAL\s*\(\s*\d{1,2}\s*,\s*\d{1,2}\s*\))"


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--biz-date", required=True)
    parser.add_argument("--attempt-id", required=True)
    parser.add_argument("--maintenance-operation", default="")
    parser.add_argument("--maintenance-statement", default="")
    parser.add_argument("--schema-change-operation")
    parser.add_argument("--schema-change-statement")
    args = parser.parse_args()
    schema_mode = args.schema_change_operation is not None
    if schema_mode and args.maintenance_operation != "":
        raise SystemExit("maintenance and schema change modes are mutually exclusive")
    if not schema_mode and args.maintenance_operation not in OPERATIONS:
        raise SystemExit("unsupported maintenance operation")
    if schema_mode and args.schema_change_operation not in SCHEMA_OPERATIONS:
        raise SystemExit("unsupported schema change operation")
    statement = args.schema_change_statement if schema_mode else args.maintenance_statement
    if not statement:
        raise SystemExit("execution statement is required")
    upper = statement.lstrip().upper()
    if ";" in statement or "\x00" in statement or "--" in statement or "/*" in statement:
        raise SystemExit("unsafe maintenance statement")
    if schema_mode:
        identifier = SCHEMA_IDENTIFIER
        table = SCHEMA_TABLE
        patterns = {
            "add_column": rf"ALTER\s+TABLE\s+{table}\s+ADD\s+COLUMN\s+{identifier}\s+{SCHEMA_TYPE}",
            "widen_type": rf"ALTER\s+TABLE\s+{table}\s+ALTER\s+COLUMN\s+{identifier}\s+TYPE\s+{SCHEMA_TYPE}",
            "rename_column": rf"ALTER\s+TABLE\s+{table}\s+RENAME\s+COLUMN\s+{identifier}\s+TO\s+{identifier}",
            "drop_column": rf"ALTER\s+TABLE\s+{table}\s+DROP\s+COLUMN\s+{identifier}",
            "comment": rf"ALTER\s+TABLE\s+{table}\s+ALTER\s+COLUMN\s+{identifier}\s+COMMENT\s+'(?:''|[^'\r\n]){{1,512}}'",
        }
        if not re.fullmatch(patterns[args.schema_change_operation], statement, re.IGNORECASE | re.DOTALL):
            raise SystemExit("schema change operation does not match statement")
    read_only = args.maintenance_operation in {"storage_estimate", "time_travel"} if not schema_mode else False
    if schema_mode and not upper.startswith("ALTER TABLE "):
        raise SystemExit("schema change must use ALTER TABLE")
    if not schema_mode and read_only and not upper.startswith("SELECT "):
        raise SystemExit("read-only maintenance must use SELECT")
    if not schema_mode and not read_only and not upper.startswith("CALL "):
        raise SystemExit("mutating maintenance must use CALL")
    if not schema_mode and not read_only:
        procedure = {
            "expire_snapshots": "EXPIRE_SNAPSHOTS",
            "rewrite_data_files": "REWRITE_DATA_FILES",
            "remove_orphan_files": "REMOVE_ORPHAN_FILES",
            "rollback": "ROLLBACK_TO_SNAPSHOT",
        }[args.maintenance_operation]
        if not re.fullmatch(
                r"CALL\s+[A-Za-z0-9_:.\-]+\.SYSTEM\." + procedure + r"\s*\(.*",
                statement, re.IGNORECASE | re.DOTALL):
            raise SystemExit("maintenance operation does not match statement")

    spark = SparkSession.builder.appName(
            f"aetheris-lakehouse-{('schema-change' if schema_mode else 'maintenance')}-{args.attempt_id}"
    ).getOrCreate()
    try:
        rows = [row.asDict(recursive=True) for row in spark.sql(statement).limit(1000).collect()]
        result = {
            "operation": args.schema_change_operation if schema_mode else args.maintenance_operation,
            "bizDate": args.biz_date,
            "attemptId": args.attempt_id,
            "rowCount": len(rows),
            "rows": rows,
        }
        result_path = os.environ.get("AETHERIS_JOB_RESULT_PATH")
        if result_path:
            path = Path(result_path)
            path.write_text(json.dumps(result, ensure_ascii=False, default=str), encoding="utf-8")
        return 0
    finally:
        spark.stop()


if __name__ == "__main__":
    raise SystemExit(main())
