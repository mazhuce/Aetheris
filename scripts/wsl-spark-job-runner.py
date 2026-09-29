#!/usr/bin/env python3
"""Small authenticated runner for approved local Spark 3.5.6 applications.

This is a development backend. It accepts PySpark files or Spark JARs staged from a repository
or the control plane, verifies their SHA-256 digest, builds spark-submit as an
argv list, strips the control-plane environment and persists enough state to
reconcile after restart.
"""

from __future__ import annotations

import argparse
import base64
import hashlib
import hmac
import json
import os
import re
import signal
import subprocess
import threading
import time
import uuid
from datetime import datetime, timezone
from http import HTTPStatus
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.parse import parse_qs, unquote, urlparse


RUNNER_VERSION = "1"
ACTIVE = {"queued", "submitting", "running", "cancel_requested"}
TERMINAL = {"succeeded", "failed", "cancelled", "timed_out", "unknown"}
ARTIFACT_KEY = re.compile(r"^[a-z0-9][a-z0-9_-]{0,63}\.(?:py|jar)$")
PYSPARK_KEY = re.compile(r"^[a-z0-9][a-z0-9_-]{0,63}\.py$")
JAR_KEY = re.compile(r"^[a-z0-9][a-z0-9_-]{0,63}\.jar$")
MAX_ARTIFACT_BYTES = 64 * 1024 * 1024
REQUEST_KEY = re.compile(r"^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$")
DATE = re.compile(r"^\d{4}-\d{2}-\d{2}$")
MAINTENANCE_OPERATIONS = {
    "expire_snapshots", "rewrite_data_files", "remove_orphan_files",
    "storage_estimate", "time_travel", "rollback",
}
MAINTENANCE_STATEMENT_BYTES = 16 * 1024
SCHEMA_CHANGE_OPERATIONS = {"add_column", "widen_type", "rename_column", "drop_column", "comment"}
SCHEMA_CHANGE_STATEMENT_BYTES = 16 * 1024
SCHEMA_IDENTIFIER = r"`[A-Za-z_][A-Za-z0-9_]{0,127}`"
SCHEMA_TABLE = rf"(?:{SCHEMA_IDENTIFIER}\.){{1,7}}{SCHEMA_IDENTIFIER}"
SCHEMA_TYPE = r"(?:BOOLEAN|TINYINT|SMALLINT|INT|INTEGER|BIGINT|FLOAT|DOUBLE|STRING|DATE|TIMESTAMP|BINARY|UUID|DECIMAL\s*\(\s*\d{1,2}\s*,\s*\d{1,2}\s*\))"
REDACTIONS = [
    re.compile(r"(?i)(password|secret|token|access[_-]?key)(\s*[=:]\s*)[^\s,;]+"),
    re.compile(r"(?i)(authorization\s*:\s*bearer\s+)[^\s]+"),
]


def spark_catalog_arguments() -> list[str]:
    """Build the fixed Spark catalog contract from runner-owned environment.

    The default remains the historic local Hive catalog.  REST mode is used by
    the R1/R5 acceptance profile and deliberately reads object-store
    credentials from the child environment instead of putting them in the
    spark-submit argv (which is visible to process listings).
    """
    mode = os.environ.get("AETHERIS_RUNNER_SPARK_CATALOG_MODE", "hive").strip().lower()
    if mode == "hive":
        return [
            "--conf", "spark.sql.catalogImplementation=hive",
            "--conf", "spark.sql.catalog.hive_prod=org.apache.iceberg.spark.SparkCatalog",
            "--conf", "spark.sql.catalog.hive_prod.type=hive",
            "--conf", "spark.sql.catalog.hive_prod.uri=thrift://127.0.0.1:9083",
            "--conf", "spark.sql.catalog.hive_prod.warehouse=hdfs://localhost:9000/user/hive/warehouse",
            "--conf", "spark.sql.defaultCatalog=hive_prod",
        ]
    if mode != "rest":
        raise RunnerError(HTTPStatus.INTERNAL_SERVER_ERROR, "unsupported runner catalog mode")
    name = os.environ.get("AETHERIS_RUNNER_CATALOG_NAME", "iceberg").strip()
    uri = os.environ.get("AETHERIS_RUNNER_CATALOG_URI", "").strip()
    warehouse = os.environ.get("AETHERIS_RUNNER_CATALOG_WAREHOUSE", "").strip()
    endpoint = os.environ.get("AETHERIS_RUNNER_S3_ENDPOINT", "").strip()
    region = os.environ.get("AETHERIS_RUNNER_S3_REGION", "us-east-1").strip()
    if not re.fullmatch(r"[A-Za-z][A-Za-z0-9_]{0,63}", name):
        raise RunnerError(HTTPStatus.INTERNAL_SERVER_ERROR, "runner catalog name is invalid")
    if not uri or not warehouse or not endpoint or not region:
        raise RunnerError(HTTPStatus.INTERNAL_SERVER_ERROR, "runner REST catalog is incompletely configured")
    prefix = f"spark.sql.catalog.{name}"
    return [
        "--conf", "spark.sql.catalogImplementation=in-memory",
        "--conf", f"{prefix}=org.apache.iceberg.spark.SparkCatalog",
        "--conf", f"{prefix}.catalog-impl=org.apache.iceberg.rest.RESTCatalog",
        "--conf", f"{prefix}.uri={uri}",
        "--conf", f"{prefix}.warehouse={warehouse}",
        "--conf", f"{prefix}.io-impl=org.apache.iceberg.aws.s3.S3FileIO",
        "--conf", f"{prefix}.s3.endpoint={endpoint}",
        "--conf", f"{prefix}.s3.path-style-access=true",
        "--conf", f"{prefix}.s3.region={region}",
        "--conf", "spark.hadoop.fs.s3.impl=org.apache.hadoop.fs.s3a.S3AFileSystem",
        "--conf", "spark.hadoop.fs.s3a.impl=org.apache.hadoop.fs.s3a.S3AFileSystem",
        "--conf", f"spark.hadoop.fs.s3a.endpoint={endpoint}",
        "--conf", "spark.hadoop.fs.s3a.path.style.access=true",
        "--conf", "spark.hadoop.fs.s3a.connection.ssl.enabled=false",
        "--conf", "spark.hadoop.fs.s3a.aws.credentials.provider=com.amazonaws.auth.EnvironmentVariableCredentialsProvider",
        "--conf", "spark.sql.extensions=org.apache.iceberg.spark.extensions.IcebergSparkSessionExtensions",
        "--conf", f"spark.sql.defaultCatalog={name}",
    ]


