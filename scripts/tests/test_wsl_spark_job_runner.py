from __future__ import annotations

import argparse
import importlib.util
import io
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch


ROOT = Path(__file__).resolve().parents[2]
SPEC = importlib.util.spec_from_file_location(
    "aetheris_spark_runner", ROOT / "scripts" / "wsl-spark-job-runner.py"
)
assert SPEC and SPEC.loader
runner_module = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(runner_module)


class FakeProcess:
    pid = 4242

    def __init__(self, *args, **kwargs):
        self.args = args
        self.kwargs = kwargs
        self.stdout = io.BytesIO(b"")

    def poll(self):
        return None


class RunnerTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        root = Path(self.temp.name)
        self.artifacts = root / "artifacts"
        self.artifacts.mkdir()
        self.artifact = self.artifacts / "approved_job.py"
        self.artifact.write_text("print('safe')\n", encoding="utf-8")
        self.spark_submit = root / "spark-submit"
        self.spark_submit.write_text("stub", encoding="utf-8")
        self.token_file = root / "token"
        self.token_file.write_text("x" * 48, encoding="utf-8")
        args = argparse.Namespace(
            artifact_root=str(self.artifacts),
            state_root=str(root / "state"),
            spark_submit=str(self.spark_submit),
            token_file=str(self.token_file),
            max_concurrency=1,
            max_log_bytes=1024,
            default_timeout=120,
        )
        self.runner = runner_module.JobRunner(args)

    def tearDown(self):
        self.temp.cleanup()

    def request(self):
        return {
            "requestKey": "attempt-7-f1",
            "artifactKey": self.artifact.name,
            "artifactDigest": runner_module.sha256(self.artifact),
            "parameters": {"bizDate": "2026-09-08", "attemptId": "7"},
            "resources": {"cores": 1, "driverMemoryMb": 768, "timeoutSeconds": 120},
        }

    def test_rejects_digest_mismatch_and_parameter_injection(self):
        request = self.request()
        request["artifactDigest"] = "0" * 64
        with self.assertRaises(runner_module.RunnerError) as mismatch:
            self.runner.submit(request)
        self.assertEqual(409, mismatch.exception.status)

        request = self.request()
        request["parameters"]["sparkConf"] = "spark.executorEnv.SECRET=leak"
        with self.assertRaises(runner_module.RunnerError) as injection:
            self.runner.submit(request)
        self.assertEqual(400, injection.exception.status)

    def test_request_key_is_idempotent_and_child_does_not_receive_runner_token(self):
        with patch.object(runner_module.subprocess, "Popen", side_effect=FakeProcess) as popen, \
                patch.object(runner_module.threading.Thread, "start", return_value=None):
            first = self.runner.submit(self.request())
            second = self.runner.submit(self.request())

        self.assertEqual(first["id"], second["id"])
        self.assertEqual(1, popen.call_count)
        command = popen.call_args.args[0]
        environment = popen.call_args.kwargs["env"]
        self.assertIsInstance(command, list)
        self.assertNotIn("shell", popen.call_args.kwargs)
        self.assertNotIn("AETHERIS_SPARK_RUNNER_TOKEN", environment)
        self.assertNotIn("x" * 48, environment.values())

    def test_redacts_sensitive_log_values(self):
        text = runner_module.redact("password=hunter2 Authorization: Bearer abc token:xyz")
        self.assertNotIn("hunter2", text)
        self.assertNotIn("abc", text)
        self.assertNotIn("xyz", text)
        self.assertEqual(3, text.count("[REDACTED]"))


if __name__ == "__main__":
    unittest.main()
