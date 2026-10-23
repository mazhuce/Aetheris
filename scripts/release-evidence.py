#!/usr/bin/env python3
"""Release evidence v2: capture a context BEFORE running producers, then wrap each
producer with `run`. Never stamp a historical report as a new execution.

create-context --run-id ID --artifact image=SHA256 --config-fingerprint SHA256
  --output artifacts/release/ID/context.json
run --context ... --manifest ... --name GATE --report REPORT -- COMMAND [ARGS]
aggregate --context ... --manifest ... --checks CHECKS --output REPORT
validate --report REPORT

All paths are explicit, repository-contained. `run` records the latest attempt,
including failures, before starting a producer; no directory scan or fallback.
The config fingerprint must be computed from a redacted deployment contract;
never pass secrets as CLI arguments. Digests are integrity evidence, not a
substitute for a trusted CI runner or signed publication manifest.
"""
from __future__ import annotations

import argparse
import contextlib
import hashlib
import importlib.util
import json
import math
import os
from pathlib import Path
import re
import subprocess
import sys
import time
import uuid
from datetime import datetime, timezone

SHA = re.compile(r"^[0-9a-f]{64}$")
DATASOURCE_TYPES = {"postgresql", "mysql", "trino", "mariadb", "clickhouse", "cockroachdb", "sqlite", "h2", "derby"}
RUNTIME_NAMES = ("r1-empty-rebuild", "r2-cdc", "r3-managed-lifecycle", "r4-event-time-quality",
                 "r4-r5-control-plane", "r5-maintenance-runner", "datasource-nine-local-runtime")
REQUIRED_NAMES = (*RUNTIME_NAMES, "r3-ha-runtime")
OFFLINE_NAMES = {"r1-release-validator", "r3-ha-compose-contract", "script-regression-suite"}


def now_iso():
    return datetime.now(timezone.utc).isoformat()


def read(path):
    body = json.loads(Path(path).read_text(encoding="utf-8-sig"))
    if not isinstance(body, dict):
        raise ValueError("JSON evidence root must be an object")
    return body


def object_digest(value):
    return hashlib.sha256(json.dumps(value, sort_keys=True, separators=(",", ":")).encode()).hexdigest()


def file_digest(path):
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()


def contained(root, value):
    root = Path(root).resolve()
    path = Path(value)
    path = (root / path).resolve() if not path.is_absolute() else path.resolve()
    if not path.is_relative_to(root):
        raise ValueError("evidence path escapes repository")
    return path


def relative(root, path):
    return contained(root, path).relative_to(Path(root).resolve()).as_posix()


