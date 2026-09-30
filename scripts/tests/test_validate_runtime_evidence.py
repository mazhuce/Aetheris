import importlib.util
import json
import tempfile
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
SPEC = importlib.util.spec_from_file_location("validate_runtime_evidence", ROOT / "scripts" / "validate-runtime-evidence.py")
validator = importlib.util.module_from_spec(SPEC)
assert SPEC.loader is not None
SPEC.loader.exec_module(validator)


class RuntimeEvidenceTests(unittest.TestCase):
    def test_current_r1_and_r2_reports_pass_content_audit(self):
        validator.validate_r1(ROOT / "artifacts/r1-empty-fixture/acceptance-manifest.json")
        validator.validate_r2(ROOT / "artifacts/r1-fixture/r2-cdc-acceptance-report.json")

    def test_schema_v2_requires_compatibility_matrix_artifact(self):
        original = json.loads((ROOT / "artifacts/r1-empty-fixture/acceptance-manifest.json").read_text(encoding="utf-8"))
        original["schemaVersion"] = 2
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "r1.json"
            path.write_text(json.dumps(original), encoding="utf-8")
            with self.assertRaisesRegex(ValueError, "artifact evidence is incomplete"):
                validator.validate_r1(path)

    def test_r1_checksum_mismatch_is_rejected(self):
        original = json.loads((ROOT / "artifacts/r1-empty-fixture/acceptance-manifest.json").read_text(encoding="utf-8"))
        original["engineChecksums"]["trino"] = "0" * 64
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "r1.json"
            path.write_text(json.dumps(original), encoding="utf-8")
            with self.assertRaisesRegex(ValueError, "checksum"):
                validator.validate_r1(path)


    def test_r1_artifact_file_digest_mismatch_is_rejected(self):
        original = json.loads((ROOT / "artifacts/r1-empty-fixture/acceptance-manifest.json").read_text(encoding="utf-8"))
        original["artifacts"]["bom"]["sha256"] = "0" * 64
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "r1.json"
            path.write_text(json.dumps(original), encoding="utf-8")
            with self.assertRaisesRegex(ValueError, "does not match file"):
                validator.validate_r1(path)

    def test_r1_artifact_path_escape_is_rejected(self):
        original = json.loads((ROOT / "artifacts/r1-empty-fixture/acceptance-manifest.json").read_text(encoding="utf-8"))
        original["artifacts"]["bom"]["path"] = "../outside.json"
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "r1.json"
            path.write_text(json.dumps(original), encoding="utf-8")
            with self.assertRaisesRegex(ValueError, "escapes repository root"):
                validator.validate_r1(path)

    def test_r2_missing_restart_evidence_is_rejected(self):
        original = json.loads((ROOT / "artifacts/r1-fixture/r2-cdc-acceptance-report.json").read_text(encoding="utf-8"))
        original["sources"]["mysql"]["restart"]["completed"] = False
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "r2.json"
            path.write_text(json.dumps(original), encoding="utf-8")
            with self.assertRaisesRegex(ValueError, "restart"):
                validator.validate_r2(path)


if __name__ == "__main__":
    unittest.main()