def utc_now() -> str:
    return datetime.now(timezone.utc).isoformat()


def atomic_json(path: Path, payload: dict[str, object]) -> None:
    temporary = path.with_suffix(path.suffix + ".tmp")
    temporary.write_text(json.dumps(payload, ensure_ascii=False, sort_keys=True), encoding="utf-8")
    os.chmod(temporary, 0o600)
    temporary.replace(path)


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def redact(line: str) -> str:
    value = line
    for pattern in REDACTIONS:
        value = pattern.sub(lambda match: match.group(1) + match.group(2) + "[REDACTED]"
                            if match.lastindex == 2 else match.group(1) + "[REDACTED]", value)
    return value


class RunnerError(Exception):
    def __init__(self, status: int, message: str):
        super().__init__(message)
        self.status = status


class JobRunner:
    def __init__(self, args: argparse.Namespace):
        self.artifact_root = Path(args.artifact_root).resolve(strict=True)
        self.state_root = Path(args.state_root).resolve()
        self.state_root.mkdir(parents=True, exist_ok=True)
        os.chmod(self.state_root, 0o700)
        self.spark_submit = Path(args.spark_submit).resolve(strict=True)
        self.max_concurrency = args.max_concurrency
        self.max_log_bytes = args.max_log_bytes
        self.default_timeout = args.default_timeout
        self.token = Path(args.token_file).read_text(encoding="utf-8").strip()
        if len(self.token.encode("utf-8")) < 32:
            raise SystemExit("runner token must contain at least 32 UTF-8 bytes")
        self.lock = threading.RLock()
        self.processes: dict[str, subprocess.Popen[bytes]] = {}
        self._recover()

    def authenticate(self, supplied: str | None) -> bool:
        return supplied is not None and hmac.compare_digest(self.token, supplied)

    def _job_dir(self, job_id: str) -> Path:
        if not re.fullmatch(r"[0-9a-f]{32}", job_id):
            raise RunnerError(HTTPStatus.BAD_REQUEST, "invalid job id")
        return self.state_root / job_id

    def _state_path(self, job_id: str) -> Path:
        return self._job_dir(job_id) / "state.json"

    def _load(self, job_id: str) -> dict[str, object]:
        path = self._state_path(job_id)
        if not path.is_file():
            raise RunnerError(HTTPStatus.NOT_FOUND, "job not found")
        try:
            return json.loads(path.read_text(encoding="utf-8"))
        except (OSError, json.JSONDecodeError) as error:
            raise RunnerError(HTTPStatus.INTERNAL_SERVER_ERROR, "job state is unreadable") from error

    def _save(self, state: dict[str, object]) -> None:
        atomic_json(self._state_path(str(state["id"])), state)

    def _public(self, state: dict[str, object]) -> dict[str, object]:
        allowed = (
            "id", "requestKey", "status", "artifactKey", "artifactType", "artifactDigest",
            "applicationId", "submittedAt", "startedAt", "heartbeatAt",
            "finishedAt", "timeoutSeconds", "affectedRows", "errorCode",
            "errorMessage", "result", "cancellationConfirmedAt",
        )
        return {key: state.get(key) for key in allowed if state.get(key) is not None}

    def artifact(self, key: str) -> dict[str, object]:
        if not ARTIFACT_KEY.fullmatch(key):
            raise RunnerError(HTTPStatus.BAD_REQUEST, "invalid artifact key")
        path = (self.artifact_root / key).resolve(strict=False)
        if path.parent != self.artifact_root or not path.is_file():
            raise RunnerError(HTTPStatus.NOT_FOUND, "artifact not found")
        if not (PYSPARK_KEY.fullmatch(key) or JAR_KEY.fullmatch(key)):
            raise RunnerError(HTTPStatus.BAD_REQUEST, "only approved PySpark or Spark JAR artifacts are supported")
        if path.stat().st_size > MAX_ARTIFACT_BYTES:
            raise RunnerError(HTTPStatus.BAD_REQUEST, "artifact exceeds the 64MB runner limit")
        return {"key": key, "sha256": sha256(path), "size": path.stat().st_size,
                "artifactType": "spark-jar" if JAR_KEY.fullmatch(key) else "pyspark"}

    def upload_artifact(self, key: str, body: dict[str, object]) -> dict[str, object]:
        if not ARTIFACT_KEY.fullmatch(key):
            raise RunnerError(HTTPStatus.BAD_REQUEST, "only lowercase .py or .jar artifacts are supported")
        encoded = body.get("contentBase64")
        expected = str(body.get("sha256", "")).lower()
        if not isinstance(encoded, str) or len(encoded) > 90 * 1024 * 1024:
            raise RunnerError(HTTPStatus.BAD_REQUEST, "invalid artifact content")
        try:
            content = base64.b64decode(encoded, validate=True)
        except (ValueError, TypeError):
            raise RunnerError(HTTPStatus.BAD_REQUEST, "invalid artifact encoding")
        if not content or len(content) > MAX_ARTIFACT_BYTES:
            raise RunnerError(HTTPStatus.BAD_REQUEST, "artifact size must be between 1B and 64MB")
        digest = hashlib.sha256(content).hexdigest()
        if not hmac.compare_digest(digest, expected):
            raise RunnerError(HTTPStatus.CONFLICT, "artifact digest does not match content")
        path = (self.artifact_root / key).resolve(strict=False)
        if path.parent != self.artifact_root:
            raise RunnerError(HTTPStatus.BAD_REQUEST, "invalid artifact path")
        if path.exists() and sha256(path) != digest:
            raise RunnerError(HTTPStatus.CONFLICT, "artifact key already contains another file")
        if not path.exists():
            temporary = path.with_suffix(path.suffix + ".upload")
            temporary.write_bytes(content)
            os.chmod(temporary, 0o640)
            temporary.replace(path)
        return {"key": key, "sha256": digest, "size": len(content)}

    def submit(self, body: dict[str, object]) -> dict[str, object]:
        request_key = str(body.get("requestKey", ""))
        artifact_key = str(body.get("artifactKey", ""))
        artifact_digest = str(body.get("artifactDigest", "")).lower()
        parameters = body.get("parameters")
        resources = body.get("resources")
        if not REQUEST_KEY.fullmatch(request_key):
            raise RunnerError(HTTPStatus.BAD_REQUEST, "invalid request key")
        if not isinstance(parameters, dict) or not {"bizDate", "attemptId"}.issubset(parameters):
            raise RunnerError(HTTPStatus.BAD_REQUEST, "managed job parameters are required")
        if set(parameters) - {"bizDate", "attemptId", "maintenance", "schemaChange"}:
            raise RunnerError(HTTPStatus.BAD_REQUEST, "unsupported parameters")
        biz_date = str(parameters.get("bizDate", ""))
        attempt_id = str(parameters.get("attemptId", ""))
        if not DATE.fullmatch(biz_date) or not attempt_id.isdecimal():
            raise RunnerError(HTTPStatus.BAD_REQUEST, "invalid managed job parameters")
        maintenance = parameters.get("maintenance")
        maintenance_operation = None
        maintenance_statement = None
        schema_change_operation = None
        schema_change_statement = None
        if maintenance is not None:
            if not isinstance(maintenance, dict):
                raise RunnerError(HTTPStatus.BAD_REQUEST, "invalid maintenance parameters")
            maintenance_operation = str(maintenance.get("operation", ""))
            maintenance_statement = str(maintenance.get("statement", ""))
            if maintenance_operation not in MAINTENANCE_OPERATIONS:
                raise RunnerError(HTTPStatus.BAD_REQUEST, "unsupported maintenance operation")
            if (not maintenance_statement or len(maintenance_statement.encode("utf-8")) > MAINTENANCE_STATEMENT_BYTES
                    or "\x00" in maintenance_statement or ";" in maintenance_statement
                    or "--" in maintenance_statement or "/*" in maintenance_statement
                    or "*/" in maintenance_statement):
                raise RunnerError(HTTPStatus.BAD_REQUEST, "invalid maintenance statement")
            if maintenance_operation in {"storage_estimate", "time_travel"}:
                if not maintenance_statement.lstrip().upper().startswith("SELECT "):
                    raise RunnerError(HTTPStatus.BAD_REQUEST, "read-only maintenance must use SELECT")
            elif not maintenance_statement.lstrip().upper().startswith("CALL "):
                raise RunnerError(HTTPStatus.BAD_REQUEST, "mutating maintenance must use CALL")
            else:
                procedure = {
                    "expire_snapshots": "EXPIRE_SNAPSHOTS",
                    "rewrite_data_files": "REWRITE_DATA_FILES",
                    "remove_orphan_files": "REMOVE_ORPHAN_FILES",
                    "rollback": "ROLLBACK_TO_SNAPSHOT",
                }[maintenance_operation]
                if not re.fullmatch(
                        r"CALL\s+[A-Za-z0-9_:.\-]+\.SYSTEM\." + procedure + r"\s*\(.*",
                        maintenance_statement, re.IGNORECASE | re.DOTALL):
                    raise RunnerError(HTTPStatus.BAD_REQUEST, "maintenance procedure does not match operation")
        schema_change = parameters.get("schemaChange")
        if schema_change is not None:
            if maintenance is not None or not isinstance(schema_change, dict):
                raise RunnerError(HTTPStatus.BAD_REQUEST, "invalid schema change parameters")
            schema_change_operation = str(schema_change.get("operation", ""))
            schema_change_statement = str(schema_change.get("statement", ""))
            if schema_change_operation not in SCHEMA_CHANGE_OPERATIONS:
                raise RunnerError(HTTPStatus.BAD_REQUEST, "unsupported schema change operation")
            if (not schema_change_statement
                    or len(schema_change_statement.encode("utf-8")) > SCHEMA_CHANGE_STATEMENT_BYTES
                    or "\x00" in schema_change_statement
                    or ";" in schema_change_statement
                    or "--" in schema_change_statement
                    or "/*" in schema_change_statement
                    or "*/" in schema_change_statement):
                raise RunnerError(HTTPStatus.BAD_REQUEST, "invalid schema change statement")
            identifier = SCHEMA_IDENTIFIER
            table = SCHEMA_TABLE
            patterns = {
                "add_column": rf"ALTER\s+TABLE\s+{table}\s+ADD\s+COLUMN\s+{identifier}\s+{SCHEMA_TYPE}",
                "widen_type": rf"ALTER\s+TABLE\s+{table}\s+ALTER\s+COLUMN\s+{identifier}\s+TYPE\s+{SCHEMA_TYPE}",
                "rename_column": rf"ALTER\s+TABLE\s+{table}\s+RENAME\s+COLUMN\s+{identifier}\s+TO\s+{identifier}",
                "drop_column": rf"ALTER\s+TABLE\s+{table}\s+DROP\s+COLUMN\s+{identifier}",
                "comment": rf"ALTER\s+TABLE\s+{table}\s+ALTER\s+COLUMN\s+{identifier}\s+COMMENT\s+'(?:''|[^'\r\n]){{1,512}}'",
            }
            if not re.fullmatch(patterns[schema_change_operation], schema_change_statement,
                               re.IGNORECASE | re.DOTALL):
                raise RunnerError(HTTPStatus.BAD_REQUEST, "schema change operation does not match statement")
        if not isinstance(resources, dict):
            raise RunnerError(HTTPStatus.BAD_REQUEST, "resources are required")
        cores = int(resources.get("cores", 1))
        memory_mb = int(resources.get("driverMemoryMb", 768))
        timeout_seconds = int(resources.get("timeoutSeconds", self.default_timeout))
        if cores not in (1, 2) or not 512 <= memory_mb <= 2048 or not 30 <= timeout_seconds <= 86400:
            raise RunnerError(HTTPStatus.BAD_REQUEST, "resources exceed the local runner policy")
        artifact = self.artifact(artifact_key)
        artifact_type = str(body.get("artifactType") or
                            ("spark-jar" if JAR_KEY.fullmatch(artifact_key) else "pyspark"))
        if artifact_type not in {"pyspark", "spark-jar"} or artifact["artifactType"] != artifact_type:
            raise RunnerError(HTTPStatus.BAD_REQUEST, "artifact type does not match artifact key")
        main_class = str(body.get("mainClass", ""))
        if artifact_type == "spark-jar" and not re.fullmatch(r"[A-Za-z_$][A-Za-z0-9_$.]{0,255}", main_class):
            raise RunnerError(HTTPStatus.BAD_REQUEST, "Spark JAR requires a valid mainClass")
        if not re.fullmatch(r"[0-9a-f]{64}", artifact_digest) or artifact["sha256"] != artifact_digest:
            raise RunnerError(HTTPStatus.CONFLICT, "artifact digest does not match the approved file")

        with self.lock:
            existing = self.find_by_request(request_key, required=False)
            if existing is not None:
                if existing.get("artifactDigest") != artifact_digest:
                    raise RunnerError(HTTPStatus.CONFLICT, "request key already has another artifact")
                return self._public(existing)
            active = sum(1 for item in self._all_states() if item.get("status") in ACTIVE)
            if active >= self.max_concurrency:
                raise RunnerError(HTTPStatus.TOO_MANY_REQUESTS, "local Spark concurrency limit reached")

            job_id = uuid.uuid4().hex
            job_dir = self._job_dir(job_id)
            job_dir.mkdir(mode=0o700)
            result_path = job_dir / "result.json"
            command = [
                str(self.spark_submit),
                "--master", f"local[{cores}]",
                "--driver-memory", f"{memory_mb}m",
                "--conf", "spark.sql.shuffle.partitions=4",
            ]
            command.extend(spark_catalog_arguments())
            if os.environ.get("AETHERIS_RUNNER_SPARK_USER_CLASS_PATH_FIRST", "").strip().lower() == "true":
                command.extend(["--conf", "spark.driver.userClassPathFirst=true",
                                "--conf", "spark.executor.userClassPathFirst=true"])
            jars = os.environ.get("AETHERIS_RUNNER_SPARK_JARS", "").strip()
            if jars:
                command.extend(["--jars", jars])
            if artifact_type == "spark-jar":
                command.extend(["--class", main_class, str(self.artifact_root / artifact_key)])
            else:
                command.append(str(self.artifact_root / artifact_key))
            command.extend(["--biz-date", biz_date, "--attempt-id", attempt_id])
            if maintenance_operation is not None:
                command.extend(["--maintenance-operation", maintenance_operation,
                                "--maintenance-statement", maintenance_statement])
            if schema_change_operation is not None:
                command.extend(["--schema-change-operation", schema_change_operation,
                                "--schema-change-statement", schema_change_statement])
            environment = {
                "HOME": os.environ.get("HOME", str(Path.home())),
                "PATH": "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin",
                "LANG": "C.UTF-8",
                "LC_ALL": "C.UTF-8",
                "JAVA_HOME": "/usr/lib/jvm/java-11-openjdk-amd64",
                "SPARK_HOME": str(self.spark_submit.parent.parent),
                "HADOOP_CONF_DIR": os.environ.get("AETHERIS_RUNNER_HADOOP_CONF_DIR", ""),
                "HIVE_CONF_DIR": os.environ.get("AETHERIS_RUNNER_HIVE_CONF_DIR", ""),
                "PYSPARK_PYTHON": "/usr/bin/python3",
                "AETHERIS_JOB_RESULT_PATH": str(result_path),
            }
            # AWS SDK resolves S3FileIO credentials from the child environment;
            # never add access keys to spark-submit arguments or persisted state.
            for key in ("AWS_ACCESS_KEY_ID", "AWS_SECRET_ACCESS_KEY", "AWS_SESSION_TOKEN",
                        "AWS_REGION", "AWS_DEFAULT_REGION", "AWS_PROFILE", "AWS_SHARED_CREDENTIALS_FILE"):
                value = os.environ.get(key)
                if value:
                    environment[key] = value
            environment = {key: value for key, value in environment.items() if value}
            state: dict[str, object] = {
                "id": job_id,
                "requestKey": request_key,
                "status": "submitting",
                "artifactKey": artifact_key,
                "artifactType": artifact_type,
                "artifactDigest": artifact_digest,
                "mainClass": main_class or None,
                "parameters": {"bizDate": biz_date, "attemptId": attempt_id,
                                **({"schemaChange": {"operation": schema_change_operation,
                                                      "statement": schema_change_statement}}
                                   if schema_change_operation is not None else {}),
                                **({"maintenance": {"operation": maintenance_operation,
                                                     "statement": maintenance_statement}}
                                   if maintenance_operation is not None else {})},
                "resources": {"cores": cores, "driverMemoryMb": memory_mb},
                "timeoutSeconds": timeout_seconds,
                "submittedAt": utc_now(),
            }
            self._save(state)
            try:
                process = subprocess.Popen(
                    command,
                    cwd=str(job_dir),
                    env=environment,
                    stdin=subprocess.DEVNULL,
                    stdout=subprocess.PIPE,
                    stderr=subprocess.STDOUT,
                    start_new_session=True,
                )
            except OSError as error:
                state.update(status="failed", finishedAt=utc_now(), errorCode="SUBMIT_FAILED",
                             errorMessage="spark-submit could not be started")
                self._save(state)
                raise RunnerError(HTTPStatus.BAD_GATEWAY, "spark-submit could not be started") from error
            state.update(status="running", pid=process.pid, processStart=self._process_start(process.pid),
                         startedAt=utc_now(), heartbeatAt=utc_now())
            self.processes[job_id] = process
            self._save(state)
            threading.Thread(target=self._capture_log, args=(job_id, process), daemon=True).start()
            threading.Thread(target=self._monitor, args=(job_id, process), daemon=True).start()
            return self._public(state)

    def status(self, job_id: str) -> dict[str, object]:
        with self.lock:
            state = self._load(job_id)
            if state.get("status") in ACTIVE:
                process = self.processes.get(job_id)
                if process is not None and process.poll() is None:
                    state["heartbeatAt"] = utc_now()
                    self._read_result(state, allow_terminal=False)
                    self._save(state)
                elif self._owns_live_pid(state):
                    state["heartbeatAt"] = utc_now()
                    self._read_result(state, allow_terminal=False)
                    self._save(state)
                elif process is not None:
                    # The child has exited, but its monitor thread may still be
                    # persisting the result.  Keep the attempt observable for
                    # one more poll; marking it unknown here would turn a
                    # successful local Spark exit into a false restart result.
                    self._read_result(state, allow_terminal=True)
                    if state.get("status") in ACTIVE:
                        state["heartbeatAt"] = utc_now()
                        self._save(state)
                else:
                    self._read_result(state, allow_terminal=True)
                    if state.get("status") in ACTIVE:
                        state.update(status="unknown", finishedAt=utc_now(),
                                     errorCode="RUNNER_RESTART_RESULT_UNKNOWN",
                                     errorMessage="process ended while runner was unavailable")
                        self._save(state)
            return self._public(state)

    def find_by_request(self, request_key: str, required: bool = True) -> dict[str, object] | None:
        if not REQUEST_KEY.fullmatch(request_key):
            raise RunnerError(HTTPStatus.BAD_REQUEST, "invalid request key")
        for state in self._all_states():
            if state.get("requestKey") == request_key:
                return state
        if required:
            raise RunnerError(HTTPStatus.NOT_FOUND, "job not found")
        return None

    def logs(self, job_id: str, offset: int, limit: int) -> dict[str, object]:
        self._load(job_id)
        offset = max(0, offset)
        limit = max(1, min(limit, 65536))
        path = self._job_dir(job_id) / "job.log"
        if not path.exists():
            return {"content": "", "nextOffset": 0, "eof": True}
        size = path.stat().st_size
        offset = min(offset, size)
        with path.open("rb") as stream:
            stream.seek(offset)
            content = stream.read(limit)
        return {
            "content": content.decode("utf-8", errors="replace"),
            "nextOffset": offset + len(content),
            "eof": offset + len(content) >= size,
        }

    def cancel(self, job_id: str) -> dict[str, object]:
        with self.lock:
            state = self._load(job_id)
            if state.get("status") in TERMINAL:
                return self._public(state)
            state["status"] = "cancel_requested"
            state["heartbeatAt"] = utc_now()
            self._save(state)
            process = self.processes.get(job_id)
            pid = int(state.get("pid", 0))
            if process is not None and process.poll() is None:
                self._terminate_group(pid)
                try:
                    process.wait(timeout=10)
                except subprocess.TimeoutExpired:
                    os.killpg(pid, signal.SIGKILL)
                    process.wait(timeout=5)
            elif self._owns_live_pid(state):
                self._terminate_group(pid)
                deadline = time.monotonic() + 10
                while time.monotonic() < deadline and self._owns_live_pid(state):
                    time.sleep(0.2)
                if self._owns_live_pid(state):
                    os.killpg(pid, signal.SIGKILL)
            if self._owns_live_pid(state):
                state.update(status="unknown", errorCode="CANCEL_UNCONFIRMED",
                             errorMessage="remote process termination was not confirmed")
            else:
                state.update(status="cancelled", finishedAt=utc_now(), errorCode=None,
                             errorMessage=None, cancellationConfirmedAt=utc_now())
            self._save(state)
            return self._public(state)

    def health(self) -> dict[str, object]:
        active = sum(1 for item in self._all_states() if item.get("status") in ACTIVE)
        return {"status": "UP", "version": RUNNER_VERSION, "activeJobs": active,
                "maxConcurrency": self.max_concurrency}

    def _all_states(self) -> list[dict[str, object]]:
        result = []
        for path in self.state_root.glob("*/state.json"):
            try:
                result.append(json.loads(path.read_text(encoding="utf-8")))
            except (OSError, json.JSONDecodeError):
                continue
        return result

    def _capture_log(self, job_id: str, process: subprocess.Popen[bytes]) -> None:
        path = self._job_dir(job_id) / "job.log"
        written = 0
        with path.open("w", encoding="utf-8") as target:
            os.chmod(path, 0o600)
            assert process.stdout is not None
            for raw in iter(process.stdout.readline, b""):
                line = redact(raw.decode("utf-8", errors="replace"))
                encoded = line.encode("utf-8")
                if written < self.max_log_bytes:
                    remaining = self.max_log_bytes - written
                    target.write(encoded[:remaining].decode("utf-8", errors="ignore"))
                    target.flush()
                    written += min(len(encoded), remaining)

    def _monitor(self, job_id: str, process: subprocess.Popen[bytes]) -> None:
        started = time.monotonic()
        timed_out = False
        while process.poll() is None:
            state = self._load(job_id)
            if state.get("status") == "cancel_requested":
                time.sleep(0.2)
                continue
            if time.monotonic() - started > int(state["timeoutSeconds"]):
                timed_out = True
                self._terminate_group(process.pid)
                try:
                    process.wait(timeout=10)
                except subprocess.TimeoutExpired:
                    os.killpg(process.pid, signal.SIGKILL)
                break
            time.sleep(1)
            with self.lock:
                state = self._load(job_id)
                state["heartbeatAt"] = utc_now()
                self._read_result(state, allow_terminal=False)
                self._save(state)
        return_code = process.wait()
        with self.lock:
            state = self._load(job_id)
            self._read_result(state, allow_terminal=True)
            if state.get("status") == "cancel_requested":
                state.update(status="cancelled", errorCode=None, errorMessage=None,
                             cancellationConfirmedAt=utc_now())
            elif timed_out:
                state.update(status="timed_out", errorCode="TIMEOUT",
                             errorMessage="managed Spark job exceeded its time limit")
            elif state.get("status") not in ("succeeded", "failed"):
                state.update(status="succeeded" if return_code == 0 else "failed",
                             errorCode=None if return_code == 0 else "PROCESS_EXIT",
                             errorMessage=None if return_code == 0 else "managed Spark process failed")
            state.update(exitCode=return_code, finishedAt=state.get("finishedAt") or utc_now(),
                         heartbeatAt=utc_now())
            self._save(state)
            self.processes.pop(job_id, None)

    def _read_result(self, state: dict[str, object], allow_terminal: bool) -> None:
        path = self._job_dir(str(state["id"])) / "result.json"
        if not path.is_file():
            return
        try:
            result = json.loads(path.read_text(encoding="utf-8"))
        except (OSError, json.JSONDecodeError):
            return
        if not isinstance(result, dict):
            return
        state["result"] = result
        for source, target in (("applicationId", "applicationId"), ("affectedRows", "affectedRows")):
            if result.get(source) is not None:
                state[target] = result[source]
        if allow_terminal and result.get("status") in ("succeeded", "failed"):
            state["status"] = result["status"]
            if result.get("errorMessage"):
                state["errorCode"] = "APPLICATION_FAILED"
                state["errorMessage"] = str(result["errorMessage"])[:512]

    def _recover(self) -> None:
        for state in self._all_states():
            if state.get("status") in ACTIVE and not self._owns_live_pid(state):
                self._read_result(state, allow_terminal=True)
                if state.get("status") in ACTIVE:
                    state.update(status="unknown", finishedAt=utc_now(),
                                 errorCode="RUNNER_RESTART_RESULT_UNKNOWN",
                                 errorMessage="process state was lost during runner restart")
                self._save(state)

    @staticmethod
    def _process_start(pid: int) -> str | None:
        try:
            return Path(f"/proc/{pid}/stat").read_text().split()[21]
        except (OSError, IndexError):
            return None

    def _owns_live_pid(self, state: dict[str, object]) -> bool:
        pid = int(state.get("pid", 0))
        if pid <= 1 or self._process_start(pid) != state.get("processStart"):
            return False
        try:
            command = Path(f"/proc/{pid}/cmdline").read_bytes().decode(errors="ignore")
        except OSError:
            return False
        owns_artifact = str(state.get("artifactKey", "")) in command
        owns_submitter = str(self.spark_submit) in command or "org.apache.spark.deploy.SparkSubmit" in command
        return owns_artifact and owns_submitter

    @staticmethod
    def _terminate_group(pid: int) -> None:
        try:
            os.killpg(pid, signal.SIGTERM)
        except ProcessLookupError:
            return