def write(path, value):
    path = Path(path)
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = path.with_name(path.name + f".tmp.{os.getpid()}")
    try:
        temporary.write_text(json.dumps(value, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
        os.replace(temporary, path)
    finally:
        temporary.unlink(missing_ok=True)


def git(root, *args):
    return subprocess.check_output(["git", "-C", str(root), *args], stderr=subprocess.PIPE)


def source_snapshot(root):
    commit = git(root, "rev-parse", "HEAD").decode().strip()
    paths = git(root, "ls-files", "-z", "--cached", "--others", "--exclude-standard").split(b"\0")
    digest = hashlib.sha256()
    for raw in sorted(set(paths)):
        if not raw:
            continue
        name = os.fsdecode(raw).replace("\\", "/")
        # Reports/state change during execution and must not change source identity.
        if name.startswith(("artifacts/", "backups/", ".playwright-mcp/", "apps/web/public/acceptance/")):
            continue
        path = contained(root, name)
        digest.update(raw + b"\0")
        digest.update(b"file\0" + hashlib.sha256(path.read_bytes()).digest() if path.is_file() else b"missing\0")
        digest.update(b"\0")
    return {"commit": commit, "worktreeSha256": digest.hexdigest()}


def create_context(root, run_id, artifacts, config_fingerprint):
    if not re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9._-]{0,127}", run_id):
        raise ValueError("invalid runId")
    if not artifacts or any(not k or not SHA.fullmatch(v) for k, v in artifacts.items()):
        raise ValueError("artifact digests are required")
    if not SHA.fullmatch(config_fingerprint):
        raise ValueError("redacted config fingerprint is required")
    return {"schemaVersion": 2, "runId": run_id, "source": source_snapshot(root),
            "artifactDigests": artifacts, "configFingerprint": config_fingerprint, "startedAtUtc": now_iso()}


def timestamp(value):
    parsed = datetime.fromisoformat(str(value).replace("Z", "+00:00"))
    if parsed.tzinfo is None:
        raise ValueError("timestamp must contain a timezone")
    return parsed.astimezone(timezone.utc)


def check_age(value, max_age_hours):
    if not math.isfinite(max_age_hours) or not 0 < max_age_hours <= 24:
        raise ValueError("invalid freshness limit")
    age = (datetime.now(timezone.utc) - timestamp(value)).total_seconds()
    if age < -60 or age > max_age_hours * 3600:
        raise ValueError("evidence timestamp is stale or in the future")


def validate_context(root, body, max_age_hours=24):
    if not isinstance(body, dict) or body.get("schemaVersion") != 2 or not re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9._-]{0,127}", str(body.get("runId", ""))):
        raise ValueError("unbound historical context; rerun required")
    if body.get("source") != source_snapshot(root):
        raise ValueError("source commit/worktree does not match execution context")
    if not isinstance(body.get("artifactDigests"), dict) or not body["artifactDigests"] or any(not k or not SHA.fullmatch(str(v)) for k, v in body["artifactDigests"].items()):
        raise ValueError("artifact digests are missing")
    if not SHA.fullmatch(str(body.get("configFingerprint", ""))):
        raise ValueError("config fingerprint is missing")
    check_age(body.get("startedAtUtc"), max_age_hours)


def validate_entry(root, context, entry, max_age_hours=24):
    if not isinstance(entry, dict):
        raise ValueError("invalid runtime evidence entry")
    if entry.get("status") != "passed" or entry.get("exitCode") != 0:
        raise ValueError("latest producer attempt failed or is incomplete")
    if entry.get("contextSha256") != object_digest(context):
        raise ValueError("evidence context/run does not match")
    start, finish = timestamp(entry.get("startedAtUtc")), timestamp(entry.get("finishedAtUtc"))
    if start < timestamp(context["startedAtUtc"]) or finish < start:
        raise ValueError("evidence predates execution context")
    check_age(entry.get("finishedAtUtc"), max_age_hours)
    path = contained(root, entry.get("path", ""))
    if not path.is_file() or file_digest(path) != entry.get("sha256"):
        raise ValueError("evidence file is missing or digest changed")
    body = read(path)
    if body.get("status") != "passed":
        raise ValueError("report did not pass")
    execution_times = [body[key] for key in ("startedAtUtc", "finishedAtUtc", "generatedAtUtc", "startedAt", "finishedAt", "generatedAt") if body.get(key)]
    # R5 records timestamps in per-job phases rather than at the report root.
    if not execution_times and isinstance(body.get("phases"), dict):
        for phase in body["phases"].values():
            if isinstance(phase, dict):
                execution_times.extend(phase[key] for key in ("startedAt", "finishedAt", "startedAtUtc", "finishedAtUtc") if phase.get(key))
    if not execution_times or any(not start <= timestamp(value) <= finish for value in execution_times):
        raise ValueError("report execution timestamps are outside the producer attempt; historical copy refused")
    return body


