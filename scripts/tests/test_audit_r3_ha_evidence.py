import importlib.util
import json
import tempfile
import unittest
from datetime import datetime, timezone
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
SPEC = importlib.util.spec_from_file_location("audit_r3_ha_evidence", ROOT / "scripts" / "audit-r3-ha-evidence.py")
audit = importlib.util.module_from_spec(SPEC)
assert SPEC.loader is not None
SPEC.loader.exec_module(audit)


def valid_report(finished="2026-09-28T00:00:00+00:00"):
    phases = {phase: {"status": "passed"} for phase in audit.REQUIRED_PHASES}
    phases["checkpoint"] = {"status": "completed", "id": 1}
    return {
        "gate": "R3-flink-lifecycle-runtime-gate",
        "status": "passed",
        "finishedAtUtc": finished,
        "phases": phases,
        "reconciliation": {
            "outageInjected": True,
            "remoteUnavailableObserved": True,
            "unknownWasNotSuccess": True,
            "mode": "auto_reconciled",
        },
    }


class R3HaEvidenceAuditTests(unittest.TestCase):
    def write(self, body):
        directory = tempfile.TemporaryDirectory()
        path = Path(directory.name) / "report.json"
        path.write_text(json.dumps(body), encoding="utf-8")
        self.addCleanup(directory.cleanup)
        return path

    def test_old_pass_is_manual_review_not_current_pass(self):
        result = audit.inspect(self.write(valid_report()), now=datetime(2026, 9, 29, 12, tzinfo=timezone.utc))
        self.assertEqual(result["status"], "manual_review_required")
        self.assertEqual(result["reason"], "historical_pass_requires_rerun")

    def test_recent_pass_is_accepted(self):
        result = audit.inspect(self.write(valid_report("2026-09-29T11:50:00+00:00")), now=datetime(2026, 9, 29, 12, tzinfo=timezone.utc))
        self.assertEqual(result["status"], "passed")

    def test_missing_unknown_boundary_fails_closed(self):
        body = valid_report()
        body["reconciliation"]["unknownWasNotSuccess"] = False
        result = audit.inspect(self.write(body), now=datetime(2026, 9, 29, 12, tzinfo=timezone.utc))
        self.assertEqual(result["status"], "failed")


if __name__ == "__main__":
    unittest.main()
