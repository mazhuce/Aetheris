#!/usr/bin/env python3
"""M0 Spark SQL acceptance through the public gateway (Python standard library).

Generate an isolated fixture with --fixture-sql first; executing that SQL is an
explicit operator step. Normal execution never issues DDL or changes business
tables. --write enables two manual workflow runs against one owned partition.
"""

import argparse
from collections import defaultdict
from datetime import date, datetime, timezone
from decimal import Decimal, InvalidOperation
import json
import os
from pathlib import Path
import re
import secrets
import sys
from urllib.error import HTTPError, URLError
from urllib.parse import urlencode, urlsplit
from urllib.request import HTTPRedirectHandler, ProxyHandler, Request, build_opener


OWNER_PROPERTY = "aetheris.smoke.owner"
SOURCE_ROWS = [[1, "A", 10], [2, "A", 20], [3, "B", 7]]


class SmokeFailure(Exception):
    """Only locally constructed, credential-free messages belong here."""


def require(condition, message):
    if not condition:
        raise SmokeFailure(message)


def fixture_name(marker):
    require(isinstance(marker, str) and re.fullmatch(r"[0-9a-f]{16}", marker),
            "fixture must be exactly 16 lowercase hexadecimal characters")
    return "aetheris_smoke_" + marker


def fixture_sql(marker):
    database = fixture_name(marker)
    return f"""-- Dedicated M0 fixture; owner={marker}. Run with beeline --force=false.
-- Existing database is an error: do not add IF NOT EXISTS or reuse business data.
CREATE DATABASE {database} COMMENT 'Aetheris M0 isolated smoke fixture {marker}';
CREATE TABLE {database}.source_orders (id INT, category STRING, amount INT)
STORED AS PARQUET TBLPROPERTIES ('{OWNER_PROPERTY}'='{marker}');
INSERT INTO {database}.source_orders VALUES (1, 'A', 10), (2, 'A', 20), (3, 'B', 7);
CREATE TABLE {database}.target_totals (category STRING, order_count BIGINT, total_amount BIGINT)
PARTITIONED BY (run_id STRING) STORED AS PARQUET
TBLPROPERTIES ('{OWNER_PROPERTY}'='{marker}');
"""


def write_sql(marker):
    database = fixture_name(marker)
    # A static partition is intentional: rerunning never appends or overwrites
    # another run's partition. All identifiers are fixed or generated above.
    return (f"INSERT OVERWRITE TABLE {database}.target_totals "
            f"PARTITION (run_id='{marker}') "
            f"SELECT category, COUNT(*), SUM(amount) FROM {database}.source_orders "
            "GROUP BY category")


def target_sql(marker):
    database = fixture_name(marker)
    return (f"SELECT category, order_count, total_amount FROM {database}.target_totals "
            f"WHERE run_id='{marker}' ORDER BY category")


def query_rows(result, expected_columns):
    require(isinstance(result, dict), "query response is not an object")
    columns, rows = result.get("columns"), result.get("rows")
    require(isinstance(columns, list) and isinstance(rows, list), "query response lacks rows/columns")
    # Hive JDBC may qualify labels with a table name.
    labels = [str(column).rsplit(".", 1)[-1].lower() for column in columns]
    require(labels == expected_columns, "query returned unexpected column labels")
    require(result.get("rowCount") == len(rows), "query rowCount differs from returned rows")
    require(not result.get("truncated", False), "query result was truncated")
    require(all(isinstance(row, list) and len(row) == len(columns) for row in rows),
            "query returned malformed rows")
    return rows


def integer(value):
    require(not isinstance(value, bool) and value is not None, "expected an integer cell")
    try:
        numeric = Decimal(str(value))
        require(numeric.is_finite() and numeric == numeric.to_integral_value(),
                "expected a finite integer cell")
        return int(numeric)
    except (InvalidOperation, ValueError, OverflowError):
        raise SmokeFailure("expected an integer cell") from None


def assert_select_one(result):
    rows = query_rows(result, ["smoke_one"])
    require(len(rows) == 1 and integer(rows[0][0]) == 1, "SELECT 1 returned incorrect data")