def validate_datasource(body):
    results = body.get("results") or []
    types = body.get("testedTypes") or []
    if not isinstance(types, list) or any(not isinstance(x, str) for x in types) or body.get("status") != "passed" or len(types) != 9 or set(types) != DATASOURCE_TYPES:
        raise ValueError("nine-source coverage is incomplete")
    if not isinstance(results, list) or any(not isinstance(r, dict) or not isinstance(r.get("type"), str) for r in results) or len(results) != 9 or {r.get("type") for r in results} != DATASOURCE_TYPES:
        raise ValueError("nine-source results are incomplete or duplicated")
    if any(r.get("status") != "passed" or r.get("error") or type(r.get("rowCount")) is not int or r["rowCount"] != 1 for r in results):
        raise ValueError("datasource application result failed")
    cleanup = body.get("cleanup") or {}
    if not isinstance(cleanup, dict) or cleanup.get("datasourceRowsRemoved") is not True or cleanup.get("smokeStackStopped") is not True:
        raise ValueError("datasource cleanup is incomplete")


def load_validator(filename):
    path = Path(__file__).with_name(filename)
    spec = importlib.util.spec_from_file_location(filename.replace("-", "_"), path)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def validate_manifest(root, context, manifest, max_age_hours=24):
    validate_context(root, context, max_age_hours)
    if manifest.get("schemaVersion") != 2 or manifest.get("contextSha256") != object_digest(context):
        raise ValueError("manifest context/run mismatch")
    evidence = manifest.get("evidence") or {}
    if not isinstance(evidence, dict) or any(not isinstance(x, dict) for x in evidence.values()) or set(evidence) != set(REQUIRED_NAMES):
        raise ValueError("required evidence names are missing or unknown")
    if len({str(contained(root, entry.get("path", ""))) for entry in evidence.values()}) != len(REQUIRED_NAMES):
        raise ValueError("different runtime gates must reference distinct reports")
    reports = {}
    for name in REQUIRED_NAMES:
        entry = evidence[name]
        reports[name] = validate_entry(root, context, entry, max_age_hours)
    validate_datasource(reports["datasource-nine-local-runtime"])
    runtime_validator = load_validator("validate-runtime-evidence.py")
    runtime_validator.validate_r1(contained(root, evidence["r1-empty-rebuild"]["path"]))
    runtime_validator.validate_r2(contained(root, evidence["r2-cdc"]["path"]))
    ha = load_validator("audit-r3-ha-evidence.py").inspect(contained(root, evidence["r3-ha-runtime"]["path"]), max_age_hours)
    if ha["status"] != "passed":
        raise ValueError("R3 HA content/freshness audit did not pass")
    return reports


def validate_checks(root, context, checks, max_age_hours=24):
    if checks.get("contextSha256") != object_digest(context):
        raise ValueError("offline checks belong to another context/run")
    if timestamp(checks.get("startedAtUtc")) < timestamp(context["startedAtUtc"]):
        raise ValueError("offline checks predate the execution context")
    if timestamp(checks.get("finishedAtUtc")) < timestamp(checks["startedAtUtc"]):
        raise ValueError("invalid offline check execution interval")
    check_age(checks.get("finishedAtUtc"), max_age_hours)
    offline = checks.get("offlineChecks") or []
    if not isinstance(offline, list) or any(not isinstance(x, dict) or not isinstance(x.get("name"), str) for x in offline) or len(offline) != len(OFFLINE_NAMES) or {x.get("name") for x in offline} != OFFLINE_NAMES or any(x.get("status") != "passed" or type(x.get("exitCode")) is not int or x["exitCode"] != 0 for x in offline):
        raise ValueError("required offline checks failed or are missing")
    if not isinstance(checks.get("docker"), dict):
        raise ValueError("invalid Docker evidence")
    return offline


