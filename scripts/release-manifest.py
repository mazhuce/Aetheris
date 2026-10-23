#!/usr/bin/env python3
"""Build, fetch and consume release manifests that actually pin Compose images.

Manifests contain public source/run/image identities only. GH_TOKEN is read from
the runner environment and is never embedded in a manifest or passed to SSH.
"""
from __future__ import annotations

import argparse
import base64
import hashlib
from datetime import datetime, timezone
import importlib.util
import io
import json
import os
from pathlib import Path
import re
import sys
import zipfile

spec = importlib.util.spec_from_file_location("aetheris_ci_gate", Path(__file__).with_name("verify-ci-run.py"))
ci = importlib.util.module_from_spec(spec)
spec.loader.exec_module(ci)
SERVICES = ("system", "metadata", "serving", "quality", "ingestion", "governance", "workflow", "ai-agent", "auth", "gateway", "web")
COMPOSE_SERVICES = {name: name if name in {"ai-agent", "gateway", "web"} else name + "-service" for name in SERVICES}
PREFIX = "ghcr.io/mazhuce/aetheris"
REF = re.compile(r"^refs/(?:heads/main|tags/v[A-Za-z0-9][A-Za-z0-9._-]*)$")
SHA256 = re.compile(r"^sha256:[0-9a-f]{64}$")
DATABASE_SERVICES = set(SERVICES) - {"gateway", "web"}


def database_sources(root=None):
    root=Path(root) if root is not None else Path(__file__).resolve().parents[1]
    return {p.relative_to(root).as_posix():hashlib.sha256(p.read_bytes()).hexdigest()
            for p in sorted(root.glob('services/*/src/main/resources/db/migration/*.sql'))}


def read(path):
    body = json.loads(Path(path).read_text(encoding="utf-8-sig"))
    if not isinstance(body, dict):
        raise ValueError("manifest must be an object")
    return body


def write(path, body):
    path = Path(path)
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(body, indent=2) + "\n", encoding="utf-8")


def image_ref(service, value):
    if service not in SERVICES or not isinstance(value, str) or not value.startswith(PREFIX + "-" + service + "@") or not SHA256.fullmatch(value.rsplit("@", 1)[-1]):
        raise ValueError("image must match the expected service repository and immutable digest")
    return value


def validate(body, commit=None):
    if not isinstance(body, dict) or body.get("schemaVersion") not in (1,2) or str(body.get("repository", "")).lower() != "mazhuce/aetheris" or not ci.COMMIT.fullmatch(str(body.get("commit", ""))):
        raise ValueError("invalid release source identity")
    if commit is not None and body["commit"] != commit:
        raise ValueError("deployment checkout does not match the release commit")
    for section in ("ci", "build"):
        value = body.get(section)
        if not isinstance(value, dict) or type(value.get("runId")) is not int or value["runId"] <= 0 or type(value.get("attempt")) is not int or value["attempt"] <= 0:
            raise ValueError("release run/attempt is missing")
    if not REF.fullmatch(str(body["build"].get("ref", ""))):
        raise ValueError("release ref is outside main/version tags")
    if body["ci"].get("requiredJobs") != sorted(ci.REQUIRED_JOBS):
        raise ValueError("release CI job contract does not match")
    images = body.get("images")
    if not isinstance(images, dict) or set(images) != set(SERVICES):
        raise ValueError("release must contain exactly the eleven application images")
    for service, value in images.items():
        image_ref(service, value)
    if body['schemaVersion']==2:
        sources=body.get('databaseSources')
        if not isinstance(sources,dict) or not sources:raise ValueError('Signed migration source binding is missing')
        covered=set()
        for path,digest in sources.items():
            match=re.fullmatch(r'services/([a-z-]+)/src/main/resources/db/migration/V[0-9][0-9._]*__[A-Za-z0-9_]+\.sql',path)
            if not match or match[1] not in DATABASE_SERVICES or not isinstance(digest,str) or not re.fullmatch('[0-9a-f]{64}',digest):
                raise ValueError('Invalid signed migration source entry')
            covered.add(match[1])
        if covered!=DATABASE_SERVICES:raise ValueError('Signed migration sources must cover nine services')
    return body


def identity(body):
    return "https://github.com/" + body["repository"] + "/.github/workflows/build.yml@" + body["build"]["ref"]


def compose_override(body):
    validate(body)
    return {"services": {COMPOSE_SERVICES[service]: {"image": ref} for service, ref in body["images"].items()}}