def assert_source(result):
    rows = query_rows(result, ["id", "category", "amount"])
    normalized = [[integer(row[0]), row[1], integer(row[2])] for row in rows]
    require(normalized == SOURCE_ROWS, "source fixture differs from the three known rows")
    return normalized


def independent_totals(rows):
    totals = defaultdict(lambda: [0, 0])
    for _, category, amount in rows:
        totals[category][0] += 1
        totals[category][1] += integer(amount)
    return [[category, count, amount] for category, (count, amount) in sorted(totals.items())]


def assert_target(result, expected):
    rows = query_rows(result, ["category", "order_count", "total_amount"])
    normalized = [[row[0], integer(row[1]), integer(row[2])] for row in rows]
    require(normalized == expected, "workflow output differs from independent Python aggregation")
    return normalized


def assert_owner(result, marker):
    fixture_name(marker)
    rows = query_rows(result, ["key", "value"])
    require(rows == [[OWNER_PROPERTY, marker]], "fixture ownership property is absent or mismatched")


def gateway_url(value):
    parsed = urlsplit(value)
    require(parsed.scheme in ("http", "https") and parsed.hostname,
            "gateway URL must use HTTP or HTTPS")
    require(not parsed.username and not parsed.password and not parsed.query and not parsed.fragment,
            "gateway URL must not include credentials, query or fragment")
    require(parsed.path in ("", "/"), "gateway URL must point at the gateway root")
    require(parsed.scheme == "https" or parsed.hostname in ("localhost", "127.0.0.1", "::1"),
            "non-loopback gateway connections require HTTPS")
    return value.rstrip("/")


