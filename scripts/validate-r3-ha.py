#!/usr/bin/env python3
"""Validate the reviewable, offline contract of the R3 HA Compose overlay."""

from __future__ import annotations

import pathlib
import re
import sys


ROOT = pathlib.Path(__file__).resolve().parents[1]
BASE = ROOT / "infrastructure" / "lakehouse" / "docker-compose.r1.yml"
OVERLAY = ROOT / "infrastructure" / "lakehouse" / "docker-compose.r3-ha.yml"


def fail(message: str) -> None:
    raise ValueError(message)


def service_block(text: str, service: str) -> str:
    match = re.search(
        rf"(?ms)^  {re.escape(service)}:\s*\n(?P<body>(?:(?!^  \S).*(?:\n|$))*)",
        text,
    )
    if not match:
        fail(f"R3 HA overlay is missing service {service}")
    return match.group("body")


def validate() -> None:
    base = BASE.read_text(encoding="utf-8")
    overlay = OVERLAY.read_text(encoding="utf-8")
    for service in ("object-store", "source-postgres", "flink-jobmanager", "flink-taskmanager"):
        service_block(base, service)
    for service in ("zookeeper", "flink-jobmanager", "flink-jobmanager-standby", "flink-taskmanager", "flink-sql-gateway"):
        service_block(overlay, service)

    if 'profiles: ["r1", "flink"]' not in base:
        fail("R1 base Flink services must remain in the r1/flink profiles")
    for marker in (
        "high-availability.type: zookeeper",
        "high-availability.storageDir: s3://warehouse/flink-ha",
        "high-availability.zookeeper.quorum: zookeeper:2181",
        "high-availability.cluster-id: /aetheris-r3-ha",
    ):
        if marker not in overlay:
            fail(f"R3 HA overlay is missing {marker}")
    if "flink-jobmanager-standby:" not in overlay:
        fail("R3 HA overlay must provide a second JobManager")
    if '"127.0.0.1:18184:8081"' not in overlay:
        fail("R3 HA primary JobManager must use the isolated loopback port")
    if '"127.0.0.1:18185:8081"' not in overlay:
        fail("R3 HA standby JobManager must use the isolated loopback port")
    if '"127.0.0.1:18186:8083"' not in overlay:
        fail("R3 HA SQL Gateway must use the isolated loopback port")
    if "ports: !override" not in overlay:
        fail("R3 HA overlay must replace base host ports instead of merging them")
    for binding in re.findall(r'(?m)^\s*-\s*"([^"\n]+)"\s*$', overlay):
        if ":" in binding and not binding.startswith("127.0.0.1:"):
            fail(f"R3 HA host port must bind loopback only: {binding}")


def main() -> int:
    try:
        validate()
    except (OSError, ValueError) as error:
        print(f"R3 HA validation failed: {error}", file=sys.stderr)
        return 1
    print("R3 HA offline validation passed")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
