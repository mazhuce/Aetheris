#!/usr/bin/env python3
"""Read-only GitHub gate for the latest complete CI run of one exact commit."""
from __future__ import annotations

import argparse
import json
import os
from pathlib import Path
import re
import sys
import time
import urllib.error
import urllib.parse
import urllib.request

REQUIRED_JOBS = {"deployment-contracts", "documentation-contracts", "security", "spark-script-contracts", "backend", "frontend", "smoke"}
REPOSITORY = re.compile(r"^[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+$")
COMMIT = re.compile(r"^[0-9a-f]{40}$")
API = "https://api.github.com"


class SafeRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, request, fp, code, message, headers, newurl):
        if urllib.parse.urlsplit(newurl).scheme != "https":
            raise ValueError("non-HTTPS GitHub redirect refused")
        redirected = super().redirect_request(request, fp, code, message, headers, newurl)
        if urllib.parse.urlsplit(request.full_url).netloc != urllib.parse.urlsplit(newurl).netloc:
            redirected.remove_header("Authorization")
        return redirected


def request_bytes(path, limit=8 * 1024 * 1024):
    if not path.startswith("/repos/"):
        raise ValueError("invalid GitHub API path")
    token = os.environ.get("GH_TOKEN", "")
    if not token:
        raise ValueError("GH_TOKEN is required for release verification")
    request = urllib.request.Request(API + path, headers={"Authorization": "Bearer " + token,
        "Accept": "application/vnd.github+json", "X-GitHub-Api-Version": "2026-03-10", "User-Agent": "Aetheris-release-gate"})
    with urllib.request.build_opener(SafeRedirect()).open(request, timeout=30) as response:
        body = response.read(limit + 1)
    if len(body) > limit:
        raise ValueError("GitHub response exceeds the release gate limit")
    return body


def fetch_json(path):
    return json.loads(request_bytes(path))


def validate_run(body, repository, commit, expected_attempt=None, workflow="ci.yml"):
    if body.get("head_sha") != commit or body.get("event") != "push" or (body.get("head_repository") or {}).get("full_name", "").lower() != repository.lower():
        raise ValueError("run source identity mismatch")
    if body.get("path") != ".github/workflows/" + workflow:
        raise ValueError("unexpected workflow identity")
    if body.get("status") != "completed" or body.get("conclusion") != "success":
        raise ValueError("latest CI run failed or is incomplete")
    if type(body.get("run_attempt")) is not int or body["run_attempt"] < 1:
        raise ValueError("missing run attempt")
    if expected_attempt is not None and body["run_attempt"] != expected_attempt:
        raise ValueError("CI run attempt changed after publication")
    return body


def select_run(runs, repository, commit):
    eligible = [run for run in runs if run.get("head_sha") == commit and run.get("event") == "push"
                and (run.get("head_repository") or {}).get("full_name", "").lower() == repository.lower()
                and run.get("path") == ".github/workflows/ci.yml"]
    if not eligible:
        return None
    latest = max(eligible, key=lambda row: int(row["id"]))
    if latest.get("status") != "completed":
        return None
    return validate_run(latest, repository, commit)


def validate_jobs(jobs, commit):
    for name in REQUIRED_JOBS:
        matches = [job for job in jobs if job.get("name") == name]
        if len(matches) != 1 or matches[0].get("head_sha") != commit or matches[0].get("status") != "completed" or matches[0].get("conclusion") != "success":
            raise ValueError("required CI job missing, duplicated, skipped, or failed: " + name)


def jobs_for_run(repository, run_id, attempt, fetch=fetch_json):
    jobs = []
    for page in range(1, 11):
        body = fetch(f"/repos/{repository}/actions/runs/{run_id}/attempts/{attempt}/jobs?per_page=100&page={page}")
        batch = body.get("jobs", [])
        jobs.extend(batch)
        if len(batch) < 100:
            return jobs
    raise ValueError("CI job pagination exceeds limit")


