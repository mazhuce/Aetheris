import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]


class StrictPlatformAcceptanceTests(unittest.TestCase):
    def test_strict_entrypoint_rejects_manual_review(self):
        script = (ROOT / "scripts" / "platform-release-acceptance-strict.ps1").read_text(encoding="utf-8")
        self.assertIn("validate-runtime-evidence.py", script)
        self.assertIn("validate-platform-release-report.py", script)
        self.assertIn('if ($body.status -ne "passed")', script)
        self.assertIn("exit 2", script)


if __name__ == "__main__":
    unittest.main()
