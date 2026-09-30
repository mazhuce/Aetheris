import importlib.util
import json
import tempfile
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
SPEC = importlib.util.spec_from_file_location("r1_fixture", ROOT / "scripts" / "r1-fixture.py")
fixture = importlib.util.module_from_spec(SPEC)
assert SPEC.loader is not None
SPEC.loader.exec_module(fixture)


class R1FixtureTests(unittest.TestCase):
    def test_generate_is_deterministic_and_contains_engine_contracts(self):
        with tempfile.TemporaryDirectory() as temporary:
            output = Path(temporary) / "fixture"
            manifest = fixture.generate(output)
            self.assertEqual(manifest["fixture"], "aetheris-r1-postgres-iceberg-v1")
            self.assertEqual(manifest["expected"]["rows"], [["A", 2, "30.50"], ["B", 2, "20.00"], ["C", 1, "4.00"]])
            self.assertTrue((output / "postgres/001_source_orders.sql").exists())
            self.assertTrue((output / "postgres/002_cdc_mutations.sql").exists())
            self.assertTrue((output / "mysql/001_source_orders.sql").exists())
            self.assertTrue((output / "mysql/002_cdc_mutations.sql").exists())
            self.assertTrue((output / "flink/001_bronze_orders.sql").exists())
            self.assertTrue((output / "flink/002_postgres_cdc.sql").exists())
            self.assertTrue((output / "flink/003_mysql_cdc.sql").exists())
            self.assertTrue((output / "flink/004_r3_flink_lifecycle.sql").exists())
            self.assertTrue((output / "spark/001_reconcile.sql").exists())
            self.assertTrue((output / "trino/001_reconcile.sql").exists())
            source_sql = (output / "postgres/001_source_orders.sql").read_text(encoding="utf-8")
            self.assertIn("REPLICA IDENTITY FULL", source_sql)
            mutation_sql = (output / "postgres/002_cdc_mutations.sql").read_text(encoding="utf-8")
            self.assertIn("UPDATE r1_fixture.source_orders", mutation_sql)
            self.assertIn("DELETE FROM r1_fixture.source_orders", mutation_sql)
            self.assertIn("INSERT INTO r1_fixture.source_orders", mutation_sql)
            self.assertIn("'connector' = 'jdbc'", (output / "flink/001_bronze_orders.sql").read_text(encoding="utf-8"))
            self.assertIn("'connector' = 'postgres-cdc'", (output / "flink/002_postgres_cdc.sql").read_text(encoding="utf-8"))
            postgres_cdc = (output / "flink/002_postgres_cdc.sql").read_text(encoding="utf-8")
            mysql_cdc = (output / "flink/003_mysql_cdc.sql").read_text(encoding="utf-8")
            for cdc_sql in (postgres_cdc, mysql_cdc):
                self.assertIn("'state.checkpoints.dir' = 's3://warehouse/checkpoints/'", cdc_sql)
                self.assertIn("'state.savepoints.dir' = 's3://warehouse/savepoints/'", cdc_sql)
                self.assertIn("RETAIN_ON_CANCELLATION", cdc_sql)
                self.assertIn("'restart-strategy.type' = 'fixed-delay'", cdc_sql)
                self.assertIn("'restart-strategy.fixed-delay.attempts' = '3'", cdc_sql)
                self.assertIn("'write.upsert.enabled' = 'true'", cdc_sql)

    def test_generate_refuses_to_overwrite_without_force(self):
        with tempfile.TemporaryDirectory() as temporary:
            output = Path(temporary) / "fixture"
            output.mkdir()
            (output / "keep.txt").write_text("keep", encoding="utf-8")
            with self.assertRaisesRegex(fixture.FixtureError, "not empty"):
                fixture.generate(output)
            self.assertEqual((output / "keep.txt").read_text(encoding="utf-8"), "keep")

    def test_render_replaces_credentials_only_in_derived_sql(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            fixture_dir = root / "fixture"
            fixture.generate(fixture_dir)
            env_file = root / ".env.r1"
            env_file.write_text(
                "R1_SOURCE_POSTGRES_USER=r1_fixture\n"
                "R1_SOURCE_POSTGRES_PASSWORD=p@ss'word\n",
                encoding="utf-8",
            )
            rendered = fixture.render_sql(fixture_dir, env_file)
            self.assertEqual(len(rendered), 4)
            template = (fixture_dir / "flink/001_bronze_orders.sql").read_text(encoding="utf-8")
            output = (fixture_dir / "flink/rendered/001_bronze_orders.sql").read_text(encoding="utf-8")
            self.assertIn("${R1_SOURCE_POSTGRES_PASSWORD}", template)
            self.assertIn("p@ss''word", output)
            self.assertNotIn("${R1_SOURCE_POSTGRES_PASSWORD}", output)
            mysql_output = (fixture_dir / "flink/rendered/003_mysql_cdc.sql").read_text(encoding="utf-8")
            self.assertIn("'connector' = 'mysql-cdc'", mysql_output)
            self.assertIn("p@ss''word", mysql_output)
            lifecycle_output = (fixture_dir / "flink/rendered/004_r3_flink_lifecycle.sql").read_text(encoding="utf-8")
            self.assertIn("r3_orders_lifecycle", lifecycle_output)
            self.assertIn("p@ss''word", lifecycle_output)

    def test_render_rejects_example_credentials(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            fixture_dir = root / "fixture"
            fixture.generate(fixture_dir)
            env_file = root / ".env.r1"
            env_file.write_text(
                "R1_SOURCE_POSTGRES_USER=r1_fixture\n"
                "R1_SOURCE_POSTGRES_PASSWORD=replace-with-a-random-source-password\n",
                encoding="utf-8",
            )
            with self.assertRaisesRegex(fixture.FixtureError, "example or pending"):
                fixture.render_sql(fixture_dir, env_file)

    def test_reconcile_accepts_order_and_numeric_format_differences(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            fixture_dir = root / "fixture"
            fixture.generate(fixture_dir)
            spark = root / "spark.json"
            trino = root / "trino.json"
            spark.write_text(json.dumps({
                "columns": ["category", "order_count", "total_amount"],
                "rows": [{"category": "B", "order_count": "2", "total_amount": 20},
                         {"category": "A", "order_count": 2, "total_amount": "30.5"},
                         {"category": "C", "order_count": 1, "total_amount": "4.00"}],
            }), encoding="utf-8")
            trino.write_text(json.dumps([["C", 1, "4.00"], ["A", 2, "30.50"], ["B", 2, "20.00"]]), encoding="utf-8")
            result = fixture.reconcile(fixture_dir, spark, trino, root / "report.json")
            self.assertEqual(result["status"], "passed")
            self.assertTrue(result["engines"]["spark"]["matchesExpected"])
            self.assertTrue((root / "report.json").exists())

    def test_reconcile_accepts_windows_powershell_utf8_bom(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            fixture_dir = root / "fixture"
            fixture.generate(fixture_dir)
            spark = root / "spark.json"
            trino = root / "trino.json"
            payload = json.dumps(fixture.expected_contract())
            spark.write_text(payload, encoding="utf-8-sig")
            trino.write_text(payload, encoding="utf-8-sig")
            result = fixture.reconcile(fixture_dir, spark, trino, None)
            self.assertEqual(result["status"], "passed")

    def test_reconcile_rejects_wrong_checksum_and_mismatched_engine(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            fixture_dir = root / "fixture"
            fixture.generate(fixture_dir)
            good = root / "good.json"
            bad = root / "bad.json"
            good.write_text(json.dumps(fixture.expected_contract()), encoding="utf-8")
            bad.write_text(json.dumps([["A", 2, "30.50"], ["B", 2, "20.01"], ["C", 1, "4.00"]]), encoding="utf-8")
            result = fixture.reconcile(fixture_dir, good, bad, None)
            self.assertEqual(result["status"], "failed")
            self.assertFalse(result["engines"]["trino"]["matchesExpected"])

    def test_verify_cdc_accepts_current_state_after_mutation(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            fixture_dir = root / "fixture"
            fixture.generate(fixture_dir)
            result_path = root / "cdc.json"
            result_path.write_text(json.dumps({"rows": [
                {"id": 1006, "category": "C", "amount": "6.00"},
                {"id": 1002, "category": "A", "amount": 21.5},
                {"id": 1001, "category": "A", "amount": "10.00"},
                {"id": 1005, "category": "C", "amount": "4.00"},
                {"id": 1004, "category": "B", "amount": "12.75"},
            ]}), encoding="utf-8")
            result = fixture.verify_cdc(fixture_dir, result_path, root / "cdc-report.json")
            self.assertEqual(result["status"], "passed")
            self.assertEqual(result["sourceKind"], "postgres")
            self.assertTrue((root / "cdc-report.json").exists())

    def test_verify_cdc_rejects_deleted_row_reappearing(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            fixture_dir = root / "fixture"
            fixture.generate(fixture_dir)
            result_path = root / "cdc.json"
            result_path.write_text(json.dumps({"rows": fixture.expected_cdc_rows() + [[1003, "B", "7.25"]]}), encoding="utf-8")
            result = fixture.verify_cdc(fixture_dir, result_path, None)
            self.assertEqual(result["status"], "failed")

    def test_verify_cdc_reports_duplicate_keys_and_mysql_table(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            fixture_dir = root / "fixture"
            fixture.generate(fixture_dir)
            result_path = root / "cdc.json"
            rows = fixture.expected_cdc_rows()
            rows.append(rows[0])
            result_path.write_text(json.dumps({"rows": rows}), encoding="utf-8")
            result = fixture.verify_cdc(fixture_dir, result_path, None, "mysql")
            self.assertEqual(result["status"], "failed")
            self.assertEqual(result["bronzeTable"], "bronze.r1_orders_mysql_cdc")
            self.assertEqual(result["duplicateIds"], [1001])
            self.assertFalse(result["deduplication"]["passed"])

    def test_rebuild_script_requires_explicit_volume_reset(self):
        script = (ROOT / "scripts" / "r1-rebuild.ps1").read_text(encoding="utf-8")
        self.assertIn("SupportsShouldProcess", script)
        self.assertIn("[switch]$ResetVolumes", script)
        self.assertIn("[switch]$FreshProject", script)
        self.assertIn("Docker engine unavailable for FreshProject", script)
        self.assertIn("[switch]$AllowVolumeReset", script)
        self.assertIn("[string]$ProjectName", script)
        self.assertIn("[int]$PortOffset", script)
        self.assertIn("R1_SOURCE_MYSQL_PORT = 13306", script)
        self.assertIn("Set-Item -Path (\"Env:\" + $portName)", script)
        self.assertIn("[switch]$ForceFixture", script)
        self.assertIn("[switch]$RunAcceptance", script)
        self.assertIn("if ($ResetVolumes)", script)
        self.assertIn("down -v", script)
        self.assertIn("Refusing to change the R1 environment", script)
        self.assertIn("R1 empty-rebuild hard-gate acceptance passed", script)
        self.assertIn("RequireFreshRebuild requires ResetVolumes or FreshProject", script)
        self.assertIn("freshRebuild = [bool]$freshRebuild", script)
        self.assertIn("acceptance-manifest.json", script)
        self.assertIn("acceptance-failure.json", script)
        self.assertIn("R1Phase", script)
        self.assertIn("Write-AcceptanceFailure", script)
        self.assertIn("R1-empty-rebuild-hard-gate", script)
        self.assertIn("R1-cross-engine-runtime-gate", script)
        self.assertIn("connectorManifest", script)
        self.assertIn("results/flink.json", script)
        self.assertIn("Flink submission did not return JSON evidence", script)
        self.assertIn("r1-fixture.py", script)
        self.assertIn("R1 fixture generation failed; use -ForceFixture", script)

    def test_r2_smoke_is_explicit_about_reset_and_cleanup(self):
        script = (ROOT / "scripts" / "r2-postgres-cdc-smoke.ps1").read_text(encoding="utf-8")
        self.assertIn("SupportsShouldProcess", script)
        self.assertIn("[switch]$ResetSource", script)
        self.assertIn("Stop-CdcRemote", script)
        self.assertIn("verify-cdc", script)
        self.assertIn("SourceKind", script)
        self.assertIn("RestartTaskManagerAfterSnapshot", script)
        self.assertIn("Wait-CdcCheckpoint", script)
        self.assertIn("Restart-CdcTaskManager", script)
        self.assertIn("checkpointAfterRestart", script)
        self.assertIn("failureClass", script)
        self.assertIn("sourceKind", script)
        mysql_script = (ROOT / "scripts" / "r2-mysql-cdc-smoke.ps1").read_text(encoding="utf-8")
        self.assertIn("RestartTaskManagerAfterSnapshot", mysql_script)
        acceptance = (ROOT / "scripts" / "r2-cdc-acceptance.ps1").read_text(encoding="utf-8")
        self.assertIn('foreach ($source in @("postgres", "mysql"))', acceptance)
        self.assertIn("r2-cdc-acceptance-report.json", acceptance)
        self.assertIn("-Confirm:$false", acceptance)


if __name__ == "__main__":
    unittest.main()