def run_checks(root, context_path, output, offline_only=False):
    context = read(context_path)
    validate_context(root, context)
    body = {"schemaVersion": 2, "contextSha256": object_digest(context), "startedAtUtc": now_iso(), "offlineChecks": []}
    commands = (
        ("r1-release-validator", ["scripts/validate-lakehouse-r1.py", "--mode", "release"]),
        ("r3-ha-compose-contract", ["scripts/validate-r3-ha.py"]),
        ("script-regression-suite", ["-m", "unittest", "discover", "-s", "scripts/tests", "-p", "test_*.py"]),
    )
    for name, arguments in commands:
        code = subprocess.run([sys.executable, "-B", *arguments], cwd=root, check=False).returncode
        body["offlineChecks"].append({"name": name, "status": "passed" if code == 0 else "failed", "exitCode": code})
    docker = {"available": False, "status": "skipped" if offline_only else "unavailable"}
    if not offline_only:
        try:
            result = subprocess.run(["docker", "version", "--format", "{{.Server.Version}}"], capture_output=True, cwd=root, timeout=30, check=False)
            if result.returncode == 0:
                docker = {"available": True, "status": "available"}
        except (OSError, subprocess.SubprocessError):
            pass
    body["docker"] = docker
    body["finishedAtUtc"] = now_iso()
    validate_context(root, context)
    write(output, body)
    return 0 if all(x["status"] == "passed" for x in body["offlineChecks"]) else 1


@contextlib.contextmanager
def manifest_lock(path):
    lock = path.with_name(path.name + ".lock")
    lock.parent.mkdir(parents=True, exist_ok=True)
    deadline = time.monotonic() + 10
    while True:
        try:
            descriptor = os.open(lock, os.O_CREAT | os.O_EXCL | os.O_WRONLY, 0o600)
            os.close(descriptor)
            break
        except FileExistsError:
            if time.monotonic() >= deadline:
                raise ValueError("manifest locked by another producer")
            time.sleep(0.05)
    try:
        yield
    finally:
        lock.unlink(missing_ok=True)


def update_attempt(path, context, name, entry, starting=False):
    with manifest_lock(path):
        manifest = read(path) if path.exists() else {"schemaVersion": 2, "contextSha256": object_digest(context), "evidence": {}}
        if manifest.get("contextSha256") != object_digest(context):
            raise ValueError("manifest already belongs to another context/run")
        previous = manifest["evidence"].get(name, {})
        if starting and previous.get("status") == "running":
            raise ValueError("producer already running; reconcile before retry")
        if not starting and previous.get("attemptId") != entry.get("attemptId"):
            raise ValueError("producer attempt was superseded")
        manifest["evidence"][name] = entry
        write(path, manifest)


def run_producer(root, context_path, manifest_path, name, report_path, command):
    context = read(context_path)
    validate_context(root, context)
    if name not in REQUIRED_NAMES or not command:
        raise ValueError("unknown producer name or empty command")
    if contained(root, report_path) in {contained(root, context_path), contained(root, manifest_path)}:
        raise ValueError("report must not overwrite context or manifest")
    entry = {"path": relative(root, report_path), "contextSha256": object_digest(context),
             "attemptId": uuid.uuid4().hex, "startedAtUtc": now_iso(), "status": "running", "exitCode": None}
    update_attempt(manifest_path, context, name, entry, starting=True)
    started_ns = time.time_ns()
    try:
        result = subprocess.run(command, cwd=root, check=False)
        entry["exitCode"] = result.returncode
        if result.returncode:
            raise ValueError("producer returned nonzero")
        if not report_path.is_file() or report_path.stat().st_mtime_ns < started_ns:
            raise ValueError("producer did not write a new report; historical reuse refused")
        validate_context(root, context)
        entry["sha256"] = file_digest(report_path)
        entry["status"] = "passed" if read(report_path).get("status") == "passed" else "failed"
    except (OSError, ValueError, subprocess.SubprocessError):
        entry["status"] = "failed"
        # Do not store command text, stdout, URLs or exception strings containing credentials.
        entry["error"] = "producer failed, source changed, or report was not freshly written"
        if entry["exitCode"] is None:
            entry["exitCode"] = 1
    finally:
        entry["finishedAtUtc"] = now_iso()
        if entry["status"] == "passed":
            try:
                validate_entry(root, context, entry)
            except (OSError, ValueError, KeyError, TypeError):
                entry["status"] = "failed"
                entry["error"] = "report content does not prove execution during this producer attempt"
        update_attempt(manifest_path, context, name, entry)
    return 0 if entry["status"] == "passed" else 1


