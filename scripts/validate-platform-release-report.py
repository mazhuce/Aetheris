#!/usr/bin/env python3
"""Validate the aggregate platform release report without weakening runtime gates."""

from __future__ import annotations

import argparse
import json
import pathlib
import sys


REQUIRED_RUNTIME = {
    "r1-empty-rebuild",
    "r2-cdc",
    "r3-managed-lifecycle",
    "r4-event-time-quality",
    "r4-r5-control-plane",
    "r5-maintenance-runner",
    "datasource-seven-runtime",
}


def validate(path: pathlib.Path) -> dict:
    body = json.loads(path.read_text(encoding="utf-8-sig"))
    if body.get("gate") != "platform-release-acceptance":
        raise ValueError("unexpected aggregate gate")
    if body.get("status") not in {"passed", "manual_review_required", "failed"}:
        raise ValueError("invalid aggregate status")
    offline = body.get("offlineChecks") or []
    if any(item.get("status") != "passed" for item in offline):
        raise ValueError("offline checks contain a failure")
    reports = {item.get("name"): item for item in body.get("runtimeReports") or []}
    missing = REQUIRED_RUNTIME - set(reports)
    if missing:
        raise ValueError(f"aggregate report is missing runtime gates: {sorted(missing)}")
    failed = [name for name in REQUIRED_RUNTIME if reports[name].get("status") != "passed"]
    if failed:
        raise ValueError(f"runtime gates are not passed: {sorted(failed)}")
    if body.get("status") == "passed":
        additional = body.get("additionalGates") or {}
        if (additional.get("r3Ha") or {}).get("status") != "passed":
            raise ValueError("passed aggregate report must include passed R3 HA evidence")
        if (additional.get("datasourceNine") or {}).get("status") != "passed":
            raise ValueError("passed aggregate report must include nine-source evidence")
        if (body.get("docker") or {}).get("available") is not True:
            raise ValueError("passed aggregate report requires Docker runtime evidence")
        if body.get("manualReviewRequired"):
            raise ValueError("passed aggregate report cannot contain manual review items")
    return body


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--report", required=True, type=pathlib.Path)
    args = parser.parse_args()
    try:
        validate(args.report)
    except (OSError, ValueError, json.JSONDecodeError) as error:
        print(f"platform release report validation failed: {error}", file=sys.stderr)
        return 1
    print("platform release report validation passed")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
