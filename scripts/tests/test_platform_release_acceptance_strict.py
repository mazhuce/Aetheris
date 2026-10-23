import json
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]
PWSH = shutil.which("pwsh")

@unittest.skipUnless(PWSH, "PowerShell is required")
class StrictPlatformAcceptanceTests(unittest.TestCase):
    def test_strict_rejects_historical_and_nonpassed_reports_with_nonzero_exit(self):
        with tempfile.TemporaryDirectory(prefix=".release-test-", dir=ROOT / "scripts/tests") as directory:
            report = Path(directory) / "report.json"
            for status in ("passed", "failed", "manual_review_required"):
                with self.subTest(status=status):
                    original = json.dumps({"schemaVersion": 1, "status": status})
                    report.write_text(original, encoding="utf-8")
                    result = subprocess.run([PWSH, "-NoProfile", "-File", str(ROOT / "scripts/platform-release-acceptance-strict.ps1"),
                                             "-ValidateExisting", "-ReportPath", str(report)], capture_output=True, text=True, encoding="utf-8", errors="replace")
                    self.assertNotEqual(result.returncode, 0)
                    self.assertEqual(report.read_text(encoding="utf-8"), original)

    def test_aggregate_requires_explicit_context_and_manifest(self):
        result = subprocess.run([PWSH, "-NoProfile", "-File", str(ROOT / "scripts/platform-release-acceptance.ps1"), "-OfflineOnly"],
                                capture_output=True, text=True, encoding="utf-8", errors="replace")
        self.assertNotEqual(result.returncode, 0)