#!/usr/bin/env python3
"""Validate the reviewable, offline parts of the R1 lakehouse release gate."""

from __future__ import annotations

import argparse
import pathlib
import sys
import tomllib
from urllib.parse import urlparse


ROOT = pathlib.Path(__file__).resolve().parents[1]
BOM = ROOT / "configs" / "lakehouse" / "r1-bom.toml"
ALLOWLIST = ROOT / "configs" / "lakehouse" / "connector-allowlist.toml"


def fail(message: str) -> None:
    raise ValueError(message)


def validate(mode: str) -> list[str]:
    bom = tomllib.loads(BOM.read_text(encoding="utf-8"))
    allowlist = tomllib.loads(ALLOWLIST.read_text(encoding="utf-8"))
    warnings: list[str] = []

    if bom["meta"]["status"] not in {"candidate", "approved"}:
        fail("BOM meta.status must be candidate or approved")
    for section, key in (("runtime", "flink"), ("runtime", "iceberg"), ("runtime", "spark"), ("runtime", "trino")):
        value = str(bom[section][key])
        if not value or value.lower() in {"latest", "snapshot", "dev"}:
            fail(f"{section}.{key} must be pinned")

    if bom["acceptance"]["required_table_format"] != "iceberg-v2":
        fail("R1 requires Iceberg format v2")
    if set(bom["acceptance"]["required_engines"]) != {"flink", "spark", "trino"}:
        fail("R1 engine acceptance must cover Flink, Spark and Trino")

    for connector in allowlist["connector"]:
        if connector["license"] != "Apache-2.0":
            fail(f"unsupported license for {connector['id']}")
        parsed = urlparse(connector["source"])
        if parsed.scheme != "https" or parsed.netloc != "repo1.maven.org":
            fail(f"connector source must be Maven Central HTTPS: {connector['id']}")
        digest = connector["sha256"]
        if digest == "PENDING_CAPTURE":
            warnings.append(f"{connector['id']}: SHA-256 capture pending")
        elif len(digest) != 64 or any(ch not in "0123456789abcdef" for ch in digest.lower()):
            fail(f"invalid SHA-256 for {connector['id']}")
        if connector["status"] not in {"candidate", "approved"}:
            fail(f"invalid connector status for {connector['id']}")

    if mode == "release":
        if bom["meta"]["status"] != "approved":
            fail("release mode requires BOM meta.status=approved")
        if warnings:
            fail("release mode requires captured connector SHA-256 values")
    return warnings


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--mode", choices=("draft", "release"), default="draft")
    args = parser.parse_args()
    try:
        warnings = validate(args.mode)
    except (OSError, ValueError, KeyError, tomllib.TOMLDecodeError) as error:
        print(f"R1 validation failed: {error}", file=sys.stderr)
        return 1
    print(f"R1 {args.mode} validation passed")
    for warning in warnings:
        print(f"WARNING: {warning}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