def emit_summary(root, output, body, summary_path):
    reports = {item["name"]: item for item in body["runtimeReports"]}
    milestones = []
    for code, name in zip(("R1", "R2", "R3", "R4", "R5"), RUNTIME_NAMES[:4] + ("r5-maintenance-runner",)):
        entry = reports.get(name, {})
        milestones.append({"code": code, "status": entry.get("status", "missing"), "evidence": entry.get("path"),
                           "evidenceGeneratedAtUtc": entry.get("finishedAtUtc")})
    datasource = (body.get("additionalGates") or {}).get("datasourceNine", {})
    datasource_body = read(contained(root, datasource["path"])) if datasource.get("path") else {}
    summary = {"schemaVersion": 2, "gate": body["gate"], "status": body["status"],
               "generatedAtUtc": body["finishedAtUtc"], "source": relative(root, output), "milestones": milestones,
               "datasourceNineStatus": datasource.get("status", "missing"),
               "datasourceNineTypes": datasource_body.get("testedTypes", []),
               "datasourceNineFinishedAtUtc": datasource.get("finishedAtUtc"),
               "datasourceRuntimeResults": [{"type": x.get("type"), "status": x.get("status")} for x in datasource_body.get("results", [])],
               "externalDatasourceStatus": "missing", "dockerStatus": body.get("docker", {}).get("status", "unavailable"),
               "manualReviewRequired": body.get("manualReviewRequired", [])}
    write(summary_path, summary)


def aggregate(root, context_path, manifest_path, checks_path, output, max_age_hours=24, summary_path=None):
    body = {"schemaVersion": 2, "gate": "platform-release-acceptance", "status": "failed",
            "startedAtUtc": now_iso(), "runtimeReports": [], "additionalGates": {}, "manualReviewRequired": []}
    try:
        context, manifest, checks = read(context_path), read(manifest_path), read(checks_path)
        validate_manifest(root, context, manifest, max_age_hours)
        offline = validate_checks(root, context, checks, max_age_hours)
        body.update({"context": {"path": relative(root, context_path), "sha256": file_digest(context_path)},
                     "manifest": {"path": relative(root, manifest_path), "sha256": file_digest(manifest_path)},
                     "checks": {"path": relative(root, checks_path), "sha256": file_digest(checks_path)},
                     "runId": context["runId"], "source": context["source"], "artifactDigests": context["artifactDigests"],
                     "configFingerprint": context["configFingerprint"], "offlineChecks": offline, "docker": checks.get("docker") or {}})
        evidence = manifest["evidence"]
        body["runtimeReports"] = [{"name": name, **evidence[name]} for name in RUNTIME_NAMES]
        body["additionalGates"] = {"r3Ha": {"name": "r3-ha-runtime", **evidence["r3-ha-runtime"]},
                                   "datasourceNine": {"name": "datasource-nine-local-runtime", **evidence["datasource-nine-local-runtime"]}}
        body["status"] = "passed" if body["docker"].get("available") is True else "manual_review_required"
        if body["status"] != "passed":
            body["manualReviewRequired"] = ["docker-runtime"]
    except (OSError, ValueError, KeyError, TypeError):
        body["error"] = "release evidence missing, mismatched, stale, failed, or structurally invalid; rerun required"
    body["finishedAtUtc"] = now_iso()
    write(output, body)
    if summary_path is not None:
        emit_summary(root, output, body, summary_path)
    return 0 if body["status"] == "passed" else 2 if body["status"] == "manual_review_required" else 1


