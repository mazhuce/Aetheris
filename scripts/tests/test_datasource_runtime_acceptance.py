import json
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]


class DataSourceRuntimeAcceptanceTests(unittest.TestCase):
    def setUp(self):
        self.script = (ROOT / "scripts" / "datasource-runtime-acceptance.ps1").read_text(encoding="utf-8")
        self.compose = (ROOT / "infrastructure" / "datasources" / "docker-compose.smoke.yml").read_text(encoding="utf-8")

    def test_runtime_gate_covers_nine_types_and_cleans_rows(self):
        for data_source_type in ("postgresql", "mysql", "trino", "mariadb", "clickhouse", "cockroachdb", "sqlite", "h2", "derby"):
            self.assertIn(f'Type = "{data_source_type}"', self.script)
        self.assertIn("DELETE FROM metadata.data_source", self.script)
        self.assertIn("runtime-acceptance-report.json", self.script)
        self.assertIn('status = "passed"', self.script)

    def test_smoke_compose_is_pinned_and_loopback_only(self):
        for image in ("mariadb:11.4.3", "clickhouse/clickhouse-server:24.8.14.39-alpine", "cockroachdb/cockroach:v24.3.6"):
            self.assertIn(image, self.compose)
        self.assertNotIn('"0.0.0.0:', self.compose)
        self.assertIn("127.0.0.1:13308", self.compose)
        self.assertIn("127.0.0.1:18123", self.compose)
        self.assertIn("127.0.0.1:26258", self.compose)

    def test_health_check_uses_compose_service_labels_for_custom_projects(self):
        self.assertIn('com.docker.compose.service', self.script)
        self.assertIn(r'^(mariadb|clickhouse|cockroachdb)\s+Up .*healthy', self.script)
        self.assertNotIn(r'datasource-smoke-(mariadb|clickhouse|cockroachdb)-1\s+Up .*healthy', self.script)

    def test_report_has_no_credentials(self):
        report_path = ROOT / "artifacts" / "datasource-smoke" / "runtime-acceptance-report.json"
        report = json.loads(report_path.read_text(encoding="utf-8"))
        self.assertIn(report["status"], {"passed", "failed"})
        self.assertIn("blockingCondition", report)
        self.assertIn("retryable", report)
        self.assertIn("docker", report)
        self.assertIn(report["docker"].get("engineStatus"), {"unknown", "available"})
        if report["status"] == "passed":
            self.assertEqual(set(report["testedTypes"]), {"postgresql", "mysql", "trino", "mariadb", "clickhouse", "cockroachdb", "sqlite", "h2", "derby"})
            self.assertTrue(report["cleanup"]["datasourceRowsRemoved"])
        else:
            self.assertTrue(report.get("error"))
        serialized = json.dumps(report).lower()
        self.assertNotIn("password", serialized)


if __name__ == "__main__":
    unittest.main()
