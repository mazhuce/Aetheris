#!/usr/bin/env python3
"""Verify the isolated R5 table after maintenance/schema operations."""

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
    spark = SparkSession.builder.appName("aetheris-r5-acceptance-verify").getOrCreate()
    try:
        columns = [row["col_name"] for row in spark.sql(f"DESCRIBE TABLE {TABLE}").collect()]
        result = {
            "table": TABLE,
            "hasIngestedAt": "ingested_at" in columns,
            "rowCount": spark.sql(f"SELECT COUNT(*) AS count FROM {TABLE}").first()["count"],
            "columns": columns,
        }
        result_path = os.environ.get("AETHERIS_JOB_RESULT_PATH")
        if result_path:
            Path(result_path).write_text(json.dumps(result, ensure_ascii=False, default=str), encoding="utf-8")
        return 0
    finally:
        spark.stop()


if __name__ == "__main__":
    raise SystemExit(main())
