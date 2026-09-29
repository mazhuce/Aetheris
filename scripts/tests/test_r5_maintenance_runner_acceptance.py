import json
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]


class R5MaintenanceRunnerAcceptanceTests(unittest.TestCase):
    def setUp(self):
        self.script = (ROOT / "scripts" / "r5-maintenance-runner-acceptance.sh").read_text(encoding="utf-8")
        self.report = json.loads(
            (ROOT / "artifacts" / "r5-maintenance-runner" / "r5-maintenance-runtime-report.json").read_text(
                encoding="utf-8"
            )
        )

    def test_script_covers_real_runner_lifecycle_and_cleanup(self):
        for marker in (
            "R5-maintenance-runner-runtime-gate",
            "AETHERIS_RUNNER_SPARK_CATALOG_MODE=rest",
            "v1/jobs",
            "remove_orphan_files",
            "rewrite_data_files",
            "rollback",
            "schemaChange",
            "AWS_SHARED_CREDENTIALS_FILE",
            "cleanup_runner",
        ):
            self.assertIn(marker, self.script)

    def test_report_is_passed_with_all_real_maintenance_phases(self):
        self.assertEqual(self.report["status"], "passed")
        self.assertEqual(self.report["gate"], "R5-maintenance-runner-runtime-gate")
        self.assertEqual(self.report["sparkVersion"], "3.5.6")
        self.assertEqual(self.report["icebergRuntime"], "1.7.1")
        self.assertEqual(self.report["catalogMode"], "rest")
        phases = self.report["phases"]
        for phase in (
            "setup",
            "storageEstimate",
            "timeTravel",
            "rewriteDataFiles",
            "expireSnapshots",
            "removeOrphanFiles",
            "rollback",
            "schemaChange",
            "verify",
            "cleanup",
        ):
            self.assertEqual(phases[phase]["status"], "succeeded", phase)
        self.assertEqual(phases["setup"]["result"]["snapshotCount"], 2)
        self.assertEqual(phases["timeTravel"]["result"]["rows"][0]["row_count"], 2)
        self.assertEqual(phases["removeOrphanFiles"]["result"]["rowCount"], 0)
        self.assertTrue(phases["verify"]["result"]["hasIngestedAt"])
        serialized = json.dumps(self.report).lower()
        self.assertNotIn("minio_secret", serialized)
        self.assertNotIn("aws_secret_access_key", serialized)


if __name__ == "__main__":
    unittest.main()