def validate_deploy_inputs(body, tag, policy, supplied_identity):
    validate(body)
    expected_tag = "sha-" + body["commit"][:7]
    allowed_tags = {"", expected_tag}
    if body["build"]["ref"].startswith("refs/tags/v"):
        allowed_tags.add(body["build"]["ref"][len("refs/tags/v"):])
    if tag not in allowed_tags or policy not in {"required", "disabled"} or supplied_identity not in {"", identity(body)}:
        raise ValueError("deployment input does not match the verified release")
    return expected_tag


def validate_build(run, repository, commit, attempt):
    ci.validate_run(run, repository, commit, expected_attempt=attempt, workflow="build.yml")


def create(repository, commit, ci_run, ci_attempt, build_run, build_attempt, build_ref, digest_dir):
    if repository.lower() != "mazhuce/aetheris" or not ci.COMMIT.fullmatch(commit):
        raise ValueError("unexpected repository/commit")
    ci.verify_binding(repository, commit, ci_run, ci_attempt)
    images = {}
    for path in Path(digest_dir).glob("*.json"):
        entry = read(path)
        name = entry.get("service")
        if name in images or entry.get("commit") != commit or entry.get("buildRunId") != build_run or entry.get("buildAttempt") != build_attempt:
            raise ValueError("image digest provenance mismatched or duplicated")
        images[name] = image_ref(name, entry.get("image"))
    body = {"schemaVersion": 2, "repository": repository, "commit": commit,
            "ci": {"runId": ci_run, "attempt": ci_attempt, "requiredJobs": sorted(ci.REQUIRED_JOBS)},
            "build": {"runId": build_run, "attempt": build_attempt, "ref": build_ref},
            "images": images, "databaseSources":database_sources(), "generatedAtUtc": datetime.now(timezone.utc).isoformat()}
    return validate(body)


