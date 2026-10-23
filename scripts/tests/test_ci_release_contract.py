import importlib.util
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[2]


class CiReleaseGateTests(unittest.TestCase):
    def setUp(self):
        spec = importlib.util.spec_from_file_location("ci_release_gate", ROOT / "scripts/verify-ci-run.py")
        self.gate = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(self.gate)
        self.sha = "a" * 40
        self.repo = "owner/repository"

    def run_body(self, identifier=10, **updates):
        body = {"id": identifier, "head_sha": self.sha, "event": "push", "status": "completed", "conclusion": "success",
                "run_attempt": 1, "path": ".github/workflows/ci.yml", "head_repository": {"full_name": self.repo}}
        body.update(updates)
        return body

    def jobs(self):
        return [{"name": name, "status": "completed", "conclusion": "success", "head_sha": self.sha} for name in self.gate.REQUIRED_JOBS]

    def test_new_failure_is_not_hidden_by_old_success(self):
        with self.assertRaisesRegex(ValueError, "failed"):
            self.gate.select_run([self.run_body(), self.run_body(11, conclusion="failure")], self.repo, self.sha)

    def test_missing_pending_and_in_progress_require_waiting(self):
        self.assertIsNone(self.gate.select_run([], self.repo, self.sha))
        for status in ("queued", "in_progress", "waiting"):
            self.assertIsNone(self.gate.select_run([self.run_body(status=status, conclusion=None)], self.repo, self.sha))

    def test_wrong_commit_fork_and_nonpush_do_not_qualify(self):
        for changes in ({"head_sha": "b" * 40}, {"head_repository": {"full_name": "fork/repository"}}, {"event": "pull_request"}):
            self.assertIsNone(self.gate.select_run([self.run_body(**changes)], self.repo, self.sha))

    def test_skipped_missing_duplicate_or_failed_job_is_not_complete_ci(self):
        good = self.jobs()
        self.gate.validate_jobs(good, self.sha)
        for jobs in (good[:-1], good + [good[0]], [dict(job, conclusion="skipped") for job in good], [dict(job, conclusion="failure") for job in good]):
            with self.assertRaises(ValueError):
                self.gate.validate_jobs(jobs, self.sha)

    def test_successful_run_is_bound_to_attempt_and_jobs(self):
        selected = self.gate.select_run([self.run_body()], self.repo, self.sha)
        self.assertEqual(selected["id"], 10)
        self.gate.validate_run(selected, self.repo, self.sha, expected_attempt=1)
        with self.assertRaises(ValueError):
            self.gate.validate_run(dict(selected, run_attempt=2), self.repo, self.sha, expected_attempt=1)

    def test_wait_handles_ci_creation_queue_and_progress_with_a_deadline(self):
        states = [[], [self.run_body(status="queued", conclusion=None)], [self.run_body(status="in_progress", conclusion=None)], [self.run_body()]]
        clock = [0]
        def fetch(path):
            if "/workflows/ci.yml/runs?" in path:
                return {"workflow_runs": states.pop(0) if len(states) > 1 else states[0]}
            if path.endswith("/jobs?per_page=100&page=1"): return {"jobs": self.jobs()}
            return self.run_body()
        result = self.gate.wait_for_ci(self.repo, self.sha, wait_seconds=60, fetch=fetch,
                                       sleep=lambda seconds: clock.__setitem__(0, clock[0] + seconds), monotonic=lambda: clock[0])
        self.assertEqual(result["id"], 10)
        self.assertEqual(clock[0], 45)
        with self.assertRaisesRegex(ValueError, "deadline"):
            self.gate.wait_for_ci(self.repo, self.sha, wait_seconds=0, fetch=lambda p: {"workflow_runs": []})

    def test_new_failed_run_appearing_during_job_check_is_not_ignored(self):
        list_calls = [0]
        def fetch(path):
            if "/workflows/ci.yml/runs?" in path:
                list_calls[0] += 1
                return {"workflow_runs": [self.run_body()] if list_calls[0] == 1 else [self.run_body(), self.run_body(11, conclusion="failure")]}
            if "/jobs?" in path: return {"jobs": self.jobs()}
            return self.run_body()
        with self.assertRaisesRegex(ValueError, "failed"):
            self.gate.wait_for_ci(self.repo, self.sha, fetch=fetch)