def verify_exact(repository, commit, run_id, attempt, fetch=fetch_json):
    first = fetch(f"/repos/{repository}/actions/runs/{run_id}")
    validate_run(first, repository, commit, expected_attempt=attempt)
    validate_jobs(jobs_for_run(repository, run_id, attempt, fetch), commit)
    # A rerun can begin while job checks are being fetched. Re-read the run.
    validate_run(fetch(f"/repos/{repository}/actions/runs/{run_id}"), repository, commit, expected_attempt=attempt)
    return first


def verify_binding(repository, commit, run_id, attempt, fetch=fetch_json):
    run = verify_exact(repository, commit, run_id, attempt, fetch)
    latest = select_run(fetch(f"/repos/{repository}/actions/workflows/ci.yml/runs?head_sha={commit}&event=push&per_page=100").get("workflow_runs", []), repository, commit)
    if latest is None or int(latest["id"]) != run_id or latest["run_attempt"] != attempt:
        raise ValueError("published CI binding was superseded or rerun")
    return run


def wait_for_ci(repository, commit, wait_seconds=1800, fetch=fetch_json, sleep=time.sleep, monotonic=time.monotonic):
    deadline = monotonic() + wait_seconds
    while True:
        body = fetch(f"/repos/{repository}/actions/workflows/ci.yml/runs?head_sha={commit}&event=push&per_page=100")
        selected = select_run(body.get("workflow_runs", []), repository, commit)
        if selected:
            verified = verify_exact(repository, commit, int(selected["id"]), selected["run_attempt"], fetch)
            latest = select_run(fetch(f"/repos/{repository}/actions/workflows/ci.yml/runs?head_sha={commit}&event=push&per_page=100").get("workflow_runs", []), repository, commit)
            if latest is not None and int(latest["id"]) == int(selected["id"]) and latest["run_attempt"] == selected["run_attempt"]:
                return verified
        if monotonic() >= deadline:
            raise ValueError("deadline reached waiting for same-commit CI")
        sleep(min(15, max(0, deadline - monotonic())))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--repository", default=os.environ.get("GITHUB_REPOSITORY", ""))
    parser.add_argument("--commit", default=os.environ.get("GITHUB_SHA", ""))
    parser.add_argument("--wait-seconds", type=int, default=1800)
    parser.add_argument("--output", required=True)
    args = parser.parse_args()
    try:
        if not REPOSITORY.fullmatch(args.repository) or not COMMIT.fullmatch(args.commit) or not 0 <= args.wait_seconds <= 3600:
            raise ValueError("invalid CI gate arguments")
        ref = os.environ.get("GITHUB_REF", "")
        if ref and not re.fullmatch(r"refs/(?:heads/main|tags/v[A-Za-z0-9][A-Za-z0-9._-]*)", ref):
            raise ValueError("release ref is outside the trusted main/version-tag contract")
        run = wait_for_ci(args.repository, args.commit, args.wait_seconds)
        result = {"schemaVersion": 1, "repository": args.repository, "commit": args.commit,
                  "ciRunId": int(run["id"]), "ciRunAttempt": run["run_attempt"], "requiredJobs": sorted(REQUIRED_JOBS)}
        Path(args.output).write_text(json.dumps(result, indent=2) + "\n", encoding="utf-8")
        if os.environ.get("GITHUB_OUTPUT"):
            with open(os.environ["GITHUB_OUTPUT"], "a", encoding="utf-8") as output:
                output.write(f"ci_run_id={result['ciRunId']}\nci_run_attempt={result['ciRunAttempt']}\n")
        print(f"Verified complete CI run {result['ciRunId']} attempt {result['ciRunAttempt']} for {args.commit}")
        return 0
    except (OSError, ValueError, KeyError, TypeError, urllib.error.URLError):
        print("Same-commit complete CI verification failed; no release is authorized", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
