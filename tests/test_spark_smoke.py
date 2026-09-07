"""Regression checks for smoke safety, independent assertions and API sequence.

Run: python -m unittest discover -s tests -p test_spark_smoke.py
No Docker, Spark, network calls or environment credentials are needed.
"""

from contextlib import redirect_stdout, redirect_stderr
import importlib.util
import io
import json
from pathlib import Path
from tempfile import TemporaryDirectory
import unittest
from unittest.mock import Mock
from urllib.error import HTTPError


SPEC = importlib.util.spec_from_file_location("spark_smoke", Path(__file__).resolve().parents[1] / "scripts/spark-smoke-test.py")
smoke = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(smoke)
MARKER = "0123456789abcdef"


def result(columns, rows, **extra):
    return {"columns": columns, "rows": rows, "rowCount": len(rows), **extra}


class FixtureSafetyTests(unittest.TestCase):
    def test_rejects_business_identifiers_and_sql_injection(self):
        for value in ("default", "warehouse", "aetheris_smoke_test", "0123456789abcdeF", "a" * 17,
                      "0123456789abcdef\n", "x'; DROP TABLE orders;--", None):
            with self.subTest(value=value), self.assertRaises(smoke.SmokeFailure):
                smoke.fixture_name(value)

    def test_preparation_is_collision_failing_and_tiny(self):
        sql = smoke.fixture_sql(MARKER)
        self.assertIn("CREATE DATABASE aetheris_smoke_" + MARKER + " COMMENT", sql)
        statements = [line for line in sql.splitlines() if not line.startswith("--")]
        self.assertNotIn("IF NOT EXISTS", "\n".join(statements))
        self.assertNotIn("DROP", sql)
        self.assertEqual(sql.count("CREATE TABLE"), 2)
        self.assertEqual(sql.count("INSERT INTO"), 1)
        self.assertEqual(sql.count(smoke.OWNER_PROPERTY), 2)

    def test_overwrite_is_always_static_owned_partition(self):
        sql = smoke.write_sql(MARKER)
        self.assertTrue(sql.startswith("INSERT OVERWRITE TABLE aetheris_smoke_" + MARKER + ".target_totals"))
        self.assertIn("PARTITION (run_id='" + MARKER + "')", sql)
        self.assertNotIn(";", sql)
        self.assertNotIn("INSERT INTO", sql)

    def test_marker_mismatch_refuses_writes(self):
        with self.assertRaisesRegex(smoke.SmokeFailure, "ownership"):
            smoke.assert_owner(result(["key", "value"], [[smoke.OWNER_PROPERTY, "f" * 16]]), MARKER)

    def test_generator_does_not_overwrite_existing_sql(self):
        with TemporaryDirectory() as temporary:
            path = Path(temporary) / "fixture.sql"
            path.write_text("existing data", encoding="utf-8")
            with redirect_stderr(io.StringIO()):
                code = smoke.main(["--fixture-sql", str(path), "--fixture", MARKER])
            self.assertEqual(code, 1)
            self.assertEqual(path.read_text(encoding="utf-8"), "existing data")

    def test_invalid_marker_creates_no_empty_fixture_file(self):
        with TemporaryDirectory() as temporary:
            path = Path(temporary) / "fixture.sql"
            with redirect_stderr(io.StringIO()):
                self.assertEqual(smoke.main(["--fixture-sql", str(path), "--fixture", "business"]), 1)
            self.assertFalse(path.exists())


class ResultAssertionTests(unittest.TestCase):
    def test_independent_aggregation_with_different_input(self):
        self.assertEqual(smoke.independent_totals([[9, "C", 6], [1, "A", 5], [3, "C", -2]]),
                         [["A", 1, 5], ["C", 2, 4]])

    def test_accepts_hive_labels_and_integer_numeric_strings(self):
        self.assertEqual(smoke.assert_source(result(["s.id", "s.category", "s.amount"],
                         [["1", "A", "10"], [2, "A", 20], [3, "B", 7]])), smoke.SOURCE_ROWS)

    def test_rejects_extra_source_rows_and_truncation(self):
        for rows, extra in ((smoke.SOURCE_ROWS + [[4, "B", 9]], {}), (smoke.SOURCE_ROWS, {"truncated": True})):
            with self.subTest(extra=extra), self.assertRaises(smoke.SmokeFailure):
                smoke.assert_source(result(["id", "category", "amount"], rows, **extra))

    def test_rejects_duplicate_or_wrong_aggregate(self):
        expected = [["A", 2, 30], ["B", 1, 7]]
        for rows in (expected + [["A", 2, 30]], [["A", 4, 60], ["B", 2, 14]], [["A", 2, 31], ["B", 1, 7]]):
            with self.subTest(rows=rows), self.assertRaises(smoke.SmokeFailure):
                smoke.assert_target(result(["category", "order_count", "total_amount"], rows), expected)

    def test_rejects_rowcount_mismatch_and_non_integer_cells(self):
        bad = result(["smoke_one"], [[1]])
        bad["rowCount"] = 0
        with self.assertRaises(smoke.SmokeFailure):
            smoke.assert_select_one(bad)
        for value in (True, None, "NaN", "Infinity", "1.5", "secret"):
            with self.subTest(value=value), self.assertRaises(smoke.SmokeFailure):
                smoke.integer(value)


