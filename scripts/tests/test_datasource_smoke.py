import json
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]


class DataSourceSmokeContractTests(unittest.TestCase):
    def test_manifest_covers_eight_real_jdbc_families_without_passwords(self):
        manifest = json.loads((ROOT / "configs" / "datasource-smoke.example.json").read_text(encoding="utf-8"))
        self.assertEqual(len(manifest), 8)
        self.assertEqual(
            {entry["type"] for entry in manifest},
            {"postgresql", "mysql", "hive2", "sqlserver", "oracle", "clickhouse", "trino", "sqlite"},
        )
        for entry in manifest:
            self.assertNotIn("password", entry)

    def test_script_uses_non_mutating_test_endpoint_and_env_passwords(self):
        script = (ROOT / "scripts" / "datasource-smoke.ps1").read_text(encoding="utf-8")
        self.assertIn("/api/metadata/datasources/test", script)
        self.assertIn("passwordEnv", script)
        self.assertIn("SELECT", (ROOT / "services/metadata/src/main/java/com/aetheris/metadata/service/JdbcService.java").read_text(encoding="utf-8"))
        self.assertNotIn("/api/metadata/datasources/\"", script)


if __name__ == "__main__":
    unittest.main()
