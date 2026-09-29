import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]


class PlatformBuildWorkflowTests(unittest.TestCase):
    def test_ci_covers_frontend_and_java_service_gates(self):
        workflow = (ROOT / ".github/workflows/platform-build.yml").read_text(encoding="utf-8")
        for marker in (
            "npm ci",
            "npm run type-check",
            "npm test -- --run",
            "npm run build",
            "mvn -q -f services/metadata/pom.xml test",
            "mvn -q -f services/workflow/pom.xml test",
            "java-version: '21'",
        ):
            self.assertIn(marker, workflow)


if __name__ == "__main__":
    unittest.main()
