"""Validate the UI summary's data contract without treating history as current proof."""
import json
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
STATUSES = {"passed", "failed", "manual_review_required", "missing", "invalid", "unavailable", "skipped"}

class PlatformReleaseAcceptanceTests(unittest.TestCase):
    def test_historical_frontend_summary_has_consistent_public_contract(self):
        body = json.loads((ROOT / "apps/web/public/acceptance/platform-release.json").read_text(encoding="utf-8-sig"))
        self.assertEqual(body["gate"], "platform-release-acceptance")
        self.assertIn(body["status"], STATUSES)
        self.assertEqual([item["code"] for item in body["milestones"]], ["R1", "R2", "R3", "R4", "R5"])
        for item in body["milestones"]:
            self.assertIn(item["status"], STATUSES)
            self.assertNotIn("password", json.dumps(item).lower())
        # Historical successful evidence is not asserted to still be passed.
        if body["status"] == "passed":
            self.assertTrue(all(item["status"] == "passed" for item in body["milestones"]))
            self.assertEqual(body["datasourceNineStatus"], "passed")
            self.assertFalse(body.get("manualReviewRequired"))