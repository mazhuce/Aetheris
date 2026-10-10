from __future__ import annotations

import subprocess
import sys
import tempfile
from pathlib import Path
import unittest


ROOT = Path(__file__).resolve().parents[2]
SCRIPT = ROOT / "scripts" / "verify-flyway-migrations.py"


class DeploymentGateTests(unittest.TestCase):
    def test_repository_migrations_have_unique_versions(self) -> None:
        result = subprocess.run(
            [sys.executable, str(SCRIPT), "--root", str(ROOT)],
            capture_output=True,
            text=True,
            check=False,
        )
        self.assertEqual(result.returncode, 0, result.stderr)

    def test_duplicate_version_fails(self) -> None:
        # The hosted Windows runner may deny the default user temp directory;
        # keep the fixture under the checked-out workspace instead.
        with tempfile.TemporaryDirectory(dir=ROOT) as temp:
            root = Path(temp)
            migration = root / "services" / "demo" / "src" / "main" / "resources" / "db" / "migration"
            migration.mkdir(parents=True)
            (migration / "V1__one.sql").write_text("select 1;", encoding="utf-8")
            (migration / "V1__two.sql").write_text("select 2;", encoding="utf-8")
            result = subprocess.run(
                [sys.executable, str(SCRIPT), "--root", str(root)],
                capture_output=True,
                text=True,
                check=False,
            )
            self.assertEqual(result.returncode, 1)
            self.assertIn("version V1 appears more than once", result.stderr)


if __name__ == "__main__":
    unittest.main()