def validate_aggregate(root, path, max_age_hours=24):
    body = read(path)
    if body.get("schemaVersion") != 2 or body.get("status") != "passed":
        raise ValueError("release report is not a current passed v2 report")
    paths = {}
    for key in ("context", "manifest", "checks"):
        reference = body[key]
        paths[key] = contained(root, reference["path"])
        if file_digest(paths[key]) != reference["sha256"]:
            raise ValueError("aggregate reference digest mismatch")
    context, manifest = read(paths["context"]), read(paths["manifest"])
    validate_manifest(root, context, manifest, max_age_hours)
    checks = read(paths["checks"])
    offline = validate_checks(root, context, checks, max_age_hours)
    if body.get("offlineChecks") != offline or body.get("docker") != checks.get("docker"):
        raise ValueError("aggregate offline evidence mismatch")
    for key in ("runId", "source", "artifactDigests", "configFingerprint"):
        if body.get(key) != context[key]:
            raise ValueError("aggregate context mismatch")
    expected = [{"name": name, **manifest["evidence"][name]} for name in RUNTIME_NAMES]
    if body.get("runtimeReports") != expected:
        raise ValueError("aggregate selected evidence mismatch")
    expected_additional = {"r3Ha": {"name": "r3-ha-runtime", **manifest["evidence"]["r3-ha-runtime"]},
                           "datasourceNine": {"name": "datasource-nine-local-runtime", **manifest["evidence"]["datasource-nine-local-runtime"]}}
    if body.get("additionalGates") != expected_additional:
        raise ValueError("aggregate additional evidence mismatch")
    check_age(body["finishedAtUtc"], max_age_hours)
    load_validator("validate-platform-release-report.py").validate(path)
    return body


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, default=Path(__file__).resolve().parents[1])
    sub = parser.add_subparsers(dest="action", required=True)
    create = sub.add_parser("create-context")
    create.add_argument("--run-id", required=True)
    create.add_argument("--artifact", action="append", default=[])
    create.add_argument("--config-fingerprint", required=True)
    create.add_argument("--output", required=True)
    run = sub.add_parser("run")
    for key in ("context", "manifest", "name", "report"):
        run.add_argument("--" + key, required=True)
    run.add_argument("command", nargs=argparse.REMAINDER)
    checks = sub.add_parser("checks")
    checks.add_argument("--context", required=True)
    checks.add_argument("--output", required=True)
    checks.add_argument("--offline-only", action="store_true")
    agg = sub.add_parser("aggregate")
    for key in ("context", "manifest", "checks", "output"):
        agg.add_argument("--" + key, required=True)
    agg.add_argument("--max-age-hours", type=float, default=24)
    agg.add_argument("--summary")
    validate = sub.add_parser("validate")
    validate.add_argument("--report", required=True)
    validate.add_argument("--max-age-hours", type=float, default=24)
    args = parser.parse_args()
    root = args.root.resolve()
    try:
        if args.action == "create-context":
            output = contained(root, args.output)
            if output.exists():
                raise ValueError("execution context already exists; choose a new run")
            artifacts = dict(item.split("=", 1) for item in args.artifact)
            write(output, create_context(root, args.run_id, artifacts, args.config_fingerprint))
            return 0
        if args.action == "run":
            command = args.command[1:] if args.command[:1] == ["--"] else args.command
            return run_producer(root, contained(root, args.context), contained(root, args.manifest), args.name, contained(root, args.report), command)
        if args.action == "checks":
            return run_checks(root, contained(root, args.context), contained(root, args.output), args.offline_only)
        if not math.isfinite(args.max_age_hours) or args.max_age_hours <= 0 or args.max_age_hours > 24:
            raise ValueError("release freshness must be in (0, 24] hours")
        if args.action == "aggregate":
            return aggregate(root, contained(root, args.context), contained(root, args.manifest), contained(root, args.checks), contained(root, args.output), args.max_age_hours,
                             contained(root, args.summary) if args.summary else None)
        validate_aggregate(root, contained(root, args.report), args.max_age_hours)
        print("Current release evidence passed")
        return 0
    except (OSError, ValueError, KeyError, TypeError, subprocess.SubprocessError):
        print("Release evidence validation failed; inspect current run inputs and rerun producers", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
