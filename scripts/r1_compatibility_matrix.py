#!/usr/bin/env python3
"""Build and verify the deterministic R1 Flink/Iceberg compatibility matrix."""

from __future__ import annotations

import argparse
import json
import pathlib
import re
import sys
import tomllib
from typing import Any

ROOT = pathlib.Path(__file__).resolve().parents[1]
BOM_PATH = ROOT / "configs" / "lakehouse" / "r1-bom.toml"
ALLOWLIST_PATH = ROOT / "configs" / "lakehouse" / "connector-allowlist.toml"
COMPOSE_PATH = ROOT / "infrastructure" / "lakehouse" / "docker-compose.r1.yml"
DEFAULT_MATRIX_PATH = ROOT / "configs" / "lakehouse" / "r1-compatibility-matrix.json"

SERVICE_PROFILES = {
    "object-store": ("r1", "storage"),
    "object-store-init": ("r1", "storage"),
    "source-postgres": ("r1", "sources"),
    "source-mysql": ("r1", "sources"),
    "catalog": ("r1", "catalog"),
    "trino": ("r1", "trino"),
    "flink-jobmanager": ("r1", "flink"),
    "flink-taskmanager": ("r1", "flink"),
    "flink-sql-gateway": ("r1", "flink"),
}


class CompatibilityMatrixError(ValueError):
    pass


def service_block(compose: str, service: str) -> str:
    match = re.search(
        rf"(?ms)^  {re.escape(service)}:\s*\n(?P<body>(?:(?!^  \S).*(?:\n|$))*)",
        compose,
    )
    if not match:
        raise CompatibilityMatrixError(f"R1 compose is missing service {service}")
    return match.group("body")


def _service_entry(compose: str, service: str) -> dict[str, Any]:
    block = service_block(compose, service)
    image = re.search(r"(?m)^\s+image:\s*(\S+)", block)
    profiles = re.search(r"(?m)^\s+profiles:\s*\[([^\]]+)\]", block)
    if image is None or profiles is None:
        raise CompatibilityMatrixError(f"R1 service {service} must declare image and profiles")
    declared = [item.strip().strip('\\"\\\'') for item in profiles.group(1).split(",")]
    expected = list(SERVICE_PROFILES[service])
    if not set(expected).issubset(declared):
        raise CompatibilityMatrixError(f"R1 service {service} is missing profiles: {expected}")
    return {"name": service, "image": image.group(1), "profiles": declared}


def build_matrix(root: pathlib.Path = ROOT) -> dict[str, Any]:
    bom_path = root / "configs" / "lakehouse" / "r1-bom.toml"
    allowlist_path = root / "configs" / "lakehouse" / "connector-allowlist.toml"
    compose_path = root / "infrastructure" / "lakehouse" / "docker-compose.r1.yml"
    bom = tomllib.loads(bom_path.read_text(encoding="utf-8"))
    allowlist = tomllib.loads(allowlist_path.read_text(encoding="utf-8"))
    compose = compose_path.read_text(encoding="utf-8")
    connectors = []
    seen: set[str] = set()
    for item in allowlist.get("connector", []):
        connector_id = str(item.get("id", ""))
        if not connector_id or connector_id in seen:
            raise CompatibilityMatrixError(f"duplicate or empty connector id: {connector_id}")
        seen.add(connector_id)
        connectors.append({
            "id": connector_id,
            "coordinate": str(item.get("coordinate", "")),
            "source": str(item.get("source", "")),
            "sha256": str(item.get("sha256", "")).lower(),
            "license": str(item.get("license", "")),
            "status": str(item.get("status", "")),
        })
    runtime = dict(bom["runtime"])
    matrix = {
        "schemaVersion": 1,
        "runtime": {
            "jdk": runtime["jdk"],
            "flink": runtime["flink"],
            "flinkCdc": runtime["flink_cdc"],
            "iceberg": runtime["iceberg"],
            "spark": runtime["spark"],
            "trino": runtime["trino"],
        },
        "catalog": {
            "implementation": bom["catalog"]["implementation"],
            "version": bom["catalog"]["version"],
            "endpoint": bom["catalog"]["endpoint"],
        },
        "objectStorage": {
            "implementation": bom["object_storage"]["implementation"],
            "image": bom["object_storage"]["image"],
            "initImage": bom["object_storage"]["init_image"],
            "bucket": bom["object_storage"]["bucket"],
        },
        "sourceDatabases": dict(bom["source_databases"]),
        "services": [_service_entry(compose, service) for service in SERVICE_PROFILES],
        "connectors": connectors,
        "acceptance": {
            "tableFormat": bom["acceptance"]["required_table_format"],
            "engines": list(bom["acceptance"]["required_engines"]),
            "rebuildRequired": bool(bom["acceptance"]["rebuild_required"]),
        },
    }
    return matrix


def check_matrix(path: pathlib.Path = DEFAULT_MATRIX_PATH, root: pathlib.Path = ROOT) -> None:
    try:
        actual = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as error:
        raise CompatibilityMatrixError(f"cannot read compatibility matrix: {error}") from error
    expected = build_matrix(root)
    if actual != expected:
        raise CompatibilityMatrixError("compatibility matrix is stale; regenerate it from the current BOM, allowlist and Compose file")


def write_matrix(path: pathlib.Path = DEFAULT_MATRIX_PATH, root: pathlib.Path = ROOT) -> None:
    matrix = build_matrix(root)
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(matrix, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser()
    sub = parser.add_subparsers(dest="command", required=True)
    generate = sub.add_parser("generate")
    generate.add_argument("--output", type=pathlib.Path, default=DEFAULT_MATRIX_PATH)
    check = sub.add_parser("check")
    check.add_argument("--matrix", type=pathlib.Path, default=DEFAULT_MATRIX_PATH)
    args = parser.parse_args(argv)
    try:
        if args.command == "generate":
            write_matrix(args.output)
            print(json.dumps({"status": "generated", "path": str(args.output)}, ensure_ascii=False))
        else:
            check_matrix(args.matrix)
            print(json.dumps({"status": "passed", "path": str(args.matrix)}, ensure_ascii=False))
    except (OSError, CompatibilityMatrixError, KeyError, tomllib.TOMLDecodeError) as error:
        print(f"R1 compatibility matrix failed: {error}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())