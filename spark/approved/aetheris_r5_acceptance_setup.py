#!/usr/bin/env python3
"""Create the isolated R5 Spark acceptance table and two real snapshots."""

from __future__ import annotations

import argparse
import json
import os
from pathlib import Path

from pyspark.sql import SparkSession


TABLE = "iceberg.acceptance.r5_runner_acceptance"


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--biz-date", required=True)
    parser.add_argument("--attempt-id", required=True)
    parser.parse_known_args()

    spark = SparkSession.builder.appName("aetheris-r5-acceptance-setup").getOrCreate()
    try:
        spark.sql("CREATE NAMESPACE IF NOT EXISTS iceberg.acceptance")
        spark.sql(f"DROP TABLE IF EXISTS {TABLE}")
        spark.sql(
            f"CREATE TABLE {TABLE} (id BIGINT, category STRING, amount DECIMAL(18,2)) "
            "USING iceberg TBLPROPERTIES ('format-version'='2')"
        )
        spark.sql(f"INSERT INTO {TABLE} VALUES (1, 'A', 10.00), (2, 'B', 20.00)")
        spark.sql(f"INSERT INTO {TABLE} VALUES (3, 'C', 30.00)")
        snapshots = [row.asDict(recursive=True) for row in spark.sql(f"SELECT * FROM {TABLE}.history").collect()]
        result = {
            "table": TABLE,
            "snapshotCount": len(snapshots),
            "snapshotIds": [str(row["snapshot_id"]) for row in snapshots],
            "rowCount": spark.sql(f"SELECT COUNT(*) AS count FROM {TABLE}").first()["count"],
        }
        result_path = os.environ.get("AETHERIS_JOB_RESULT_PATH")
        if result_path:
            Path(result_path).write_text(json.dumps(result, ensure_ascii=False, default=str), encoding="utf-8")
        return 0
    finally:
        spark.stop()


if __name__ == "__main__":
    raise SystemExit(main())
