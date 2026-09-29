import importlib.util
import json
import tempfile
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
SPEC = importlib.util.spec_from_file_location("validate_platform_release_report", ROOT / "scripts" / "validate-platform-release-report.py")
validator = importlib.util.module_from_spec(SPEC)
assert SPEC.loader is not None
SPEC.loader.exec_module(validator)


def report(status="manual_review_required"):
    runtime = [{"name": name, "status": "passed"} for name in validator.REQUIRED_RUNTIME]
    return {
        "gate": "platform-release-acceptance",
        "status": status,
        "offlineChecks": [{"status": "passed"}],
        "runtimeReports": runtime,
        "additionalGates": {
            "r3Ha": {"status": "passed"},
            "datasourceNine": {"status": "passed"},
        },
        "docker": {"available": True},
        "manualReviewRequired": [] if status == "passed" else ["r3-ha-runtime"],
    }


class PlatformReleaseReportTests(unittest.TestCase):
    def write(self, value):
        directory = tempfile.TemporaryDirectory()
        path = Path(directory.name) / "report.json"
        path.write_text(json.dumps(value), encoding="utf-8")
        self.addCleanup(directory.cleanup)
        return path

    def test_manual_review_report_is_structurally_valid_but_not_pass(self):
        validator.validate(self.write(report()))

    def test_passed_report_requires_all_real_gates(self):
        body = report("passed")
        validator.validate(self.write(body))

    def test_passed_report_cannot_hide_manual_review(self):
        body = report("passed")
        body["manualReviewRequired"] = ["r3-ha-runtime"]
        with self.assertRaisesRegex(ValueError, "manual review"):
            validator.validate(self.write(body))


if __name__ == "__main__":
    unittest.main()
