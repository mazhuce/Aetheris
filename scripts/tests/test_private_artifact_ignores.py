from pathlib import Path
import subprocess
import unittest

ROOT = Path(__file__).resolve().parents[2]


class PrivateArtifactIgnoreTests(unittest.TestCase):
    def ignored(self, name):
        result = subprocess.run(["git", "check-ignore", "--no-index", "--quiet", "--", name], cwd=ROOT)
        self.assertIn(result.returncode, (0, 1))
        return result.returncode == 0

    def test_private_runtime_files_and_backup_are_ignored(self):
        for name in ("artifacts/r5-maintenance-runner/state/aws-credentials", "artifacts/r5-maintenance-runner/state/runner-token",
                     "artifacts/another/state/request.json", "backups/fixture.dump", "fixture.dump", ".env.bak.portfix", "configs/lakehouse/.env.r1"):
            with self.subTest(name=name):
                self.assertTrue(self.ignored(name))

    def test_examples_and_public_reports_remain_eligible(self):
        for name in (".env.example", "configs/lakehouse/.env.r1.example", "configs/datasource-smoke.env.example",
                     "artifacts/platform-release/platform-release-acceptance-report.json"):
            with self.subTest(name=name):
                self.assertFalse(self.ignored(name))
