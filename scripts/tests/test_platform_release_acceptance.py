import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]


class PlatformReleaseAcceptanceTests(unittest.TestCase):
    def setUp(self):
        self.script = (ROOT / "scripts" / "platform-release-acceptance.ps1").read_text(encoding="utf-8")

    def test_orchestrator_covers_all_runtime_reports_and_offline_gates(self):
        for marker in (
            "artifacts/r1-empty-fixture/acceptance-manifest.json",
            "artifacts/r1-fixture/acceptance-manifest.json",
            "artifacts/r1-fixture/r2-cdc-acceptance-report.json",
            "artifacts/r3-lifecycle/r3-managed-stream-report.json",
            "artifacts/r4-event-time/r4-event-time-quality-report.json",
            "artifacts/r4-r5-control-plane/runtime-acceptance-report.json",
            "artifacts/r5-maintenance-runner/r5-maintenance-runtime-report.json",
            "artifacts/datasource-smoke/runtime-acceptance-report.json",
            "artifacts/r3-ha/r3-ha-lifecycle-report.json",
            "artifacts/r3-ha-lifecycle/r3-ha-lifecycle-report.json",
            "artifacts/datasource-smoke/external-9-runtime-report.json",
            "manual_review_required",
        ):
            self.assertIn(marker, self.script)

    def test_docker_unavailable_is_not_success(self):
        self.assertIn('"manual_review_required"', self.script)
        self.assertIn("docker.available", self.script)
        self.assertIn("-not $OfflineOnly -and -not $docker.available", self.script)

    def test_explicit_additional_gate_failure_is_not_manual_review(self):
        self.assertIn("$additionalFailed", self.script)
        self.assertIn("$runtimeFailed.Count -gt 0 -or $additionalFailed.Count -gt 0", self.script)
        self.assertIn("failedAdditionalGates", self.script)


if __name__ == "__main__":
    unittest.main()
