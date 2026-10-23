import json
import tempfile
import unittest
import importlib.util
from pathlib import Path
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[2]
SPEC = importlib.util.spec_from_file_location("validate_lakehouse_r1", ROOT / "scripts" / "validate-lakehouse-r1.py")
validator = importlib.util.module_from_spec(SPEC)
assert SPEC.loader is not None
SPEC.loader.exec_module(validator)


class LakehouseR1ValidatorTest(unittest.TestCase):
    def test_current_draft_passes_offline_gate(self):
        warnings = validator.validate("draft")
        self.assertEqual([], warnings)

    def test_current_release_remains_blocked_by_unresolved_minio_images(self):
        with self.assertRaisesRegex(ValueError, "Unresolved base-image locks: 2"):
            validator.validate("release")

    def test_pinned_image_cannot_change_tag_digest_repository_or_revert_to_tag(self):
        compose = validator.COMPOSE.read_text(encoding="utf-8")
        pinned = next(line.strip().split("image: ", 1)[1] for line in validator.service_block(compose, "source-postgres").splitlines() if line.strip().startswith("image:"))
        replacements = (pinned.replace(":16.9@", ":17.0@"),
                        pinned.rsplit(":", 1)[0] + ":" + "0" * 64,
                        pinned.replace("library/postgres", "adjacent/postgres"),
                        "postgres:16.9", pinned.split("@", 1)[0])
        for replacement in replacements:
            with self.subTest(reference=replacement), tempfile.TemporaryDirectory() as directory:
                path = Path(directory) / "compose.yml"
                path.write_text(compose.replace(pinned, replacement), encoding="utf-8")
                with patch.object(validator, "COMPOSE", path):
                    with self.assertRaisesRegex(ValueError, "image drift for source-postgres"):
                        validator.validate("draft")

    def test_matching_source_and_lock_digest_cannot_forge_official_manifest(self):
        compose = validator.COMPOSE.read_text(encoding="utf-8")
        lock = json.loads((ROOT / ".github/workflows/base-image-inventory.json").read_text(encoding="utf-8"))
        item = next(x for x in lock["images"] if x["reference"].startswith("docker.io/library/postgres:16.9@"))
        old = item["reference"]
        item["officialResolution"]["digest"] = "sha256:" + "0" * 64
        item["reference"] = old.split("@", 1)[0] + "@" + item["officialResolution"]["digest"]
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "compose.yml"
            path.write_text(compose.replace(old, item["reference"]), encoding="utf-8")
            locked = Path(directory) / "lock.json"
            locked.write_text(json.dumps(lock), encoding="utf-8")
            with patch.object(validator, "COMPOSE", path), patch.object(validator, "IMAGE_LOCK", locked, create=True):
                with self.assertRaisesRegex(ValueError, "image drift for source-postgres"):
                    validator.validate("draft")

    def test_duplicate_image_cannot_hide_an_unpinned_override(self):
        compose = validator.COMPOSE.read_text(encoding="utf-8")
        marker = "    # Logical WAL is required"
        modified = compose.replace(marker, "    image: postgres:16.9\n" + marker)
        self.assertNotEqual(compose, modified)
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "compose.yml"
            path.write_text(modified, encoding="utf-8")
            with patch.object(validator, "COMPOSE", path):
                with self.assertRaisesRegex(ValueError, "image drift for source-postgres"):
                    validator.validate("draft")

    def test_unverified_minio_digest_is_not_a_release_fix(self):
        compose = validator.COMPOSE.read_text(encoding="utf-8")
        modified = compose.replace("quay.io/minio/minio:RELEASE.2025-09-07T16-13-09Z",
                                   "quay.io/minio/minio:RELEASE.2025-09-07T16-13-09Z@sha256:" + "0" * 64)
        self.assertNotEqual(compose, modified)
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "compose.yml"
            path.write_text(modified, encoding="utf-8")
            with patch.object(validator, "COMPOSE", path):
                with self.assertRaisesRegex(ValueError, "image drift for object-store"):
                    validator.validate("draft")

    def test_jdbc_driver_versions_are_required_and_pinned(self):
        bom = validator.BOM.read_text(encoding="utf-8")
        head, tail = bom.rsplit('trino = "476"', 1)
        modified = head + 'trino = "latest"' + tail
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "r1-bom.toml"
            path.write_text(modified, encoding="utf-8")
            with patch.object(validator, "BOM", path):
                with self.assertRaisesRegex(ValueError, "jdbc_drivers.trino"):
                    validator.validate("draft")

    def test_connector_source_must_match_coordinate(self):
        allowlist = validator.ALLOWLIST.read_text(encoding="utf-8")
        modified = allowlist.replace("iceberg-spark-runtime-3.5_2.12-1.7.1.jar", "wrong-name.jar")
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "connector-allowlist.toml"
            path.write_text(modified, encoding="utf-8")
            with patch.object(validator, "ALLOWLIST", path):
                with self.assertRaisesRegex(ValueError, "filename does not match coordinate"):
                    validator.validate("draft")

    def test_image_drift_is_rejected(self):
        compose = validator.COMPOSE.read_text(encoding="utf-8")
        modified = compose.replace("trinodb/trino:476", "trinodb/trino:latest")
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "docker-compose.r1.yml"
            path.write_text(modified, encoding="utf-8")
            with patch.object(validator, "COMPOSE", path):
                with self.assertRaisesRegex(ValueError, "image drift for trino"):
                    validator.validate("draft")

    def test_non_loopback_port_is_rejected(self):
        compose = validator.COMPOSE.read_text(encoding="utf-8")
        modified = compose.replace('"127.0.0.1:${R1_TRINO_PORT:-18080}:8080"', '"0.0.0.0:${R1_TRINO_PORT:-18080}:8080"')
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "docker-compose.r1.yml"
            path.write_text(modified, encoding="utf-8")
            with patch.object(validator, "COMPOSE", path):
                with self.assertRaisesRegex(ValueError, "loopback"):
                    validator.validate("draft")

    def test_sql_gateway_is_required_and_pinned(self):
        compose = validator.COMPOSE.read_text(encoding="utf-8")
        self.assertIn("flink-sql-gateway:", compose)
        self.assertIn("sql-gateway.sh start", compose)
        self.assertIn("R1_FLINK_SQL_GATEWAY_PORT", compose)

    def test_sql_gateway_start_command_is_required(self):
        compose = validator.COMPOSE.read_text(encoding="utf-8")
        modified = compose.replace("./bin/sql-gateway.sh start", "./bin/sql-gateway.sh removed")
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "docker-compose.r1.yml"
            path.write_text(modified, encoding="utf-8")
            with patch.object(validator, "COMPOSE", path):
                with self.assertRaisesRegex(ValueError, "sql-gateway.sh"):
                    validator.validate("draft")

    def test_machine_table_contract_is_required(self):
        contract = validator.TABLE_CONTRACT_TOML.read_text(encoding="utf-8")
        modified = contract.replace('name_pattern = "<domain>.gold_<subject>"', 'name_pattern = ""')
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "r1-table-contract.toml"
            path.write_text(modified, encoding="utf-8")
            with patch.object(validator, "TABLE_CONTRACT_TOML", path):
                with self.assertRaisesRegex(ValueError, "incomplete for gold"):
                    validator.validate("draft")

    def test_bronze_contract_requires_upsert_key(self):
        contract = validator.TABLE_CONTRACT_TOML.read_text(encoding="utf-8")
        modified = contract.replace('write_mode = "upsert"', 'write_mode = "append"')
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "r1-table-contract.toml"
            path.write_text(modified, encoding="utf-8")
            with patch.object(validator, "TABLE_CONTRACT_TOML", path):
                with self.assertRaisesRegex(ValueError, "upsert semantics"):
                    validator.validate("draft")

    def test_release_rejects_unapproved_connector(self):
        allowlist = validator.ALLOWLIST.read_text(encoding="utf-8")
        modified = allowlist.replace('id = "flink-connector-jdbc"', 'id = "flink-connector-jdbc"', 1).replace(
            'status = "approved"', 'status = "candidate"', 1
        )
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "connector-allowlist.toml"
            path.write_text(modified, encoding="utf-8")
            with patch.object(validator, "ALLOWLIST", path):
                with self.assertRaisesRegex(ValueError, "approved connectors"):
                    validator.validate("release")

    def test_source_postgres_fixture_is_pinned_and_mounted(self):
        compose = validator.COMPOSE.read_text(encoding="utf-8")
        self.assertIn("source-postgres:", compose)
        self.assertRegex(compose, r"image: docker\.io/library/postgres:16\.9@sha256:[0-9a-f]{64}")
        self.assertIn("../../fixtures/r1/postgres:/docker-entrypoint-initdb.d:ro", compose)
        self.assertIn("wal_level=logical", compose)

    def test_source_mysql_fixture_is_pinned_and_binlog_enabled(self):
        compose = validator.COMPOSE.read_text(encoding="utf-8")
        self.assertIn("source-mysql:", compose)
        self.assertRegex(compose, r"image: docker\.io/library/mysql:8\.0\.40@sha256:[0-9a-f]{64}")
        self.assertIn("../../fixtures/r1/mysql:/docker-entrypoint-initdb.d:ro", compose)
        self.assertIn("--binlog-format=ROW", compose)

    def test_streaming_job_has_recovery_window_for_taskmanager_restart(self):
        compose = validator.COMPOSE.read_text(encoding="utf-8")
        self.assertIn("restart-strategy: fixed-delay", compose)
        self.assertIn("restart-strategy.fixed-delay.attempts: 10", compose)
        self.assertIn("restart-strategy.fixed-delay.delay: 10 s", compose)

    def test_all_bom_jdbc_drivers_have_approved_source_license_and_digest(self):
        allowlist = validator.ALLOWLIST.read_text(encoding="utf-8")
        self.assertIn('id = "mysql-jdbc"', allowlist)
        self.assertIn('id = "oracle-jdbc"', allowlist)
        self.assertIn('license_source = "https://www.oracle.com/downloads/licenses/oracle-free-license.html"', allowlist)
        # Connector approval does not resolve the separate MinIO image blockers.
        self.assertEqual([], validator.validate("draft"))

    def test_jdbc_driver_license_source_is_required(self):
        allowlist = validator.ALLOWLIST.read_text(encoding="utf-8")
        modified = allowlist.replace(
            'license_source = "https://www.oracle.com/downloads/licenses/oracle-free-license.html"\n',
            "",
            1,
        )
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "connector-allowlist.toml"
            path.write_text(modified, encoding="utf-8")
            with patch.object(validator, "ALLOWLIST", path):
                with self.assertRaisesRegex(ValueError, "license source must be HTTPS: oracle-jdbc"):
                    validator.validate("draft")

    def test_flink_runtime_gate_rejects_cancelled_as_success(self):
        submit = validator.FLINK_SUBMIT_SCRIPT.read_text(encoding="utf-8")
        modified = submit.replace('$status -ne "FINISHED"', '$status -notin @("FINISHED", "CANCELED")', 1)
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "r1-submit-flink-sql.ps1"
            path.write_text(modified, encoding="utf-8")
            with patch.object(validator, "FLINK_SUBMIT_SCRIPT", path):
                with self.assertRaisesRegex(ValueError, "must never accept CANCELED"):
                    validator.validate("draft")

    def test_empty_rebuild_gate_must_record_fresh_environment(self):
        rebuild = validator.REBUILD_SCRIPT.read_text(encoding="utf-8")
        modified = rebuild.replace("freshRebuild = [bool]$freshRebuild", "freshRebuild = $true", 1)
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "r1-rebuild.ps1"
            path.write_text(modified, encoding="utf-8")
            with patch.object(validator, "REBUILD_SCRIPT", path):
                with self.assertRaisesRegex(ValueError, "freshRebuild"):
                    validator.validate("draft")


    def test_connector_manifest_digest_drift_is_rejected(self):
        manifest = json.loads(validator.CONNECTOR_MANIFEST.read_text(encoding="utf-8"))
        manifest["connectors"][0]["sha256"] = "0" * 64
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "CONNECTOR-MANIFEST.json"
            path.write_text(json.dumps(manifest), encoding="utf-8")
            with patch.object(validator, "CONNECTOR_MANIFEST", path):
                with self.assertRaisesRegex(ValueError, "manifest digest drift"):
                    validator.validate("draft")

if __name__ == "__main__":
    unittest.main()