class TransportSafetyTests(unittest.TestCase):
    def test_url_rejects_cleartext_remote_and_embedded_credentials(self):
        for url in ("http://remote.example", "http://user:secret@localhost", "https://example.com?token=secret", "file:///tmp/test"):
            with self.subTest(url=url), self.assertRaises(smoke.SmokeFailure):
                smoke.gateway_url(url)
        self.assertEqual(smoke.gateway_url("http://localhost:8080/"), "http://localhost:8080")
        self.assertEqual(smoke.gateway_url("https://example.com"), "https://example.com")

    def test_server_errors_do_not_echo_credentials_or_retry(self):
        api = smoke.Api("http://localhost:8080")
        api.opener = Mock()
        api.opener.open.side_effect = HTTPError("http://localhost", 502, "password=secret", {}, io.BytesIO(b"token=secret"))
        with self.assertRaises(smoke.SmokeFailure) as failure:
            api.request("POST", "/api/metadata/query", {"sql": "SELECT 1"})
        self.assertEqual(str(failure.exception), "POST /api/metadata/query returned HTTP 502")
        self.assertEqual(api.opener.open.call_count, 1)

    def test_redirect_handler_never_forwards_credentials(self):
        self.assertIsNone(smoke.NoRedirect().redirect_request(None, None, 302, "", {}, "https://elsewhere.example"))


class FakeApi:
    def __init__(self):
        self.token = None
        self.calls = []
        self.allowed = True
        self.attempt = 0
        self.fail_run = False
        self.duplicate_output = False
        self.owner = MARKER
        self.fail_user_cleanup = False

    def login(self, username, password):
        return {"token": "admin-secret" if username == "admin" else "viewer-secret",
                "role": "admin" if username == "admin" else "viewer"}

    def request(self, method, path, payload=None, token=None, expected=(200,)):
        self.calls.append((method, path, payload, token, expected))
        if method == "DELETE":
            if self.fail_user_cleanup and path == "/api/auth/users/71":
                raise smoke.SmokeFailure("test cleanup failed")
            return None
        if path == "/api/metadata/datasources/5":
            return {"type": "spark"}
        if path.endswith("/test"):
            return {"success": True, "latencyMs": 1}
        if path.endswith("/schemas"):
            return [{"name": "aetheris_smoke_" + MARKER}]
        if "/tables?" in path:
            return [{"name": "source_orders"}, {"name": "target_totals"}]
        if "/columns?" in path:
            return [{"name": "id"}, {"name": "category"}, {"name": "amount"}]
        if path == "/api/auth/users":
            return {"id": 71}
        if path == "/api/auth/rbac/grants":
            return {"id": 81}
        if path == "/api/auth/rbac/grants/81":
            self.allowed = "query" in payload["actions"]
            return {"id": 81}
        if path == "/api/workflow/tasks":
            return {"task": {"id": 91}}
        if path.endswith("/run"):
            self.attempt += 1
            if self.fail_run:
                raise smoke.SmokeFailure("run timed out; not retried")
            return {"id": 101, "status": "success", "attempt": self.attempt}
        if path.endswith("/query"):
            if token == "viewer-secret" and not self.allowed:
                if expected != (403,):
                    raise AssertionError("unauthorized request expected success")
                return None
            if expected == (403,):
                raise AssertionError("authorized request incorrectly expected denial")
            sql = payload["sql"]
            if sql.startswith("SHOW TBLPROPERTIES"):
                return result(["key", "value"], [[smoke.OWNER_PROPERTY, self.owner]])
            if sql == "SELECT 1 AS smoke_one":
                return result(["smoke_one"], [[1]])
            if "SELECT id, category, amount" in sql:
                return result(["id", "category", "amount"], smoke.SOURCE_ROWS)
            if "SELECT category, order_count, total_amount" in sql:
                rows = [["A", 2, 30], ["B", 1, 7]]
                return result(["category", "order_count", "total_amount"], rows * (2 if self.duplicate_output else 1))
        raise AssertionError((method, path, payload))