def fetch_release(repository, run_id, output_dir):
    if repository.lower() != "mazhuce/aetheris" or run_id <= 0:
        raise ValueError("invalid release build selection")
    build = ci.fetch_json(f"/repos/{repository}/actions/runs/{run_id}")
    commit, attempt = build.get("head_sha"), build.get("run_attempt")
    if build.get("id") != run_id or not ci.COMMIT.fullmatch(str(commit)):
        raise ValueError("GitHub build identity mismatch")
    validate_build(build, repository, commit, attempt)
    artifact_name = f"release-manifest-{commit}-{attempt}"
    artifacts = []
    for page in range(1, 11):
        batch = ci.fetch_json(f"/repos/{repository}/actions/runs/{run_id}/artifacts?per_page=100&page={page}").get("artifacts", [])
        artifacts.extend(batch)
        if len(batch) < 100:
            break
    else:
        raise ValueError("release artifact pagination exceeds limit")
    selected = [item for item in artifacts if item.get("name") == artifact_name and item.get("expired") is False]
    if len(selected) != 1:
        raise ValueError("release manifest artifact is missing, expired, or ambiguous")
    data = ci.request_bytes(f"/repos/{repository}/actions/artifacts/{int(selected[0]['id'])}/zip", limit=1024 * 1024)
    with zipfile.ZipFile(io.BytesIO(data)) as archive:
        names = archive.namelist()
        expected = {"release-manifest.json", "release-manifest.bundle.json"}
        if set(names) != expected or len(names) != 2 or any(item.file_size > 512 * 1024 for item in archive.infolist()):
            raise ValueError("unexpected release artifact contents")
        contents = {name: archive.read(name) for name in names}
    body = json.loads(contents["release-manifest.json"])
    validate(body, commit)
    if body["build"]["runId"] != run_id or body["build"]["attempt"] != attempt:
        raise ValueError("manifest build provenance mismatch")
    ci.verify_binding(repository, commit, body["ci"]["runId"], body["ci"]["attempt"])
    validate_build(ci.fetch_json(f"/repos/{repository}/actions/runs/{run_id}"), repository, commit, attempt)
    directory = Path(output_dir)
    directory.mkdir(parents=True, exist_ok=True)
    for name, content in contents.items():
        (directory / name).write_bytes(content)
    return body, contents


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest="command", required=True)
    record = commands.add_parser("record-image")
    for key in ("service", "image", "commit", "output"):
        record.add_argument("--" + key, required=True)
    record.add_argument("--build-run-id", required=True, type=int)
    record.add_argument("--build-attempt", required=True, type=int)
    make = commands.add_parser("create")
    for key in ("repository", "commit", "build-ref", "digest-dir", "output"):
        make.add_argument("--" + key, required=True)
    for key in ("ci-run-id", "ci-attempt", "build-run-id", "build-attempt"):
        make.add_argument("--" + key, required=True, type=int)
    fetch = commands.add_parser("fetch")
    fetch.add_argument("--repository", required=True)
    fetch.add_argument("--build-run-id", required=True, type=int)
    fetch.add_argument("--output-dir", required=True)
    for key in ("validate", "override", "image", "identity", "commit"):
        command = commands.add_parser(key)
        command.add_argument("--manifest", required=True)
        if key == "validate": command.add_argument("--commit")
        if key == "override": command.add_argument("--output", required=True)
        if key == "image": command.add_argument("--service", required=True, choices=SERVICES)
    save = commands.add_parser("save-state")
    for key in ("manifest", "bundle", "output"):
        save.add_argument("--" + key, required=True)
    load = commands.add_parser("load-state")
    for key in ("state", "manifest-output", "bundle-output"):
        load.add_argument("--" + key, required=True)
    args = parser.parse_args()
    try:
        if args.command == "record-image":
            if not ci.COMMIT.fullmatch(args.commit) or args.build_run_id <= 0 or args.build_attempt <= 0:
                raise ValueError("invalid image build identity")
            write(args.output, {"service": args.service, "image": image_ref(args.service, args.image), "commit": args.commit,
                                "buildRunId": args.build_run_id, "buildAttempt": args.build_attempt})
        elif args.command == "create":
            write(args.output, create(args.repository, args.commit, args.ci_run_id, args.ci_attempt, args.build_run_id, args.build_attempt, args.build_ref, args.digest_dir))
        elif args.command == "fetch":
            body, contents = fetch_release(args.repository, args.build_run_id, args.output_dir)
            tag = validate_deploy_inputs(body, os.environ.get("REQUESTED_IMAGE_TAG", ""), os.environ.get("REQUESTED_COSIGN_POLICY", "required"), os.environ.get("REQUESTED_COSIGN_IDENTITY", ""))
            outputs = {"commit": body["commit"], "image_tag": tag, "identity": identity(body),
                       "manifest_base64": base64.b64encode(contents["release-manifest.json"]).decode(),
                       "bundle_base64": base64.b64encode(contents["release-manifest.bundle.json"]).decode()}
            deploy_path = os.environ.get("REQUESTED_DEPLOY_PATH", "")
            if not deploy_path.startswith("/") or "\0" in deploy_path or "\n" in deploy_path or "\r" in deploy_path:
                raise ValueError("deployment path must be an absolute Linux directory")
            outputs["path_base64"] = base64.b64encode(deploy_path.encode()).decode()
            print("::add-mask::" + outputs["path_base64"])
            if not os.environ.get("GITHUB_OUTPUT"):
                raise ValueError("GITHUB_OUTPUT is required for deployment transfer")
            with open(os.environ["GITHUB_OUTPUT"], "a", encoding="utf-8") as output:
                for key, value in outputs.items(): output.write(key + "=" + value + "\n")
        elif args.command == "save-state":
            validate(read(args.manifest))
            read(args.bundle)
            state = {"schemaVersion": 1, "manifestBase64": base64.b64encode(Path(args.manifest).read_bytes()).decode(),
                     "bundleBase64": base64.b64encode(Path(args.bundle).read_bytes()).decode()}
            output = Path(args.output)
            temporary = output.with_name(output.name + ".tmp." + str(os.getpid()))
            try:
                write(temporary, state)
                os.replace(temporary, output)
            finally:
                temporary.unlink(missing_ok=True)
        elif args.command == "load-state":
            state = read(args.state)
            if state.get("schemaVersion") != 1:
                raise ValueError("invalid release state")
            manifest_bytes = base64.b64decode(state["manifestBase64"], validate=True)
            bundle_bytes = base64.b64decode(state["bundleBase64"], validate=True)
            if max(len(manifest_bytes), len(bundle_bytes)) > 512 * 1024:
                raise ValueError("release state exceeds size limit")
            validate(json.loads(manifest_bytes))
            if not isinstance(json.loads(bundle_bytes), dict):
                raise ValueError("invalid release bundle")
            Path(args.manifest_output).write_bytes(manifest_bytes)
            Path(args.bundle_output).write_bytes(bundle_bytes)
        else:
            body = validate(read(args.manifest), getattr(args, "commit", None))
            if args.command == "override": write(args.output, compose_override(body))
            elif args.command == "image": print(body["images"][args.service])
            elif args.command == "identity": print(identity(body))
            elif args.command == "commit": print(body["commit"])
            else: print("Release manifest structure and source binding passed")
        return 0
    except (OSError, ValueError, KeyError, TypeError, zipfile.BadZipFile):
        print("Release manifest verification failed; deployment is blocked", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
