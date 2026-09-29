#!/usr/bin/env python3
"""Fail-closed validation for a real R3 HA lifecycle report."""

from __future__ import annotations

import argparse
import json
import pathlib
import sys


def fail(message: str) -> None:
    raise ValueError(message)


def validate(report_path: pathlib.Path, project: str | None = None) -> dict:
    data = json.loads(report_path.read_text(encoding="utf-8-sig"))
    if data.get("status") != "passed":
        fail(f"report status must be passed, got {data.get('status')!r}")
    if data.get("gate") != "R3-flink-lifecycle-runtime-gate":
        fail("report gate is not the R3 lifecycle runtime gate")
    phases = data.get("phases") or {}
    required = ("submit", "checkpoint", "pause", "resume", "upgrade", "networkInterruption")
    missing = []
    for name in required:
        status = (phases.get(name) or {}).get("status")
        accepted = {"passed", "completed"} if name == "checkpoint" else {"passed"}
        if status not in accepted:
            missing.append(name)
    if missing:
        fail(f"required HA phases are missing or not passed: {', '.join(missing)}")
    reconciliation = data.get("reconciliation") or {}
    for key in ("attempted", "outageInjected", "remoteUnavailableObserved", "unknownWasNotSuccess", "networkOutageInjected", "networkRecoveryObserved"):
        if reconciliation.get(key) is not True:
            fail(f"reconciliation.{key} must be true")
    mode = reconciliation.get("mode")
    if mode not in {"auto_reconciled", "manual_review_required"}:
        fail(f"reconciliation.mode must be explicit, got {mode!r}")
    if not data.get("cleanup", {}).get("completed"):
        fail("cleanup.completed must be true")
    if project and data.get("project") not in (None, project):
        fail(f"report project mismatch: expected {project!r}, got {data.get('project')!r}")
    return data


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--report", required=True, type=pathlib.Path)
    parser.add_argument("--project")
    args = parser.parse_args()
    try:
        validate(args.report, args.project)
    except (OSError, ValueError, json.JSONDecodeError) as error:
        print(f"R3 HA report validation failed: {error}", file=sys.stderr)
        return 1
    print("R3 HA runtime report validation passed")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