class NoRedirect(HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


class Api:
    def __init__(self, base_url, timeout=90):
        self.base_url = gateway_url(base_url)
        self.timeout = timeout
        # Keep loopback credentials away from configured corporate HTTP proxies.
        handlers = [NoRedirect()]
        if urlsplit(self.base_url).hostname in ("localhost", "127.0.0.1", "::1"):
            handlers.append(ProxyHandler({}))
        self.opener = build_opener(*handlers)
        self.token = None

    def request(self, method, path, payload=None, token=None, expected=(200,)):
        headers = {"Accept": "application/json"}
        token = self.token if token is None else token
        if token:
            headers["Authorization"] = "Bearer " + token
        data = None
        if payload is not None:
            data = json.dumps(payload).encode("utf-8")
            headers["Content-Type"] = "application/json"
        request = Request(self.base_url + path, data=data, method=method, headers=headers)
        try:
            with self.opener.open(request, timeout=self.timeout) as response:
                status = response.status
                raw = response.read(1024 * 1024 + 1)
        except HTTPError as error:
            status = error.code
            # Never print server error bodies: driver/proxy errors can contain secrets.
            error.close()
            raw = b""
        except (URLError, TimeoutError, OSError):
            raise SmokeFailure(f"{method} {path.split('?')[0]} connection failed or timed out; not retried") from None
        require(status in expected, f"{method} {path.split('?')[0]} returned HTTP {status}")
        require(len(raw) <= 1024 * 1024, "HTTP response exceeded one MiB")
        if not raw:
            return None
        try:
            return json.loads(raw)
        except (UnicodeDecodeError, ValueError):
            raise SmokeFailure("HTTP response is not valid JSON") from None

    def login(self, username, password):
        result = self.request("POST", "/api/auth/login", {"username": username, "password": password}, token="")
        require(isinstance(result, dict) and isinstance(result.get("token"), str) and result["token"],
                "login response has no bearer token")
        return result


class SparkSmoke:
    def __init__(self, api, datasource_id, marker, writes=False, keep_task=False):
        self.api = api
        self.datasource_id = datasource_id
        self.marker = marker
        self.database = fixture_name(marker)
        self.base = f"/api/metadata/datasources/{datasource_id}"
        self.writes = writes
        self.keep_task = keep_task
        self.user_id = self.grant_id = self.task_id = None
        self.run_unconfirmed = False
        self.report = {"startedAt": datetime.now(timezone.utc).isoformat(),
                       "datasourceId": datasource_id, "fixture": marker,
                       "database": self.database, "mode": "write" if writes else "read",
                       "status": "running", "writeAcceptancePassed": False, "checks": [], "cleanup": []}

    def passed(self, name, **details):
        self.report["checks"].append({"name": name, "status": "passed", **details})
        print("PASS " + name, flush=True)

    def query(self, sql, token=None):
        return self.api.request("POST", self.base + "/query", {"sql": sql}, token=token)

    def verify_owner(self):
        for table in ("source_orders", "target_totals"):
            result = self.query(f"SHOW TBLPROPERTIES {self.database}.{table} ('{OWNER_PROPERTY}')")
            assert_owner(result, self.marker)

    def run(self, username, password):
        login = self.api.login(username, password)
        require(login.get("role") == "admin", "smoke needs an administrator to create isolated test identities/tasks")
        self.api.token = login["token"]
        self.passed("gateway_admin_login")
        source = self.api.request("GET", self.base)
        require(isinstance(source, dict) and source.get("type") == "spark", "configured datasource is not Spark")
        self.passed("configured_spark_datasource")
        probe = self.api.request("POST", self.base + "/test")
        require(isinstance(probe, dict) and probe.get("success") is True,
                "metadata-to-Spark connection probe failed; inspect dependency health")
        self.passed("metadata_container_connection", latencyMs=probe.get("latencyMs"))
        assert_select_one(self.query("SELECT 1 AS smoke_one"))
        self.passed("gateway_metadata_relay_spark_select_one")
        schemas = self.api.request("GET", self.base + "/schemas")
        require(isinstance(schemas, list) and any(isinstance(s, dict) and s.get("name") == self.database for s in schemas),
                "dedicated fixture database is absent from schema discovery")
        # Scan exactly the tiny fixture database, never all business tables.
        tables = self.api.request("GET", self.base + "/tables?" + urlencode({"schema": self.database}))
        require(isinstance(tables, list) and {t.get("name") for t in tables if isinstance(t, dict)}
                == {"source_orders", "target_totals"}, "fixture database contains unexpected tables")
        columns = self.api.request("GET", self.base + "/columns?" + urlencode({"schema": self.database, "table": "source_orders"}))
        require(isinstance(columns, list) and [c.get("name") for c in columns] == ["id", "category", "amount"],
                "fixture metadata columns do not match")
        self.passed("fixture_schema_table_column_discovery")
        self.verify_owner()
        source_sql = f"SELECT id, category, amount FROM {self.database}.source_orders ORDER BY id"
        rows = assert_source(self.query(source_sql))
        expected = independent_totals(rows)
        self.passed("known_small_table", sql=source_sql, rows=rows)
        self.authorization()
        if self.writes:
            self.workflow(expected)
        else:
            self.report["checks"].append({"name": "workflow_overwrite_and_rerun", "status": "skipped",
                                           "reason": "requires --write"})
        self.report["status"] = "passed"
        self.report["writeAcceptancePassed"] = self.writes

    def authorization(self):
        suffix = secrets.token_hex(8)
        viewer_name, viewer_password = "spark-smoke-" + suffix, secrets.token_urlsafe(24)
        # Only returned IDs are eligible for cleanup; never reuse a preexisting account.
        user = self.api.request("POST", "/api/auth/users", {
            "username": viewer_name, "password": viewer_password, "nickname": "Spark M0 smoke",
            "role": "viewer", "status": "active"}, expected=(201,))
        self.user_id = valid_id(user.get("id"))
        self.report["temporaryUserId"] = self.user_id
        grant = self.api.request("POST", "/api/auth/rbac/grants", {
            "granteeType": "user", "granteeId": self.user_id, "resourceType": "datasource",
            "resourceId": self.datasource_id, "actions": ["view", "query"], "enabled": True}, expected=(201,))
        self.grant_id = valid_id(grant.get("id"))
        self.report["temporaryGrantId"] = self.grant_id
        viewer_token = self.api.login(viewer_name, viewer_password)["token"]
        assert_select_one(self.query("SELECT 1 AS smoke_one", token=viewer_token))
        assert_select_one(self.api.request("POST", "/api/metadata/query",
                         {"datasourceId": self.datasource_id, "sql": "SELECT 1 AS smoke_one"}, token=viewer_token))
        self.passed("explicitly_authorized_viewer_query")
        # Keeping a scoped view grant narrows the legacy viewer full-read fallback.
        # Deleting the grant here would restore that fallback and is not a deny test.
        self.api.request("PUT", f"/api/auth/rbac/grants/{self.grant_id}",
                         {"actions": ["view"], "enabled": True})
        self.api.request("POST", self.base + "/query", {"sql": "SELECT 1 AS smoke_one"},
                         token=viewer_token, expected=(403,))
        self.api.request("POST", "/api/metadata/query", {"datasourceId": self.datasource_id, "sql": "SELECT 1 AS smoke_one"},
                         token=viewer_token, expected=(403,))
        self.passed("revoked_query_permission_denied_on_both_public_routes", httpStatus=403)
        self.api.request("PUT", f"/api/auth/rbac/grants/{self.grant_id}",
                         {"actions": ["view", "query"], "enabled": True})
        assert_select_one(self.query("SELECT 1 AS smoke_one", token=viewer_token))
        self.passed("query_permission_restoration_same_token")

    def workflow(self, expected):
        self.verify_owner()
        sql = write_sql(self.marker)
        task = self.api.request("POST", "/api/workflow/tasks", {
            "name": "spark-smoke-" + self.marker + "-" + secrets.token_hex(4),
            "datasourceId": self.datasource_id, "taskSql": sql,
            "targetTable": self.database + ".target_totals", "timeoutSeconds": 60,
            "maxRetries": 0, "status": "enabled", "cronExpression": None,
            "description": "M0 isolated fixture " + self.marker})
        self.task_id = valid_id(task.get("task", {}).get("id"))
        self.report["workflowTaskId"] = self.task_id
        self.report["workflowSql"] = sql
        self.report["independentExpectedRows"] = expected
        biz_date = date.today().isoformat()
        self.report["bizDate"] = biz_date
        previous_attempt = 0
        previous_instance_id = None
        for number in (1, 2):
            self.verify_owner()
            # A transport failure leaves submission outcome unknown. Never blindly
            # repeat or delete the task while an in-flight write might remain.
            self.run_unconfirmed = True
            instance = self.api.request("POST", f"/api/workflow/tasks/{self.task_id}/run", {"bizDate": biz_date})
            require(isinstance(instance, dict), "workflow run response is not an object")
            status = instance.get("status")
            if status in ("success", "failed", "skipped", "cancelled"):
                self.run_unconfirmed = False
            require(status == "success", "workflow did not return success; inspect retained instance/logs")
            instance_id = valid_id(instance.get("id"))
            attempt = integer(instance.get("attempt"))
            require(attempt > previous_attempt, "workflow rerun did not advance its attempt counter")
            require(previous_instance_id is None or previous_instance_id == instance_id,
                    "current M0 contract expects the same logical instance for one business date")
            previous_attempt, previous_instance_id = attempt, instance_id
            actual = assert_target(self.query(target_sql(self.marker)), expected)
            self.passed(f"workflow_run_{number}_independent_result", instanceId=instance_id,
                        attempt=attempt, rows=actual, validationSql=target_sql(self.marker))
        self.passed("same_partition_rerun_without_duplicates")

    def cleanup(self):
        pending = []
        if self.task_id:
            if self.keep_task or self.run_unconfirmed or self.report["status"] != "passed":
                self.report["cleanup"].append({"resource": "workflowTask", "id": self.task_id,
                                               "status": "retained", "submissionUnconfirmed": self.run_unconfirmed})
            else:
                pending.append(("workflowTask", self.task_id, f"/api/workflow/tasks/{self.task_id}"))
        # Remove the account first so revoking its narrowing grant cannot restore
        # a still-active viewer's default read access, even briefly.
        user_removed = self.user_id is None
        if self.user_id:
            try:
                self.api.request("DELETE", f"/api/auth/users/{self.user_id}", expected=(204, 404))
                self.report["cleanup"].append({"resource": "user", "id": self.user_id, "status": "removed"})
                user_removed = True
            except SmokeFailure:
                self.report["cleanup"].append({"resource": "user", "id": self.user_id, "status": "failed"})
        if self.grant_id and user_removed:
            pending.append(("grant", self.grant_id, f"/api/auth/rbac/grants/{self.grant_id}"))
        elif self.grant_id:
            self.report["cleanup"].append({"resource": "grant", "id": self.grant_id, "status": "retained"})
        for kind, identifier, path in pending:
            try:
                self.api.request("DELETE", path, expected=(204, 404))
                self.report["cleanup"].append({"resource": kind, "id": identifier, "status": "removed"})
            except SmokeFailure:
                self.report["cleanup"].append({"resource": kind, "id": identifier, "status": "failed"})
        if any(item["status"] == "failed" for item in self.report["cleanup"]):
            self.report["status"] = "failed"
            self.report["writeAcceptancePassed"] = False
            self.report["cleanupRequired"] = True
        self.report["finishedAt"] = datetime.now(timezone.utc).isoformat()


def valid_id(value):
    require(isinstance(value, int) and not isinstance(value, bool) and value > 0,
            "API response has no positive resource ID")
    return value


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--fixture", help="16 hex owner marker from generated fixture SQL")
    parser.add_argument("--fixture-sql", type=Path, help="generate SQL into a new file and exit; does not execute it")
    parser.add_argument("--write", action="store_true", help="run a manual overwrite workflow twice on the owned fixture")
    parser.add_argument("--keep-task", action="store_true", help="preserve successful manual task and logs for inspection")
    parser.add_argument("--report", type=Path, help="new file for a credential-free JSON acceptance record")
    args = parser.parse_args(argv)
    try:
        if args.fixture_sql:
            require(not args.write and not args.keep_task and not args.report,
                    "--fixture-sql cannot be combined with execution/report flags")
            marker = args.fixture or secrets.token_hex(8)
            sql = fixture_sql(marker)
            with args.fixture_sql.open("x", encoding="utf-8", newline="\n") as output:
                output.write(sql)
            print(f"Fixture SQL generated (not executed). Use --fixture {marker}")
            return 0
        require(args.fixture is not None, "--fixture is required; prepare SQL using --fixture-sql first")
        fixture_name(args.fixture)
        require(not args.keep_task or args.write, "--keep-task requires --write")
        if args.report:
            require(not args.report.exists() and args.report.parent.is_dir(), "report path must be a new file in an existing directory")
        datasource_raw = os.environ.get("AETHERIS_SPARK_DATASOURCE_ID", "")
        require(re.fullmatch(r"[1-9][0-9]*", datasource_raw), "AETHERIS_SPARK_DATASOURCE_ID must be a positive integer")
        password = os.environ.get("INITIAL_ADMIN_PASSWORD", "")
        require(password, "INITIAL_ADMIN_PASSWORD is required")
        username = os.environ.get("INITIAL_ADMIN_USERNAME", "admin")
        smoke = SparkSmoke(Api(os.environ.get("AETHERIS_BASE_URL", "http://localhost:8080")),
                           int(datasource_raw), args.fixture, args.write, args.keep_task)
        try:
            smoke.run(username, password)
        except SmokeFailure as error:
            smoke.report["status"] = "failed"
            smoke.report["error"] = str(error)
        except (KeyboardInterrupt, Exception):
            # Suppress unexpected exception values/tracebacks, which can contain
            # response bodies, passwords or tokens in third-party error messages.
            smoke.report["status"] = "failed"
            smoke.report["error"] = "interrupted or unexpected response; no execution automatically retried"
        finally:
            smoke.cleanup()
        if args.report:
            with args.report.open("x", encoding="utf-8", newline="\n") as output:
                json.dump(smoke.report, output, ensure_ascii=False, indent=2)
                output.write("\n")
        if smoke.report["status"] != "passed":
            print("FAIL " + smoke.report.get("error", "temporary resource cleanup failed"), file=sys.stderr)
            print("Inspect report cleanup entries and retained workflow task before retrying.", file=sys.stderr)
            return 1
        print("Spark SQL M0 query/auth/workflow checks passed." if args.write
              else "Spark SQL query/auth checks passed; workflow writing was skipped (M0 incomplete).")
        return 0
    except (SmokeFailure, OSError) as error:
        print("FAIL " + (str(error) if isinstance(error, SmokeFailure) else "cannot create output file"), file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(main())
