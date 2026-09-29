import json
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]


class ExternalDatasourceAcceptanceTests(unittest.TestCase):
    def test_manifest_declares_nine_real_types_without_credentials(self):
        manifest = json.loads((ROOT / "configs" / "datasource-smoke-9.example.json").read_text(encoding="utf-8"))
        self.assertEqual(len(manifest), 9)
        self.assertEqual(
            {entry["type"] for entry in manifest},
            {"postgresql", "mysql", "mariadb", "hive2", "sqlserver", "oracle", "clickhouse", "trino", "sqlite"},
        )
        for entry in manifest:
            self.assertNotIn("password", entry)
            self.assertTrue(entry["required"])

    def test_gate_requires_all_types_and_persists_report(self):
        script = (ROOT / "scripts" / "datasource-external-acceptance.ps1").read_text(encoding="utf-8")
        self.assertIn("-RequireAll", script)
        self.assertIn("missingRequiredTypes", script)
        self.assertIn("real-connection-required", script)
        self.assertIn("external-9-runtime-report.json", script)


if __name__ == "__main__":
    unittest.main()
