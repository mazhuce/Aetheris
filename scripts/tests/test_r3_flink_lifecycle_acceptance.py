import unittest
import json
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]


class R3FlinkLifecycleAcceptanceTests(unittest.TestCase):
    def setUp(self):
        self.script = (ROOT / "scripts" / "r3-flink-lifecycle-acceptance.ps1").read_text(encoding="utf-8")
        self.fixture = (ROOT / "scripts" / "r1-fixture.py").read_text(encoding="utf-8")
        self.report = json.loads(
            (ROOT / "artifacts" / "r3-lifecycle" / "r3-ha-flink-lifecycle-report.json").read_text(encoding="utf-8")
        )

    def test_runner_covers_required_lifecycle_phases(self):
        for marker in (
            "R3-flink-lifecycle-runtime-gate",
            "Wait-Checkpoint",
            "Trigger-Savepoint",
            "stop-with-savepoint",
            "flink-taskmanager",
            "flink-jobmanager",
            "RecoveryJobManagerEndpoint",
            "SkipNetworkFaultInjection",
            "networkInterruption",
            "networkRecoveryObserved",
            "remoteUnavailableObserved",
            "unknownWasNotSuccess",
            "manual_review_required",
            "auto_reconciled",
            "replacementSqlSha256",
            "Wait-SourceContainerReady",
            "pg_isready",
        ):
            self.assertIn(marker, self.script)

    def test_runner_never_treats_jobmanager_outage_as_success(self):
        self.assertIn("if (-not $unknownObserved)", self.script)
        self.assertIn("unknownWasNotSuccess = $unknownObserved", self.script)
        self.assertIn("Invoke-RestMethod -Method Get -Uri \"$jobManager/jobs/$currentJobId\"", self.script)
        self.assertIn("networkOutageInjected = $true", self.script)

    def test_fixture_has_savepoint_compatible_r3_sql(self):
        self.assertIn("004_r3_flink_lifecycle.sql", self.fixture)
        self.assertIn("aetheris_r3_lifecycle", self.fixture)
        self.assertIn("CREATE TABLE IF NOT EXISTS bronze.r3_orders_lifecycle", self.fixture)
        self.assertIn("execution.checkpointing.savepoint-dir", self.fixture)

    def test_runtime_report_proves_lifecycle_and_unknown_boundary(self):
        self.assertEqual(self.report["status"], "passed")
        self.assertEqual(self.report["gate"], "R3-flink-lifecycle-runtime-gate")
        for phase in ("submit", "checkpoint", "taskManagerRestart", "pause", "resume", "upgrade"):
            self.assertIn(self.report["phases"][phase]["status"], {"passed", "completed"}, phase)
        if "networkInterruption" in self.report["phases"]:
            self.assertEqual(self.report["phases"]["networkInterruption"]["status"], "passed")
        reconciliation = self.report["reconciliation"]
        self.assertTrue(reconciliation["unknownWasNotSuccess"])
        self.assertTrue(reconciliation["remoteUnavailableObserved"])
        self.assertEqual(reconciliation["mode"], "auto_reconciled")
        self.assertTrue(self.report["cleanup"]["completed"])


if __name__ == "__main__":
    unittest.main()
