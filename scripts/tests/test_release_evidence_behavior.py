"""Behavioral release gates; historical reports are never a pass oracle."""
import hashlib
import importlib.util
import json
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch
from types import SimpleNamespace
from datetime import datetime, timedelta, timezone
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
HELPER = ROOT / "scripts/release-evidence.py"


class ReleaseEvidenceTests(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory(prefix=".release-test-", dir=ROOT / "scripts/tests")
        self.addCleanup(self.directory.cleanup)
        self.root = Path(self.directory.name)
        (self.root / "source.txt").write_text("source", encoding="utf-8")
        spec = importlib.util.spec_from_file_location("release_evidence", HELPER)
        self.module = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(self.module)
        git_mock = patch.object(self.module, "git", side_effect=lambda root, *args:
                                b"a" * 40 if args[0] == "rev-parse" else b"source.txt\x00")
        git_mock.start()
        self.addCleanup(git_mock.stop)
        self.context = self.module.create_context(self.root, "test-run", {"image": "a" * 64}, "b" * 64)

    def test_worktree_change_invalidates_context(self):
        (self.root / "source.txt").write_text("changed", encoding="utf-8")
        with self.assertRaisesRegex(ValueError, "source"):
            self.module.validate_context(self.root, self.context)

    def test_wrong_commit_is_rejected(self):
        self.context["source"]["commit"] = "0" * 40
        with self.assertRaisesRegex(ValueError, "source"):
            self.module.validate_context(self.root, self.context)

    def test_missing_artifacts_or_config_cannot_create_context(self):
        for artifacts, config in (({}, "b" * 64), ({"image": "a" * 64}, "")):
            with self.assertRaises(ValueError):
                self.module.create_context(self.root, "run", artifacts, config)

    def entry(self, status="passed"):
        path = self.root / "artifacts/test/report.json"
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(json.dumps({"status": status, "generatedAtUtc": self.module.now_iso()}), encoding="utf-8")
        return {"path": "artifacts/test/report.json", "sha256": hashlib.sha256(path.read_bytes()).hexdigest(),
                "contextSha256": self.module.object_digest(self.context), "exitCode": 0,
                "startedAtUtc": self.context["startedAtUtc"], "finishedAtUtc": self.module.now_iso(), "status": status}

    def test_new_failed_attempt_never_falls_back_to_old_success(self):
        old = self.entry()
        self.assertEqual(self.module.validate_entry(self.root, self.context, old)["status"], "passed")
        with self.assertRaisesRegex(ValueError, "failed"):
            self.module.validate_entry(self.root, self.context, dict(old, status="failed", exitCode=1))

    def test_digest_replacement_and_wrong_run_are_rejected(self):
        entry = self.entry()
        (self.root / entry["path"]).write_text('{"status":"failed"}', encoding="utf-8")
        with self.assertRaisesRegex(ValueError, "digest"):
            self.module.validate_entry(self.root, self.context, entry)
        entry = self.entry()
        entry["contextSha256"] = "0" * 64
        with self.assertRaisesRegex(ValueError, "context"):
            self.module.validate_entry(self.root, self.context, entry)

    def test_missing_stale_future_and_escaping_reports_are_rejected(self):
        for changes in ({"path": "../outside.json"}, {"path": "artifacts/missing.json"},
                        {"finishedAtUtc": (datetime.now(timezone.utc) - timedelta(days=2)).isoformat()},
                        {"finishedAtUtc": (datetime.now(timezone.utc) + timedelta(hours=2)).isoformat()}):
            with self.subTest(changes=changes), self.assertRaises(ValueError):
                self.module.validate_entry(self.root, self.context, dict(self.entry(), **changes))

    def test_nonpassed_and_http_error_datasource_results_fail(self):
        types = self.module.DATASOURCE_TYPES
        body = {"status": "passed", "testedTypes": sorted(types),
                "results": [{"type": t, "status": "passed", "rowCount": 1} for t in sorted(types)],
                "cleanup": {"datasourceRowsRemoved": True, "smokeStackStopped": True}}
        self.module.validate_datasource(body)
        for change in ("failed", "missing", "cleanup", "error"):
            broken = json.loads(json.dumps(body))
            if change == "failed": broken["results"][0]["status"] = "failed"
            elif change == "missing": broken["results"].pop()
            elif change == "cleanup": broken["cleanup"]["datasourceRowsRemoved"] = False
            else: broken["results"][0]["error"] = "HTTP 200 application failure"
            with self.subTest(change=change), self.assertRaises(ValueError):
                self.module.validate_datasource(broken)

    def producer_paths(self):
        context = self.root / "artifacts/run/context.json"
        manifest = self.root / "artifacts/run/manifest.json"
        report = self.root / "artifacts/run/report.json"
        self.module.write(context, self.context)
        return context, manifest, report

    def test_producer_must_rewrite_report_and_records_failed_retry(self):
        context, manifest, report = self.producer_paths()
        command = [sys.executable, "-B", "-c", "from pathlib import Path; import sys,json; from datetime import datetime,timezone; Path(sys.argv[1]).write_text(json.dumps({'status':'passed','generatedAtUtc':datetime.now(timezone.utc).isoformat()}))", str(report)]
        code = self.module.run_producer(self.root, context, manifest, "r1-empty-rebuild", report, command)
        self.assertEqual(code, 0)
        entry = self.module.read(manifest)["evidence"]["r1-empty-rebuild"]
        self.module.validate_entry(self.root, self.context, entry)
        # A successful no-op must not restamp the previous report as a fresh run.
        code = self.module.run_producer(self.root, context, manifest, "r1-empty-rebuild", report, [sys.executable, "-B", "-c", "pass"])
        self.assertNotEqual(code, 0)
        latest = self.module.read(manifest)["evidence"]["r1-empty-rebuild"]
        self.assertEqual(latest["status"], "failed")
        self.assertNotEqual(latest["attemptId"], entry["attemptId"])
        with self.assertRaises(ValueError):
            self.module.validate_entry(self.root, self.context, latest)

    def test_producer_nonzero_cannot_reuse_previous_passed_report(self):
        context, manifest, report = self.producer_paths()
        self.module.write(report, {"status": "passed"})
        code = self.module.run_producer(self.root, context, manifest, "r2-cdc", report, [sys.executable, "-B", "-c", "raise SystemExit(7)"])
        self.assertEqual(code, 1)
        latest = self.module.read(manifest)["evidence"]["r2-cdc"]
        self.assertEqual(latest["exitCode"], 7)
        self.assertNotIn("sha256", latest)

    def test_existing_running_attempt_is_not_overwritten(self):
        context, manifest, report = self.producer_paths()
        self.module.write(manifest, {"schemaVersion": 2, "contextSha256": self.module.object_digest(self.context),
                                    "evidence": {"r2-cdc": {"status": "running", "attemptId": "first"}}})
        with self.assertRaisesRegex(ValueError, "running"):
            self.module.run_producer(self.root, context, manifest, "r2-cdc", report, [sys.executable, "-B", "-c", "pass"])
        self.assertEqual(self.module.read(manifest)["evidence"]["r2-cdc"]["attemptId"], "first")

    def complete_evidence(self):
        context_path, manifest_path, report_path = self.producer_paths()
        evidence = {}
        for name in self.module.REQUIRED_NAMES:
            path = self.root / "artifacts/run" / (name + ".json")
            body = {"status": "passed", "generatedAtUtc": self.module.now_iso()}
            if name == "datasource-nine-local-runtime":
                body.update({"testedTypes": sorted(self.module.DATASOURCE_TYPES),
                             "results": [{"type": t, "status": "passed", "rowCount": 1} for t in sorted(self.module.DATASOURCE_TYPES)],
                             "cleanup": {"datasourceRowsRemoved": True, "smokeStackStopped": True}})
            self.module.write(path, body)
            evidence[name] = {"path": path.relative_to(self.root).as_posix(), "status": "passed", "exitCode": 0,
                              "sha256": self.module.file_digest(path), "contextSha256": self.module.object_digest(self.context),
                              "startedAtUtc": self.context["startedAtUtc"], "finishedAtUtc": self.module.now_iso()}
        self.module.write(manifest_path, {"schemaVersion": 2, "contextSha256": self.module.object_digest(self.context), "evidence": evidence})
        checks_path = self.root / "artifacts/run/checks.json"
        self.module.write(checks_path, {"contextSha256": self.module.object_digest(self.context),
                                      "startedAtUtc": self.context["startedAtUtc"], "finishedAtUtc": self.module.now_iso(),
                                      "offlineChecks": [{"name": n, "status": "passed", "exitCode": 0} for n in sorted(self.module.OFFLINE_NAMES)],
                                      "docker": {"available": True}})
        real_loader = self.module.load_validator
        def loader(name):
            if name == "validate-runtime-evidence.py":
                return SimpleNamespace(validate_r1=lambda p: None, validate_r2=lambda p: None)
            if name == "audit-r3-ha-evidence.py":
                return SimpleNamespace(inspect=lambda p, age: {"status": "passed"})
            return real_loader(name)
        loader_mock = patch.object(self.module, "load_validator", side_effect=loader)
        loader_mock.start()
        self.addCleanup(loader_mock.stop)
        return context_path, manifest_path, checks_path, report_path

    def test_current_aggregate_passes_and_strict_reopens_exact_reports(self):
        context, manifest, checks, report = self.complete_evidence()
        self.assertEqual(self.module.aggregate(self.root, context, manifest, checks, report), 0)
        self.module.validate_aggregate(self.root, report)
        chosen = self.module.read(manifest)["evidence"]["r2-cdc"]["path"]
        self.module.write(self.root / chosen, {"status": "failed"})
        with self.assertRaises(ValueError):
            self.module.validate_aggregate(self.root, report)

    def test_strict_rejects_aggregate_selected_object_mismatch(self):
        context, manifest, checks, report = self.complete_evidence()
        self.assertEqual(self.module.aggregate(self.root, context, manifest, checks, report), 0)
        body = self.module.read(report)
        body["runtimeReports"][0]["path"] = "artifacts/run/r2-cdc.json"
        self.module.write(report, body)
        with self.assertRaisesRegex(ValueError, "selected"):
            self.module.validate_aggregate(self.root, report)

    def test_docker_unavailable_and_foreign_or_missing_offline_checks_never_pass(self):
        context, manifest, checks, report = self.complete_evidence()
        original = self.module.read(checks)
        offline = dict(original, docker={"available": False})
        self.module.write(checks, offline)
        self.assertEqual(self.module.aggregate(self.root, context, manifest, checks, report), 2)
        self.assertEqual(self.module.read(report)["status"], "manual_review_required")
        for broken in (dict(original, contextSha256="0" * 64), dict(original, offlineChecks=[])):
            self.module.write(checks, broken)
            self.assertEqual(self.module.aggregate(self.root, context, manifest, checks, report), 1)
            self.assertEqual(self.module.read(report)["status"], "failed")

    def test_missing_required_gate_or_reused_path_blocks_release(self):
        context, manifest, checks, report = self.complete_evidence()
        body = self.module.read(manifest)
        body["evidence"]["r3-managed-lifecycle"]["path"] = body["evidence"]["r2-cdc"]["path"]
        self.module.write(manifest, body)
        self.assertEqual(self.module.aggregate(self.root, context, manifest, checks, report), 1)
        del body["evidence"]["r3-managed-lifecycle"]
        self.module.write(manifest, body)
        self.assertEqual(self.module.aggregate(self.root, context, manifest, checks, report), 1)

    def test_failed_current_gate_refreshes_ui_summary_without_old_pass(self):
        context, manifest, checks, report = self.complete_evidence()
        summary = self.root / "artifacts/run/summary.json"
        self.module.write(summary, {"status": "passed"})
        body = self.module.read(manifest)
        body["evidence"]["r2-cdc"]["status"] = "failed"
        self.module.write(manifest, body)
        self.assertEqual(self.module.aggregate(self.root, context, manifest, checks, report, summary_path=summary), 1)
        self.assertEqual(self.module.read(summary)["status"], "failed")

    def test_malformed_evidence_and_nan_freshness_fail_closed(self):
        context, manifest, checks, report = self.complete_evidence()
        original = self.module.read(manifest)
        for malformed in ([], {"schemaVersion": 2, "contextSha256": self.module.object_digest(self.context), "evidence": []}):
            self.module.write(manifest, malformed)
            self.assertEqual(self.module.aggregate(self.root, context, manifest, checks, report), 1)
            self.assertEqual(self.module.read(report)["status"], "failed")
        self.module.write(manifest, original)
        self.assertEqual(self.module.aggregate(self.root, context, manifest, checks, report, max_age_hours=float("nan")), 1)

    def test_copying_a_historical_report_does_not_make_it_current(self):
        context, manifest, report = self.producer_paths()
        old = self.root / "artifacts/old.json"
        self.module.write(old, {"status": "passed", "generatedAtUtc": (datetime.now(timezone.utc) - timedelta(days=2)).isoformat()})
        command = [sys.executable, "-B", "-c", "from pathlib import Path; import sys; Path(sys.argv[2]).write_bytes(Path(sys.argv[1]).read_bytes())", str(old), str(report)]
        self.assertNotEqual(self.module.run_producer(self.root, context, manifest, "r1-empty-rebuild", report, command), 0)
        self.assertEqual(self.module.read(manifest)["evidence"]["r1-empty-rebuild"]["status"], "failed")


if __name__ == "__main__":
    unittest.main()
