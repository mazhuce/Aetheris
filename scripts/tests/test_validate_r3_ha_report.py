import importlib.util
import json
import tempfile
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
SPEC = importlib.util.spec_from_file_location("validate_r3_ha_report", ROOT / "scripts" / "validate-r3-ha-report.py")
validator = importlib.util.module_from_spec(SPEC)
assert SPEC.loader is not None
SPEC.loader.exec_module(validator)


def valid_report():
    phases = {name: {"status": "passed"} for name in ("submit", "checkpoint", "pause", "resume", "upgrade", "networkInterruption")}
    return {
        "status": "passed",
        "gate": "R3-flink-lifecycle-runtime-gate",
        "phases": phases,
        "reconciliation": {
            "attempted": True,
            "outageInjected": True,
            "remoteUnavailableObserved": True,
            "unknownWasNotSuccess": True,
            "networkOutageInjected": True,
            "networkRecoveryObserved": True,
            "mode": "auto_reconciled",
        },
        "cleanup": {"completed": True},
    }


class R3HaReportValidatorTests(unittest.TestCase):
    def write(self, value):
        directory = tempfile.TemporaryDirectory()
        path = Path(directory.name) / "report.json"
        path.write_text(json.dumps(value), encoding="utf-8")
        self.addCleanup(directory.cleanup)
        return path

    def test_valid_report_passes(self):
        validator.validate(self.write(valid_report()))


    def test_checkpoint_completed_status_is_accepted(self):
        report = valid_report()
        report["phases"]["checkpoint"]["status"] = "completed"
        validator.validate(self.write(report))

    def test_unknown_without_explicit_reconciliation_fails(self):
        report = valid_report()
        report["reconciliation"]["unknownWasNotSuccess"] = False
        with self.assertRaisesRegex(ValueError, "unknownWasNotSuccess"):
            validator.validate(self.write(report))

    def test_success_without_network_recovery_fails(self):
        report = valid_report()
        report["reconciliation"]["networkRecoveryObserved"] = False
        with self.assertRaisesRegex(ValueError, "networkRecoveryObserved"):
            validator.validate(self.write(report))


if __name__ == "__main__":
    unittest.main()
