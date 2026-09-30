#!/usr/bin/env python3
"""Content-level validation for the R1/R2 runtime acceptance evidence."""

from __future__ import annotations

import argparse
import hashlib
import json
import pathlib
import re
import sys


SHA256 = re.compile(r"^[0-9a-f]{64}$")


def fail(message: str) -> None:
    raise ValueError(message)


def read(path: pathlib.Path) -> dict:
    return json.loads(path.read_text(encoding="utf-8-sig"))


def validate_r1(path: pathlib.Path) -> dict:
    body = read(path)
    if body.get("status") != "passed" or body.get("gate") != "R1-empty-rebuild-hard-gate":
        fail("R1 report is not a passed empty-rebuild hard gate")
    if body.get("freshRebuild") is not True or body.get("sourceFixtureReset") is not True:
        fail("R1 report must prove fresh rebuild and source fixture reset")
    expected = body.get("expectedChecksum")
    engines = body.get("engineChecksums") or {}
    if not expected or engines.get("spark") != expected or engines.get("trino") != expected:
        fail("R1 expected checksum must match Spark and Trino checksums")
    if engines.get("flink") != "write-verified-by-gateway":
        fail("R1 Flink write evidence is missing")
    submission = body.get("flinkSubmission") or {}
    operations = submission.get("operations") or []
    if submission.get("status") != "passed" or not submission.get("statementCount") or not operations:
        fail("R1 Flink submission evidence is incomplete")
    if any(item.get("status") != "FINISHED" for item in operations):
        fail("R1 contains a non-FINISHED Flink operation")
    if not any(item.get("jobId") for item in operations):
        fail("R1 Flink job id is missing")
    artifacts = body.get("artifacts") or {}
    required_artifacts = ["bom", "connectorAllowlist", "connectorManifest", "compose", "tableContract", "fixtureManifest", "flinkSubmission"]
    if body.get("schemaVersion", 1) >= 2:
        required_artifacts.append("compatibilityMatrix")
    if any(name not in artifacts for name in required_artifacts):
        fail("R1 artifact evidence is incomplete")
    root = pathlib.Path(__file__).resolve().parents[1]
    root_resolved = root.resolve()
    for name in required_artifacts:
        evidence = artifacts[name]
        digest = evidence.get("sha256")
        if not isinstance(digest, str) or not SHA256.fullmatch(digest):
            fail(f"R1 artifact {name} has no valid SHA-256")
        raw_path = evidence.get("path")
        if not isinstance(raw_path, str) or not raw_path.strip():
            fail(f"R1 artifact {name} path is missing")
        artifact_path = pathlib.Path(raw_path.replace(chr(92), "/"))
        if artifact_path.is_absolute():
            resolved = artifact_path.resolve()
        else:
            resolved = (root / artifact_path).resolve()
        try:
            resolved.relative_to(root_resolved)
        except ValueError:
            fail(f"R1 artifact {name} path escapes repository root")
        if not resolved.is_file():
            fail(f"R1 artifact {name} file is missing: {raw_path}")
        actual = hashlib.sha256(resolved.read_bytes()).hexdigest()
        if actual != digest.lower():
            fail(f"R1 artifact {name} SHA-256 does not match file")
    return body


def validate_r2(path: pathlib.Path) -> dict:
    body = read(path)
    if body.get("status") != "passed":
        fail("R2 report is not passed")
    sources = body.get("sources") or {}
    for source_name in ("postgres", "mysql"):
        source = sources.get(source_name) or {}
        if source.get("status") != "passed":
            fail(f"R2 {source_name} source is not passed")
        if source.get("snapshotRowCount", 0) <= 0 or source.get("mutationApplied") is not True:
            fail(f"R2 {source_name} snapshot/mutation evidence is incomplete")
        checkpoint = source.get("checkpoint") or {}
        if checkpoint.get("status") != "completed":
            fail(f"R2 {source_name} checkpoint is not completed")
        restart = source.get("restart") or {}
        if restart.get("completed") is not True or not restart.get("jobIdAfterRestart"):
            fail(f"R2 {source_name} restart recovery evidence is incomplete")
        dedup = (source.get("cdcVerification") or {}).get("deduplication") or {}
        if dedup.get("passed") is not True:
            fail(f"R2 {source_name} deduplication evidence is missing")
        if (source.get("cdcVerification") or {}).get("expectedRows") != (source.get("cdcVerification") or {}).get("actualRows"):
            fail(f"R2 {source_name} expected/actual rows do not match")
    return body


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--r1", type=pathlib.Path, required=True)
    parser.add_argument("--r2", type=pathlib.Path, required=True)
    args = parser.parse_args()
    try:
        validate_r1(args.r1)
        validate_r2(args.r2)
    except (OSError, ValueError, json.JSONDecodeError) as error:
        print(f"runtime evidence validation failed: {error}", file=sys.stderr)
        return 1
    print("R1/R2 runtime evidence validation passed")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