class Handler(BaseHTTPRequestHandler):
    server_version = "AetherisSparkRunner/1"

    @property
    def runner(self) -> JobRunner:
        return self.server.runner  # type: ignore[attr-defined]

    def do_GET(self) -> None:  # noqa: N802
        self._handle("GET")

    def do_POST(self) -> None:  # noqa: N802
        self._handle("POST")

    def do_DELETE(self) -> None:  # noqa: N802
        self._handle("DELETE")

    def log_message(self, fmt: str, *args: object) -> None:
        print(f"{self.address_string()} {fmt % args}", flush=True)

    def _handle(self, method: str) -> None:
        try:
            if not self.runner.authenticate(self.headers.get("X-Aetheris-Runner-Token")):
                raise RunnerError(HTTPStatus.UNAUTHORIZED, "invalid runner token")
            parsed = urlparse(self.path)
            path = parsed.path
            if method == "GET" and path == "/health":
                return self._json(HTTPStatus.OK, self.runner.health())
            if method == "POST" and path == "/v1/jobs":
                return self._json(HTTPStatus.ACCEPTED, self.runner.submit(self._body()))
            match = re.fullmatch(r"/v1/artifacts/([^/]+)", path)
            if method == "POST" and match:
                return self._json(HTTPStatus.CREATED,
                                  self.runner.upload_artifact(unquote(match.group(1)), self._body()))
            if method == "GET" and match:
                return self._json(HTTPStatus.OK, self.runner.artifact(unquote(match.group(1))))
            match = re.fullmatch(r"/v1/jobs/by-request/([^/]+)", path)
            if method == "GET" and match:
                state = self.runner.find_by_request(unquote(match.group(1)))
                return self._json(HTTPStatus.OK, self.runner._public(state))
            match = re.fullmatch(r"/v1/jobs/([0-9a-f]{32})/logs", path)
            if method == "GET" and match:
                query = parse_qs(parsed.query)
                return self._json(HTTPStatus.OK, self.runner.logs(
                    match.group(1), int(query.get("offset", ["0"])[0]),
                    int(query.get("limit", ["65536"])[0])))
            match = re.fullmatch(r"/v1/jobs/([0-9a-f]{32})", path)
            if match and method == "GET":
                return self._json(HTTPStatus.OK, self.runner.status(match.group(1)))
            if match and method == "DELETE":
                return self._json(HTTPStatus.OK, self.runner.cancel(match.group(1)))
            raise RunnerError(HTTPStatus.NOT_FOUND, "endpoint not found")
        except RunnerError as error:
            self._json(error.status, {"message": str(error)})
        except (ValueError, TypeError, json.JSONDecodeError):
            self._json(HTTPStatus.BAD_REQUEST, {"message": "invalid request"})
        except Exception as error:  # keep internal details out of the HTTP response
            print(f"runner error: {type(error).__name__}: {error}", flush=True)
            self._json(HTTPStatus.INTERNAL_SERVER_ERROR, {"message": "runner operation failed"})

    def _body(self) -> dict[str, object]:
        length = int(self.headers.get("Content-Length", "0"))
        if length <= 0 or length > 96 * 1024 * 1024:
            raise RunnerError(HTTPStatus.BAD_REQUEST, "invalid request size")
        payload = json.loads(self.rfile.read(length))
        if not isinstance(payload, dict):
            raise RunnerError(HTTPStatus.BAD_REQUEST, "JSON object required")
        return payload

    def _json(self, status: int, body: dict[str, object]) -> None:
        encoded = json.dumps(body, ensure_ascii=False).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(encoded)))
        self.send_header("Cache-Control", "no-store")
        self.send_header("X-Content-Type-Options", "nosniff")
        self.end_headers()
        self.wfile.write(encoded)


def arguments() -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    parser.add_argument("--listen", default="127.0.0.1")
    parser.add_argument("--port", type=int, default=18081)
    parser.add_argument("--artifact-root", required=True)
    parser.add_argument("--state-root", required=True)
    parser.add_argument("--token-file", required=True)
    parser.add_argument("--spark-submit", default="/usr/local/spark-3.5.6-bin-hadoop3/bin/spark-submit")
    parser.add_argument("--max-concurrency", type=int, default=1, choices=range(1, 3))
    parser.add_argument("--max-log-bytes", type=int, default=10 * 1024 * 1024)
    parser.add_argument("--default-timeout", type=int, default=1800)
    return parser.parse_args()


def main() -> None:
    args = arguments()
    runner = JobRunner(args)
    server = ThreadingHTTPServer((args.listen, args.port), Handler)
    server.runner = runner  # type: ignore[attr-defined]
    server.daemon_threads = True
    print(f"Aetheris Spark runner {RUNNER_VERSION} listening on {args.listen}:{args.port}", flush=True)
    server.serve_forever()


if __name__ == "__main__":
    main()
