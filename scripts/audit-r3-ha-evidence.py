#!/usr/bin/env python3
"""Audit R3 HA evidence and distinguish historical pass from current evidence."""

from __future__ import annotations

import argparse
import datetime as dt
import json
import pathlib
import sys


REQUIRED_PHASES = ("submit", "checkpoint", "taskManagerRestart", "pause", "resume", "upgrade")


def parse_timestamp(value: str) -> dt.datetime:
    return dt.datetime.fromisoformat(value.replace("Z", "+00:00"))


def inspect(path: pathlib.Path, max_age_hours: float = 24.0, now: dt.datetime | None = None) -> dict:
    body = json.loads(path.read_text(encoding="utf-8-sig"))
    if body.get("gate") != "R3-flink-lifecycle-runtime-gate":
        raise ValueError("not an R3 lifecycle report")
    phases = body.get("phases") or {}
    missing = []
    for phase in REQUIRED_PHASES:
        status = (phases.get(phase) or {}).get("status")
        if phase == "checkpoint":
            if status != "completed":
                missing.append(phase)
        elif status != "passed":
            missing.append(phase)
    reconciliation = body.get("reconciliation") or {}
    if body.get("status") != "passed" or missing:
        return {"status": "failed", "path": str(path), "missingPhases": missing, "sourceStatus": body.get("status")}
    if not reconciliation.get("outageInjected") or not reconciliation.get("remoteUnavailableObserved"):
        return {"status": "manual_review_required", "path": str(path), "reason": "HA outage evidence is incomplete"}
    if not reconciliation.get("unknownWasNotSuccess"):
        return {"status": "failed", "path": str(path), "reason": "unknown state was not fail-closed"}
    if reconciliation.get("mode") not in {"auto_reconciled", "manual_review_required"}:
        return {"status": "manual_review_required", "path": str(path), "reason": "reconciliation mode is missing"}
    finished = body.get("finishedAtUtc")
    if not finished:
        return {"status": "manual_review_required", "path": str(path), "reason": "finishedAtUtc is missing"}
    reference = now or dt.datetime.now(dt.timezone.utc)
    age_hours = (reference - parse_timestamp(finished)).total_seconds() / 3600
    if age_hours > max_age_hours:
        return {
            "status": "manual_review_required",
            "path": str(path),
            "reason": "historical_pass_requires_rerun",
            "ageHours": round(age_hours, 2),
            "sourceStatus": "passed",
        }
    return {"status": "passed", "path": str(path), "ageHours": round(age_hours, 2), "sourceStatus": "passed"}


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--report", required=True, type=pathlib.Path)
    parser.add_argument("--max-age-hours", type=float, default=24.0)
    args = parser.parse_args()
    try:
        result = inspect(args.report, args.max_age_hours)
    except (OSError, ValueError, json.JSONDecodeError) as error:
        print(f"R3 HA evidence audit failed: {error}", file=sys.stderr)
        return 1
    print(json.dumps(result, ensure_ascii=False, separators=(",", ":")))
    return 0 if result["status"] == "passed" else 2 if result["status"] == "manual_review_required" else 1


if __name__ == "__main__":
    raise SystemExit(main())
