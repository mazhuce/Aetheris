import json
import re
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
REQUIRED_EXTERNAL_TYPES = {
    "postgresql", "mysql", "mariadb", "hive2", "sqlserver", "oracle", "clickhouse", "trino", "sqlite"
}


class DataSourceCatalogAlignmentTests(unittest.TestCase):
    def test_nine_source_manifest_is_present_in_backend_catalog(self):
        manifest = json.loads((ROOT / "configs" / "datasource-smoke-9.example.json").read_text(encoding="utf-8"))
        manifest_types = {entry["type"] for entry in manifest}
        java = (ROOT / "services/metadata/src/main/java/com/aetheris/metadata/service/DataSourceTypeCatalog.java").read_text(encoding="utf-8")
        backend_types = set(re.findall(r'definition\("([a-z0-9]+)"', java))
        self.assertEqual(manifest_types, REQUIRED_EXTERNAL_TYPES)
        self.assertTrue(REQUIRED_EXTERNAL_TYPES.issubset(backend_types))

    def test_nine_source_manifest_is_present_in_frontend_catalog(self):
        ts = (ROOT / "apps/web/src/pages/datasource/sourceCatalog.ts").read_text(encoding="utf-8")
        frontend_types = set(re.findall(r"value: '([a-z0-9]+)'", ts))
        self.assertTrue(REQUIRED_EXTERNAL_TYPES.issubset(frontend_types))

    def test_manifest_has_environment_only_credentials(self):
        manifest = json.loads((ROOT / "configs" / "datasource-smoke-9.example.json").read_text(encoding="utf-8"))
        for entry in manifest:
            self.assertNotIn("password", entry)
            if entry.get("passwordEnv"):
                self.assertRegex(entry["passwordEnv"], r"^AETHERIS_SMOKE_[A-Z0-9_]+$")


if __name__ == "__main__":
    unittest.main()
