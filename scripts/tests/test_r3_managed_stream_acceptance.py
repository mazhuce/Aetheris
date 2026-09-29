import json
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]


class R3ManagedStreamAcceptanceTests(unittest.TestCase):
    def test_gate_covers_managed_lifecycle_and_failure_recovery(self):
        script = (ROOT / "scripts" / "r3-managed-stream-acceptance.ps1").read_text(encoding="utf-8")
        for marker in (
            "R3-managed-flink-stream-runtime-gate",
            "automatic Job ID binding",
            "completed checkpoint",
            "stream lease was not renewed",
            "capacity admission expected HTTP 409",
            "flink-pause",
            "flink-resume",
            "flink-upgrade",
            "replacement SQL was not published as task definition version 2",
            "network outage to be marked unknown",
            "automatic network recovery",
            "JobManager cancellation reconciliation",
            "$networkFaultActive",
            "[string]$FlinkEndpoint = \"http://flink-sql-gateway:8083\"",
            "flinkEndpoint = $FlinkEndpoint",
        ):
            self.assertIn(marker, script)

    def test_latest_runtime_report_passed_all_gates_and_cleanup(self):
        report = json.loads(
            (ROOT / "artifacts" / "r3-lifecycle" / "r3-managed-stream-report.json").read_text(encoding="utf-8-sig")
        )
        self.assertEqual(report["schemaVersion"], 2)
        self.assertEqual(report["status"], "passed")
        self.assertIn("flinkEndpoint", report["configuration"])
        self.assertTrue(report["configuration"]["networkFaultInjection"])
        phases = report["phases"]
        self.assertGreater(phases["checkpoint"]["id"], 0)
        self.assertGreater(phases["resume"]["checkpoint"], 0)
        self.assertGreater(phases["upgrade"]["checkpoint"], 0)
        self.assertEqual(phases["upgrade"]["definitionVersion"], 2)
        self.assertTrue(phases["upgrade"]["replacementPublished"])
        self.assertEqual(
            len({phases["submit"]["jobId"], phases["resume"]["jobId"], phases["upgrade"]["jobId"]}), 3
        )
        self.assertEqual(phases["admission"], {"duplicateHttp": 409, "capacityHttp": 409})
        self.assertEqual(phases["networkUnknown"]["attemptStatus"], "unknown")
        self.assertTrue(phases["networkUnknown"]["wasNotSuccess"])
        self.assertEqual(phases["networkRecovery"]["status"], "running")
        self.assertEqual(phases["cancel"]["attemptStatus"], "cancelled")
        self.assertEqual(report["cleanup"], {"tasksDeleted": 2, "completed": True})


if __name__ == "__main__":
    unittest.main()
