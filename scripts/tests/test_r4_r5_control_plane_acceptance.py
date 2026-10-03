import json
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]


class R4R5ControlPlaneAcceptanceTests(unittest.TestCase):
    def setUp(self):
        self.script = (ROOT / "scripts" / "r4-r5-control-plane-acceptance.ps1").read_text(encoding="utf-8")
        self.report = json.loads(
            (ROOT / "artifacts" / "r4-r5-control-plane" / "runtime-acceptance-report.json").read_text(encoding="utf-8")
        )
        self.ui_summary = json.loads(
            (ROOT / "apps" / "web" / "public" / "acceptance" / "r4-r5-control-plane.json").read_text(encoding="utf-8")
        )

    def test_script_covers_control_plane_and_fail_closed_cleanup(self):
        for marker in (
            "R4-R5-lakehouse-control-plane-runtime-gate",
            "MetadataDbContainer",
            "/api/metadata/lakehouse/tables",
            "/schema-changes/",
            "/gold-quality-gate",
            "DELETE FROM metadata.lakehouse_schema_change",
            "DELETE FROM metadata.lakehouse_maintenance_job",
            "current snapshot pointer did not advance",
            "Gold quality gate did not fail closed",
            "rename_column",
            "drop_column",
            "narrow_type",
            "approval_required",
            "planRejected",
            "isolated",
            "apps/web/public/acceptance/r4-r5-control-plane.json",
            "secret-free summary",
        ):
            self.assertIn(marker, self.script)

    def test_report_is_passed_and_has_complete_evidence(self):
        self.assertEqual(self.report["status"], "passed")
        self.assertEqual(self.report["gate"], "R4-R5-lakehouse-control-plane-runtime-gate")
        self.assertEqual(self.report["snapshotIds"], ["1001", "1002"])
        self.assertEqual(self.report["evidence"]["table"]["currentSnapshotId"], "1002")
        self.assertEqual(self.report["evidence"]["snapshotCount"], 2)
        self.assertTrue(self.report["quality"]["blockedOnFailure"])
        self.assertTrue(self.report["quality"]["reopenedAfterPass"])
        policy = self.report["evidence"]["schemaPolicy"]
        self.assertEqual(policy["rename"]["decision"], "approval_required")
        self.assertEqual(policy["rename"]["reviewedStatus"], "approved")
        self.assertTrue(policy["rename"]["readyForExecution"])
        self.assertTrue(policy["rename"]["destructive"])
        self.assertEqual(policy["drop"]["reviewedStatus"], "rejected")
        self.assertTrue(policy["drop"]["planRejected"])
        self.assertEqual(policy["narrow"]["decision"], "isolate")
        self.assertEqual(policy["narrow"]["isolatedStatus"], "isolated")
        self.assertTrue(policy["narrow"]["planRejected"])
        self.assertTrue(self.report["cleanup"]["tableRowsRemoved"])
        self.assertEqual(self.ui_summary["status"], "passed")
        self.assertEqual(self.ui_summary["schemaPolicy"]["rename"]["status"], "approved")
        self.assertEqual(self.ui_summary["schemaPolicy"]["drop"]["status"], "rejected")
        self.assertTrue(self.ui_summary["schemaPolicy"]["drop"]["planRejected"])
        self.assertEqual(self.ui_summary["schemaPolicy"]["narrow"]["status"], "isolated")
        self.assertNotIn("statement", json.dumps(self.ui_summary).lower())
        serialized = json.dumps(self.report).lower()
        self.assertNotIn("password", serialized)
        self.assertNotIn("aetheris_internal_token", serialized)


if __name__ == "__main__":
    unittest.main()
