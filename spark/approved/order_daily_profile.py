#!/usr/bin/env python3
"""Approved M2 PySpark job: write one isolated daily validation record.

The source and destination are intentionally fixed. Runtime users may only choose
the business date; they cannot inject Python, SQL, paths, Spark configuration or
credentials through parameters.
"""

from __future__ import annotations

import argparse
import json
import os
import re
from datetime import datetime, timezone
from pathlib import Path

from pyspark.sql import SparkSession


DATE = re.compile(r"^\d{4}-\d{2}-\d{2}$")
SOURCE_TABLE = "hive_prod.dwd.dwd_order"
OUTPUT_ROOT = "hdfs://localhost:9000/user/aetheris/managed/order_daily_profile"


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    parser.add_argument("--biz-date", required=True)
    parser.add_argument("--attempt-id", required=True)
    args = parser.parse_args()
    if not DATE.fullmatch(args.biz_date):
        parser.error("biz-date must use yyyy-MM-dd")
    if not args.attempt_id.isdecimal():
        parser.error("attempt-id must be numeric")
    return args


def write_result(payload: dict[str, object]) -> None:
    result_path = os.environ.get("AETHERIS_JOB_RESULT_PATH")
    if not result_path:
        raise RuntimeError("runner did not provide a result path")
    destination = Path(result_path)
    temporary = destination.with_suffix(".tmp")
    temporary.write_text(json.dumps(payload, ensure_ascii=False), encoding="utf-8")
    temporary.replace(destination)


def main() -> None:
    args = parse_args()
    spark = (
        SparkSession.builder.appName(f"aetheris-order-profile-{args.attempt_id}")
        .getOrCreate()
    )
    application_id = spark.sparkContext.applicationId
    output_uri = f"{OUTPUT_ROOT}/biz_date={args.biz_date}"
    try:
        source_rows = spark.table(SOURCE_TABLE).count()
        row = spark.createDataFrame(
            [(args.biz_date, source_rows, datetime.now(timezone.utc))],
            "biz_date string, source_row_count long, generated_at timestamp",
        )
        row.write.mode("overwrite").parquet(output_uri)
        write_result(
            {
                "status": "succeeded",
                "applicationId": application_id,
                "affectedRows": 1,
                "sourceRowCount": source_rows,
                "outputUri": output_uri,
            }
        )
    except Exception as error:
        write_result(
            {
                "status": "failed",
                "applicationId": application_id,
                "errorMessage": type(error).__name__,
            }
        )
        raise
    finally:
        spark.stop()


if __name__ == "__main__":
    main()
