#!/usr/bin/env python3
"""Drop the isolated R5 acceptance table through the real Spark catalog."""

from __future__ import annotations

import argparse
import os
from pathlib import Path

from pyspark.sql import SparkSession


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--biz-date", required=True)
    parser.add_argument("--attempt-id", required=True)
    parser.parse_known_args()
    spark = SparkSession.builder.appName("aetheris-r5-acceptance-cleanup").getOrCreate()
    try:
        spark.sql("DROP TABLE IF EXISTS iceberg.acceptance.r5_runner_acceptance")
        result_path = os.environ.get("AETHERIS_JOB_RESULT_PATH")
        if result_path:
            Path(result_path).write_text('{"dropped":true}', encoding="utf-8")
        return 0
    finally:
        spark.stop()


if __name__ == "__main__":
    raise SystemExit(main())
