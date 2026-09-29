from __future__ import annotations

import argparse
import base64
import importlib.util
import io
import os
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


class EndedProcess(FakeProcess):
    def poll(self):
        return 0


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

    def test_child_exit_race_stays_observable_until_monitor_persists_result(self):
        with patch.object(runner_module.subprocess, "Popen", side_effect=FakeProcess) as popen, \
                patch.object(runner_module.threading.Thread, "start", return_value=None):
            submitted = self.runner.submit(self.request())
        job_id = submitted["id"]
        self.runner.processes[job_id] = EndedProcess()
        observed = self.runner.status(job_id)
        self.assertEqual("running", observed["status"])
        self.assertNotEqual("unknown", observed["status"])

    def test_redacts_sensitive_log_values(self):
        text = runner_module.redact("password=hunter2 Authorization: Bearer abc token:xyz")
        self.assertNotIn("hunter2", text)
        self.assertNotIn("abc", text)
        self.assertNotIn("xyz", text)
        self.assertEqual(3, text.count("[REDACTED]"))

    def test_uploads_idempotent_pyspark_artifact_and_rejects_collision(self):
        content = b"print('uploaded')\n"
        digest = runner_module.hashlib.sha256(content).hexdigest()
        body = {"sha256": digest, "contentBase64": base64.b64encode(content).decode("ascii")}
        result = self.runner.upload_artifact("uploaded_job.py", body)
        self.assertEqual(digest, result["sha256"])
        self.assertEqual(content, (self.artifacts / "uploaded_job.py").read_bytes())
        self.assertEqual(result, self.runner.upload_artifact("uploaded_job.py", body))

        collision = {"sha256": "0" * 64, "contentBase64": base64.b64encode(b"other").decode("ascii")}
        with self.assertRaises(runner_module.RunnerError) as error:
            self.runner.upload_artifact("uploaded_job.py", collision)
        self.assertEqual(409, error.exception.status)

    def test_submits_jar_with_main_class_and_rejects_missing_main_class(self):
        content = b"fake-jar"
        digest = runner_module.hashlib.sha256(content).hexdigest()
        self.runner.upload_artifact("orders_job.jar", {
            "sha256": digest, "contentBase64": base64.b64encode(content).decode("ascii")
        })
        request = self.request()
        request.update({"artifactKey": "orders_job.jar", "artifactDigest": digest,
                        "artifactType": "spark-jar", "mainClass": "com.acme.OrdersJob"})
        with patch.object(runner_module.subprocess, "Popen", side_effect=FakeProcess) as popen, \
                patch.object(runner_module.threading.Thread, "start", return_value=None):
            self.runner.submit(request)
        command = popen.call_args.args[0]
        self.assertIn("--class", command)
        self.assertIn("com.acme.OrdersJob", command)

        request["requestKey"] = "attempt-8-f1"
        request.pop("mainClass")
        with self.assertRaises(runner_module.RunnerError) as error:
            self.runner.submit(request)
        self.assertEqual(400, error.exception.status)

    def test_maintenance_contract_is_forwarded_without_allowing_sql_injection(self):
        request = self.request()
        request["requestKey"] = "maintenance-7"
        request["parameters"]["maintenance"] = {
            "operation": "rewrite_data_files",
            "statement": "CALL rest.system.rewrite_data_files(table => 'bronze.orders')",
        }
        with patch.object(runner_module.subprocess, "Popen", side_effect=FakeProcess) as popen, \
                patch.object(runner_module.threading.Thread, "start", return_value=None):
            self.runner.submit(request)
        command = popen.call_args.args[0]
        self.assertIn("--maintenance-operation", command)
        self.assertIn("rewrite_data_files", command)
        self.assertIn("--maintenance-statement", command)
        self.assertIn("CALL rest.system.rewrite_data_files(table => 'bronze.orders')", command)

        unsafe = self.request()
        unsafe["requestKey"] = "maintenance-8"
        unsafe["parameters"]["maintenance"] = {
            "operation": "rollback",
            "statement": "CALL rest.system.rollback_to_snapshot('x'); DROP TABLE x",
        }
        with self.assertRaises(runner_module.RunnerError) as error:
            self.runner.submit(unsafe)
        self.assertEqual(400, error.exception.status)

    def test_schema_change_contract_is_forwarded_without_allowing_sql_injection(self):
        request = self.request()
        request["requestKey"] = "schema-change-7"
        request["parameters"]["schemaChange"] = {
            "operation": "add_column",
            "statement": "ALTER TABLE `lakehouse`.`bronze`.`orders` ADD COLUMN `customer_id` BIGINT",
        }
        with patch.object(runner_module.subprocess, "Popen", side_effect=FakeProcess) as popen, \
                patch.object(runner_module.threading.Thread, "start", return_value=None):
            self.runner.submit(request)
        command = popen.call_args.args[0]
        self.assertIn("--schema-change-operation", command)
        self.assertIn("add_column", command)
        self.assertIn("--schema-change-statement", command)
        self.assertIn("ALTER TABLE `lakehouse`.`bronze`.`orders` ADD COLUMN `customer_id` BIGINT", command)

        unsafe = self.request()
        unsafe["requestKey"] = "schema-change-8"
        unsafe["parameters"]["schemaChange"] = {
            "operation": "drop_column",
            "statement": "ALTER TABLE `lakehouse`.`bronze`.`orders` DROP COLUMN `customer_id`; DROP TABLE x",
        }
        with self.assertRaises(runner_module.RunnerError) as error:
            self.runner.submit(unsafe)
        self.assertEqual(400, error.exception.status)

    def test_rest_catalog_credentials_are_environment_only(self):
        request = self.request()
        request["requestKey"] = "rest-catalog-7"
        request["parameters"]["maintenance"] = {
            "operation": "storage_estimate",
            "statement": "SELECT COUNT(*) AS file_count FROM `iceberg`.`bronze`.`orders`.files",
        }
        values = {
            "AETHERIS_RUNNER_SPARK_CATALOG_MODE": "rest",
            "AETHERIS_RUNNER_CATALOG_NAME": "iceberg",
            "AETHERIS_RUNNER_CATALOG_URI": "http://catalog:8181",
            "AETHERIS_RUNNER_CATALOG_WAREHOUSE": "s3://warehouse/",
            "AETHERIS_RUNNER_S3_ENDPOINT": "http://object-store:9000",
            "AETHERIS_RUNNER_S3_REGION": "us-east-1",
            "AWS_ACCESS_KEY_ID": "test-access",
            "AWS_SECRET_ACCESS_KEY": "test-secret",
        }
        with patch.dict(os.environ, values, clear=False), \
                patch.object(runner_module.subprocess, "Popen", side_effect=FakeProcess) as popen, \
                patch.object(runner_module.threading.Thread, "start", return_value=None):
            self.runner.submit(request)
        command = popen.call_args.args[0]
        environment = popen.call_args.kwargs["env"]
        self.assertIn("spark.sql.catalog.iceberg.catalog-impl=org.apache.iceberg.rest.RESTCatalog", command)
        self.assertIn("spark.sql.catalog.iceberg.uri=http://catalog:8181", command)
        self.assertNotIn("test-secret", command)
        self.assertEqual("test-secret", environment["AWS_SECRET_ACCESS_KEY"])


if __name__ == "__main__":
    unittest.main()
