import importlib.util
import json
import hashlib
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
SPEC = importlib.util.spec_from_file_location("validate_runtime_evidence", ROOT / "scripts" / "validate-runtime-evidence.py")
validator = importlib.util.module_from_spec(SPEC)
assert SPEC.loader is not None
SPEC.loader.exec_module(validator)
HISTORICAL_FIXTURES = {
    "compose": "compose.original.txt",
    "compatibilityMatrix": "compatibility-matrix.json",
    "bom": "bom.original.toml",
    "connectorAllowlist": "connector-allowlist.original.toml",
    "connectorManifest": "connector-manifest.original.json",
    "tableContract": "table-contract.original.toml",
    "fixtureManifest": "fixture-manifest.original.json",
    "flinkSubmission": "flink-submission.original.json",
}
CURRENT_SOURCE_DRIFT = r"R1 artifact \w+ SHA-256 does not match file"


class RuntimeEvidenceTests(unittest.TestCase):
    def test_historical_r1_is_rejected_as_current_source_evidence(self):
        with self.assertRaisesRegex(ValueError, CURRENT_SOURCE_DRIFT):
            validator.validate_r1(ROOT / "artifacts/r1-empty-fixture/acceptance-manifest.json")

    def test_saved_r2_passes_content_audit_only(self):
        validator.validate_r2(ROOT / "artifacts/r2-cdc/r2-cdc-acceptance-report.json")

    def test_historical_original_artifacts_pass_only_explicit_historical_scope(self):
        report = ROOT / "artifacts/r1-empty-fixture/acceptance-manifest.json"
        original = json.loads(report.read_text(encoding="utf-8"))
        with tempfile.TemporaryDirectory() as directory:
            archived = Path(directory)
            for name, evidence in original["artifacts"].items():
                relative = evidence["path"].replace(chr(92), "/")
                raw = (ROOT / "scripts/tests/fixtures/r1-history" / HISTORICAL_FIXTURES[name]).read_bytes()
                # Only real original bytes; never rewrite the report to fit fixtures.
                self.assertEqual(evidence["sha256"], hashlib.sha256(raw).hexdigest(), name)
                target = archived / relative
                target.parent.mkdir(parents=True, exist_ok=True)
                target.write_bytes(raw)
            result = validator.audit_historical_r1(report, archived)
            self.assertEqual("historical-content-only", result["scope"])
            self.assertIs(False, result["currentAcceptance"])
            self.assertNotIn("status", result)
            wrapped = archived / "historical-audit.json"
            wrapped.write_text(json.dumps(result), encoding="utf-8")
            with self.assertRaisesRegex(ValueError, "not a passed empty-rebuild hard gate"):
                validator.validate_r1(wrapped)
            # A successful historical audit does not alter the current binding.
            with self.assertRaisesRegex(ValueError, CURRENT_SOURCE_DRIFT):
                validator.validate_r1(report)
            compose = archived / original["artifacts"]["compose"]["path"]
            compose.write_bytes(compose.read_bytes() + b"\n# changed after archive\n")
            with self.assertRaisesRegex(ValueError, "artifact compose SHA-256 does not match file"):
                validator.audit_historical_r1(report, archived)

    def test_original_fixtures_are_unconverted_with_windows_and_unix_git_settings(self):
        original = json.loads((ROOT / "artifacts/r1-empty-fixture/acceptance-manifest.json").read_text(encoding="utf-8"))
        self.assertEqual(set(original["artifacts"]), set(HISTORICAL_FIXTURES))
        for name, filename in HISTORICAL_FIXTURES.items():
            relative = "scripts/tests/fixtures/r1-history/" + filename
            raw = (ROOT / relative).read_bytes()
            with self.subTest(artifact=name):
                self.assertEqual(original["artifacts"][name]["sha256"], hashlib.sha256(raw).hexdigest())
                attributes = subprocess.check_output(["git", "check-attr", "text", "eol", "--", relative], cwd=ROOT, text=True)
                self.assertIn(relative + ": text: unset", attributes)
                # hash-object without -w cannot modify the object store/index.
                # --path actually executes clean filtering, unlike a text grep.
                expected = subprocess.check_output(["git", "hash-object", "--no-filters", "--stdin"], input=raw, cwd=ROOT).strip()
                for autocrlf in ("true", "false"):
                    filtered = subprocess.check_output(["git", "-c", "core.autocrlf=" + autocrlf,
                        "hash-object", "--path", relative, "--stdin"], input=raw, cwd=ROOT).strip()
                    self.assertEqual(expected, filtered, (name, autocrlf))

    def test_default_cli_cannot_accept_a_historical_report(self):
        result = subprocess.run([sys.executable, "-B", str(ROOT / "scripts/validate-runtime-evidence.py"),
            "--r1", str(ROOT / "artifacts/r1-empty-fixture/acceptance-manifest.json"),
            "--r2", str(ROOT / "artifacts/r2-cdc/r2-cdc-acceptance-report.json")],
            cwd=ROOT, capture_output=True, text=True, timeout=20)
        self.assertEqual(1, result.returncode)
        self.assertRegex(result.stderr, CURRENT_SOURCE_DRIFT)

    def test_schema_v2_requires_compatibility_matrix_artifact(self):
        original = json.loads((ROOT / "artifacts/r1-empty-fixture/acceptance-manifest.json").read_text(encoding="utf-8"))
        original["schemaVersion"] = 2
        original["artifacts"].pop("compatibilityMatrix", None)
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
        original = json.loads((ROOT / "artifacts/r2-cdc/r2-cdc-acceptance-report.json").read_text(encoding="utf-8"))
        original["sources"]["mysql"]["restart"]["completed"] = False
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "r2.json"
            path.write_text(json.dumps(original), encoding="utf-8")
            with self.assertRaisesRegex(ValueError, "restart"):
                validator.validate_r2(path)


if __name__ == "__main__":
    unittest.main()
