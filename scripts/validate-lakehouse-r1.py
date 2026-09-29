#!/usr/bin/env python3
"""Validate the reviewable, offline parts of the R1 lakehouse release gate."""

from __future__ import annotations

import argparse
import pathlib
import re
import sys
import tomllib
from urllib.parse import urlparse


ROOT = pathlib.Path(__file__).resolve().parents[1]
BOM = ROOT / "configs" / "lakehouse" / "r1-bom.toml"
ALLOWLIST = ROOT / "configs" / "lakehouse" / "connector-allowlist.toml"
COMPOSE = ROOT / "infrastructure" / "lakehouse" / "docker-compose.r1.yml"
TRINO_CATALOG = ROOT / "infrastructure" / "lakehouse" / "trino" / "catalog" / "iceberg.properties"
TABLE_CONTRACT = ROOT / "docs" / "r1-table-contract.md"
TABLE_CONTRACT_TOML = ROOT / "configs" / "lakehouse" / "r1-table-contract.toml"
FIXTURE_GENERATOR = ROOT / "scripts" / "r1-fixture.py"
REBUILD_SCRIPT = ROOT / "scripts" / "r1-rebuild.ps1"
FLINK_SUBMIT_SCRIPT = ROOT / "scripts" / "r1-submit-flink-sql.ps1"


def fail(message: str) -> None:
    raise ValueError(message)


def service_block(compose: str, service: str) -> str:
    match = re.search(
        rf"(?ms)^  {re.escape(service)}:\s*\n(?P<body>(?:(?!^  \S).*(?:\n|$))*)",
        compose,
    )
    if not match:
        fail(f"R1 compose is missing service {service}")
    return match.group("body")


