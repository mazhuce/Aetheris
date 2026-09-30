import json
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]


class R4FlinkEventTimeQualityTests(unittest.TestCase):
    def setUp(self):
        self.script = (ROOT / "scripts" / "r4-flink-event-time-quality-acceptance.ps1").read_text(encoding="utf-8")

    def test_acceptance_is_a_real_runtime_gate(self):
        for marker in (
            "R4-flink-event-time-quality-runtime-gate",
            "ProjectName",
            "$ProjectName-source-postgres-1",
            "([string]$_.state).ToUpperInvariant() -eq \"RUNNING\"",
            "postgres-cdc",
            "WATERMARK FOR event_time AS event_time - INTERVAL '5' SECOND",
            "CURRENT_WATERMARK(event_time)",
            "dead_letter",
            "r4.event_time.dead_letter",
            "gold-quality-gate",
            "failedClosed",
            "watermark.observedAt",
            "Invoke-TrinoRows",
            "Flink event-time route did not produce",
        ):
            self.assertIn(marker, self.script)

    def test_cleanup_and_secret_boundaries_are_explicit(self):
        self.assertIn("DROP TABLE IF EXISTS r4_fixture.$sourceTable", self.script)
        self.assertIn('$report.cleanup.jobStopped = $jobState -in @("CANCELED", "FAILED", "FINISHED")', self.script)
        self.assertIn("$report.cleanup.metadataRowsRemoved = $true", self.script)
        self.assertIn("SkipMetadataCallback", self.script)
        self.assertNotIn("R1_SOURCE_POSTGRES_PASSWORD=", self.script)

    def test_real_runtime_report_contains_engine_and_gold_evidence(self):
        report = json.loads((ROOT / "artifacts" / "r4-event-time" / "r4-event-time-quality-report.json").read_text(encoding="utf-8"))
        self.assertEqual(report["status"], "passed")
        self.assertEqual(report["counts"], {"accepted": 2, "deadLetter": 1, "total": 3})
        self.assertGreater(report["job"]["checkpointId"], 0)
        self.assertGreaterEqual(report["evidence"]["watermarkedRows"], 2)
        self.assertTrue(report["metadataCallback"]["failedClosed"])
        for key in ("sourceRowsRemoved", "replicationSlotRemoved", "icebergTableRemoved", "metadataRowsRemoved", "jobStopped"):
            self.assertTrue(report["cleanup"][key], key)


if __name__ == "__main__":
    unittest.main()