class ExecutionContractTests(unittest.TestCase):
    def execute(self, api, writes=False):
        check = smoke.SparkSmoke(api, 5, MARKER, writes)
        with redirect_stdout(io.StringIO()):
            check.run("admin", "admin-password")
        return check

    def test_default_does_not_create_or_execute_workflow(self):
        api = FakeApi()
        check = self.execute(api)
        check.cleanup()
        self.assertFalse(any("workflow" in call[1] for call in api.calls))
        self.assertFalse(check.report["writeAcceptancePassed"])
        denies = [call for call in api.calls if call[4] == (403,)]
        self.assertEqual(len(denies), 2)
        self.assertEqual({call[3] for call in denies}, {"viewer-secret"})
        serialized = json.dumps(check.report)
        self.assertNotIn("admin-secret", serialized)
        self.assertNotIn("viewer-secret", serialized)
        self.assertNotIn("admin-password", serialized)

    def test_same_workflow_business_date_and_owned_partition_twice(self):
        api = FakeApi()
        check = self.execute(api, writes=True)
        runs = [call for call in api.calls if call[1].endswith("/run")]
        self.assertEqual(len(runs), 2)
        self.assertEqual(runs[0][1:3], runs[1][1:3])
        self.assertTrue(check.report["writeAcceptancePassed"])
        task = next(call[2] for call in api.calls if call[1] == "/api/workflow/tasks")
        self.assertEqual(task["maxRetries"], 0)
        self.assertIsNone(task["cronExpression"])
        self.assertEqual(task["taskSql"], smoke.write_sql(MARKER))
        check.cleanup()
        self.assertIn(("DELETE", "/api/workflow/tasks/91"), [(c[0], c[1]) for c in api.calls])

    def test_owner_failure_stops_before_task_or_temporary_account_creation(self):
        api = FakeApi()
        api.owner = "f" * 16
        with self.assertRaisesRegex(smoke.SmokeFailure, "ownership"):
            self.execute(api, writes=True)
        self.assertFalse(any(c[1] in ("/api/workflow/tasks", "/api/auth/users") for c in api.calls))

    def test_unknown_submission_is_not_retried_or_deleted(self):
        api = FakeApi()
        api.fail_run = True
        check = smoke.SparkSmoke(api, 5, MARKER, writes=True)
        with redirect_stdout(io.StringIO()), self.assertRaises(smoke.SmokeFailure):
            check.run("admin", "admin-password")
        check.report["status"] = "failed"
        check.cleanup()
        self.assertEqual(api.attempt, 1)
        self.assertTrue(check.run_unconfirmed)
        self.assertNotIn(("DELETE", "/api/workflow/tasks/91"), [(c[0], c[1]) for c in api.calls])
        self.assertEqual(check.report["cleanup"][0]["status"], "retained")

    def test_wrong_output_stops_before_second_run(self):
        api = FakeApi()
        api.duplicate_output = True
        with self.assertRaisesRegex(smoke.SmokeFailure, "independent"):
            self.execute(api, writes=True)
        self.assertEqual(api.attempt, 1)

    def test_account_removed_before_narrowing_grant(self):
        api = FakeApi()
        check = self.execute(api)
        check.cleanup()
        deletions = [c[1] for c in api.calls if c[0] == "DELETE"]
        self.assertEqual(deletions, ["/api/auth/users/71", "/api/auth/rbac/grants/81"])

    def test_failed_user_cleanup_keeps_grant_and_fails_acceptance(self):
        api = FakeApi()
        api.fail_user_cleanup = True
        check = self.execute(api)
        check.cleanup()
        self.assertEqual(check.report["status"], "failed")
        self.assertTrue(check.report["cleanupRequired"])
        self.assertNotIn(("DELETE", "/api/auth/rbac/grants/81"), [(c[0], c[1]) for c in api.calls])


if __name__ == "__main__":
    unittest.main()