def validate_compose(bom: dict) -> None:
    compose = COMPOSE.read_text(encoding="utf-8")
    catalog = TRINO_CATALOG.read_text(encoding="utf-8")
    required_services = (
        "object-store", "object-store-init", "source-postgres", "source-mysql", "catalog", "trino",
        "flink-jobmanager", "flink-taskmanager", "flink-sql-gateway",
    )
    for service in required_services:
        service_block(compose, service)

    expected_profiles = {
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
    for service, profiles in expected_profiles.items():
        block = service_block(compose, service)
        profile_line = re.search(r"(?m)^\s+profiles:\s*\[([^\]]+)\]", block)
        if profile_line is None:
            fail(f"R1 service {service} must declare independent Compose profiles")
        declared = {item.strip().strip('\\\"\\\'') for item in profile_line.group(1).split(',')}
        if not set(profiles).issubset(declared):
            fail(f"R1 service {service} must declare profiles: {', '.join(profiles)}")

    expected_images = {
        "object-store": bom["object_storage"]["image"],
        "object-store-init": bom["object_storage"]["init_image"],
        "source-postgres": f"postgres:{bom['source_databases']['postgresql']}",
        "source-mysql": f"mysql:{bom['source_databases']['mysql']}",
        "catalog": f"tabulario/iceberg-rest:{bom['catalog']['version']}",
        "trino": f"trinodb/trino:{bom['runtime']['trino']}",
        "flink-jobmanager": f"flink:{bom['runtime']['flink']}-java{bom['runtime']['jdk'].split('.')[0]}",
        "flink-taskmanager": f"flink:{bom['runtime']['flink']}-java{bom['runtime']['jdk'].split('.')[0]}",
        "flink-sql-gateway": f"flink:{bom['runtime']['flink']}-java{bom['runtime']['jdk'].split('.')[0]}",
    }
    for service, expected in expected_images.items():
        block = service_block(compose, service)
        image = re.search(r"(?m)^\s+image:\s*(\S+)", block)
        if image is None or image.group(1) != expected:
            fail(f"R1 compose image drift for {service}: expected {expected}")

    s3_plugin = bom.get("builtin_plugins", {}).get("flink_s3_filesystem")
    if s3_plugin != f"flink-s3-fs-hadoop-{bom['runtime']['flink']}.jar":
        fail("R1 BOM must pin the Flink S3 filesystem plugin to the Flink runtime version")
    for service in ("flink-jobmanager", "flink-taskmanager", "flink-sql-gateway"):
        block = service_block(compose, service)
        if f"/opt/flink/opt/{s3_plugin}" not in block or f"/opt/flink/plugins/s3-fs-hadoop/{s3_plugin}" not in block:
            fail(f"{service} must enable the pinned Flink S3 filesystem plugin")
        for driver_jar in ("postgresql-42.7.13.jar", "mysql-connector-j-9.1.0.jar"):
            if f"./connectors/{driver_jar}:/opt/flink/lib/{driver_jar}:ro" not in compose:
                fail(f"R1 Flink services must mount approved JDBC driver {driver_jar}")

    port_bindings = re.findall(r'(?m)^\s*-\s*"([^"\n]+)"\s*$', compose)
    for binding in port_bindings:
        # The two R1 lakehouse data-plane ports may be parameterized for a
        # WSL acceptance run, but their declared default must remain loopback.
        loopback_binding = (
            binding.startswith("127.0.0.1:")
            or binding.startswith("${R1_BIND_HOST:-127.0.0.1}:")
        )
        if ":" in binding and not loopback_binding:
            fail(f"R1 host port must bind loopback only: {binding}")

    for variable in ("R1_MINIO_ACCESS_KEY", "R1_MINIO_SECRET_KEY"):
        if "${" + variable + ":?" not in compose:
            fail(f"R1 compose must require {variable}")
    gateway = service_block(compose, "flink-sql-gateway")
    if "sql-gateway.sh start" not in gateway:
        fail("Flink SQL Gateway service must start sql-gateway.sh")
    if "R1_FLINK_SQL_GATEWAY_PORT" not in gateway:
        fail("Flink SQL Gateway must expose a configurable host port")
    if "rest.address: flink-jobmanager" not in gateway:
        fail("Flink SQL Gateway must target the JobManager REST address")
    source = service_block(compose, "source-postgres")
    for variable in ("R1_SOURCE_POSTGRES_DB", "R1_SOURCE_POSTGRES_USER", "R1_SOURCE_POSTGRES_PASSWORD"):
        if "${" + variable + ":?" not in source:
            fail(f"source-postgres must require {variable}")
    if "../../fixtures/r1/postgres:/docker-entrypoint-initdb.d:ro" not in source:
        fail("source-postgres must mount the deterministic R1 fixture")
    for setting in ("wal_level=logical", "max_replication_slots=4", "max_wal_senders=4"):
        if setting not in source:
            fail(f"source-postgres must configure logical replication: {setting}")
    mysql = service_block(compose, "source-mysql")
    if "../../fixtures/r1/mysql:/docker-entrypoint-initdb.d:ro" not in mysql:
        fail("source-mysql must mount the deterministic R2 fixture")
    for setting in ("--log-bin=mysql-bin", "--binlog-format=ROW", "--binlog-row-image=FULL"):
        if setting not in mysql:
            fail(f"source-mysql must configure binlog replication: {setting}")
    if "iceberg.catalog.type=rest" not in catalog:
        fail("Trino catalog must use the Iceberg REST catalog")
    if "iceberg.rest-catalog.uri=" not in catalog:
        fail("Trino catalog must declare iceberg.rest-catalog.uri")
    if "s3.aws-secret-key=${ENV:R1_MINIO_SECRET_KEY}" not in catalog:
        fail("Trino catalog must reference R1_MINIO_SECRET_KEY through the environment")
    trino = service_block(compose, "trino")
    for variable in ("R1_MINIO_ACCESS_KEY", "R1_MINIO_SECRET_KEY"):
        if "${" + variable + ":?" not in trino:
            fail(f"Trino must receive {variable} from the R1 environment")
    if "AWS_REGION: us-east-1" not in trino:
        fail("Trino must pin an AWS region for S3-compatible storage")


def validate_table_contract_and_fixture() -> None:
    contract = TABLE_CONTRACT.read_text(encoding="utf-8")
    for marker in ("Bronze", "Silver", "Gold", "Iceberg format v2", "_event_time", "_is_deleted"):
        if marker not in contract:
            fail(f"R1 table contract is missing {marker}")
    machine_contract = tomllib.loads(TABLE_CONTRACT_TOML.read_text(encoding="utf-8"))
    if machine_contract["contract"]["format_version"] != 2:
        fail("R1 machine table contract must use Iceberg format v2")
    layers = machine_contract.get("layers", {})
    required_layers = {
        "bronze": ("_change_kind", "_source_lsn", "_event_time", "_ingest_time"),
        "silver": ("_first_seen_at", "_last_seen_at", "_is_deleted"),
        "gold": ("metric_version", "_as_of"),
    }
    for layer, required_fields in required_layers.items():
        definition = layers.get(layer)
        if not definition:
            fail(f"R1 machine table contract is missing {layer}")
        if not definition.get("name_pattern") or not definition.get("writer"):
            fail(f"R1 machine table contract is incomplete for {layer}")
        if not set(required_fields).issubset(definition.get("required_metadata", [])):
            fail(f"R1 machine table contract is missing metadata for {layer}")
    bronze = layers["bronze"]
    if bronze.get("primary_key") != ["id"] or bronze.get("write_mode") != "upsert":
        fail("R1 Bronze contract must declare id-keyed upsert semantics")
    fixture = FIXTURE_GENERATOR.read_text(encoding="utf-8")
    for marker in ("expected_contract", "expected_cdc_rows", "reconcile", "verify_cdc", "format-version' = '2'"):
        if marker not in fixture:
            fail(f"R1 fixture generator is missing {marker}")


def validate_runtime_gate_contract() -> None:
    submit = FLINK_SUBMIT_SCRIPT.read_text(encoding="utf-8")
    if '-notin @("FINISHED", "CANCELED")' in submit:
        fail("R1 Flink submission gate must never accept CANCELED as success")
    for marker in (
        "Wait-FlinkJobFinished",
        '$status -ne "FINISHED"',
        "Flink INSERT completed without a verifiable Job ID",
        '$state -ne "FINISHED"',
    ):
        if marker not in submit:
            fail(f"R1 Flink submission gate is missing: {marker}")

    rebuild = REBUILD_SCRIPT.read_text(encoding="utf-8")
    for marker in (
        "RequireFreshRebuild requires ResetVolumes",
        "freshRebuild = [bool]$ResetVolumes",
        "R1-empty-rebuild-hard-gate",
        "-JobManagerEndpoint $jobManager",
    ):
        if marker not in rebuild:
            fail(f"R1 rebuild gate is missing: {marker}")


def validate(mode: str) -> list[str]:
    bom = tomllib.loads(BOM.read_text(encoding="utf-8"))
    allowlist = tomllib.loads(ALLOWLIST.read_text(encoding="utf-8"))
    warnings: list[str] = []

    if bom["meta"]["status"] not in {"candidate", "approved"}:
        fail("BOM meta.status must be candidate or approved")
    for section, key in (
        ("runtime", "jdk"),
        ("runtime", "flink"),
        ("runtime", "flink_cdc"),
        ("runtime", "iceberg"),
        ("runtime", "spark"),
        ("runtime", "trino"),
    ):
        value = str(bom[section][key])
        if not value or value.lower() in {"latest", "snapshot", "dev"}:
            fail(f"{section}.{key} must be pinned")
    required_jdbc_drivers = ("postgresql", "mysql", "mssql", "clickhouse", "oracle", "sqlite", "h2", "derby", "hive", "trino")
    for driver in required_jdbc_drivers:
        value = str(bom.get("jdbc_drivers", {}).get(driver, ""))
        if not value or value.lower() in {"latest", "snapshot", "dev"}:
            fail(f"jdbc_drivers.{driver} must be pinned")

    if bom["acceptance"]["required_table_format"] != "iceberg-v2":
        fail("R1 requires Iceberg format v2")
    if set(bom["acceptance"]["required_engines"]) != {"flink", "spark", "trino"}:
        fail("R1 engine acceptance must cover Flink, Spark and Trino")
    validate_compose(bom)
    validate_table_contract_and_fixture()
    validate_runtime_gate_contract()

    supported_licenses = {
        "Apache-2.0",
        "BSD-2-Clause",
        "BSD-3-Clause",
        "MIT",
        "GPL-2.0-with-Universal-FOSS-Exception",
        "Oracle-FUTC",
        "MPL-2.0 OR EPL-1.0",
    }
    jdbc_driver_connectors = {}
    connector_by_id = {}
    for connector in allowlist["connector"]:
        if connector["id"] in connector_by_id:
            fail(f"duplicate connector id: {connector['id']}")
        connector_by_id[connector["id"]] = connector
        if connector["license"] not in supported_licenses:
            fail(f"unsupported license for {connector['id']}")
        parsed = urlparse(connector["source"])
        if parsed.scheme != "https" or parsed.netloc != "repo1.maven.org":
            fail(f"connector source must be Maven Central HTTPS: {connector['id']}")
        coordinate = str(connector.get("coordinate", ""))
        parts = coordinate.split(":")
        if len(parts) != 3 or any(not part for part in parts):
            fail(f"invalid Maven coordinate for {connector['id']}")
        expected_artifact = f"{parts[1]}-{parts[2]}.jar"
        if pathlib.PurePosixPath(parsed.path).name != expected_artifact:
            fail(f"connector source filename does not match coordinate for {connector['id']}")
        digest = connector["sha256"]
        if digest == "PENDING_CAPTURE":
            warnings.append(f"{connector['id']}: SHA-256 capture pending")
        elif len(digest) != 64 or any(ch not in "0123456789abcdef" for ch in digest.lower()):
            fail(f"invalid SHA-256 for {connector['id']}")
        if connector["status"] not in {"candidate", "approved"}:
            fail(f"invalid connector status for {connector['id']}")
        if connector.get("kind") == "jdbc-driver":
            driver = str(connector.get("driver", "")).strip()
            if not driver:
                fail(f"jdbc driver connector must declare driver: {connector['id']}")
            license_source = str(connector.get("license_source", ""))
            parsed_license = urlparse(license_source)
            if parsed_license.scheme != "https" or not parsed_license.netloc:
                fail(f"jdbc driver license source must be HTTPS: {connector['id']}")
            if driver in jdbc_driver_connectors:
                fail(f"duplicate JDBC driver connector: {driver}")
            jdbc_driver_connectors[driver] = connector

    flink_s3 = connector_by_id.get("flink-s3-fs-hadoop")
    if flink_s3 is None or not str(flink_s3.get("coordinate", "")).endswith(f":{bom['runtime']['flink']}"):
        fail("Flink S3 filesystem plugin must be approved for the pinned Flink runtime")
    runtime_digest = str(flink_s3.get("runtime_sha256", ""))
    if len(runtime_digest) != 64 or any(ch not in "0123456789abcdef" for ch in runtime_digest.lower()):
        fail("Flink S3 filesystem runtime SHA-256 must be recorded")

    for driver, version in bom.get("jdbc_drivers", {}).items():
        connector = jdbc_driver_connectors.get(driver)
        if connector is None:
            fail(f"jdbc driver connector missing: {driver}")
        coordinate = str(connector.get("coordinate", ""))
        if not coordinate.endswith(f":{version}"):
            fail(f"jdbc driver version drift for {driver}: expected {version}")
        if connector.get("status") != "approved":
            fail(f"jdbc driver connector is not approved: {driver}")

    if mode == "release":
        if bom["meta"]["status"] != "approved":
            fail("release mode requires BOM meta.status=approved")
        if warnings:
            fail("release mode requires captured connector SHA-256 values")
        unapproved = [item["id"] for item in allowlist["connector"] if item["status"] != "approved"]
        if unapproved:
            fail("release mode requires approved connectors: " + ", ".join(unapproved))
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
