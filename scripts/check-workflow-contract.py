#!/usr/bin/env python3
"""Parse workflows and reject unpinned actions or disconnected release gates."""
import argparse
import copy
import json
from pathlib import Path
import re
import yaml


class WorkflowLoader(yaml.SafeLoader):
    pass


WorkflowLoader.yaml_implicit_resolvers = copy.deepcopy(yaml.SafeLoader.yaml_implicit_resolvers)
for key, entries in WorkflowLoader.yaml_implicit_resolvers.items():
    WorkflowLoader.yaml_implicit_resolvers[key] = [entry for entry in entries if entry[0] != "tag:yaml.org,2002:bool"]


def check_body(name, body, pins):
    if not isinstance(body, dict) or body.get("permissions", {}).get("contents") != "read":
        raise ValueError(name + ": top-level contents: read is required")
    for job in body.get("jobs", {}).values():
        for step in job.get("steps", []):
            if "uses" in step:
                action, separator, revision = step["uses"].partition("@")
                if not separator or not re.fullmatch(r"[0-9a-f]{40}", revision) or pins.get(action, {}).get("commit") != revision:
                    raise ValueError(name + ": action must match its verified SHA pin")
            shell = step.get("run", "") + str(step.get("with", {}).get("script", ""))
            if re.search(r"\$\{\{\s*(?:inputs|secrets)\.", shell):
                raise ValueError(name + ": input/secret shell interpolation is forbidden")
    if name == "build.yml":
        jobs = body["jobs"]
        if jobs["build"].get("needs") != "validate-deployment-contracts":
            raise ValueError("image build is disconnected from complete CI")
        gate = jobs["validate-deployment-contracts"]
        if not any("verify-ci-run.py" in step.get("run", "") for step in gate["steps"]):
            raise ValueError("same-commit CI gate is missing")
        if set(jobs["release-manifest"].get("needs", [])) != {"build", "validate-deployment-contracts"}:
            raise ValueError("release manifest is disconnected from CI or image build")
    if name == "deploy.yml":
        steps = body["jobs"]["deploy"]["steps"]
        if not any("release-manifest.py fetch" in step.get("run", "") for step in steps):
            raise ValueError("deployment does not select a verified release manifest")
        if not any("cosign verify-blob" in step.get("run", "") for step in steps):
            raise ValueError("release manifest signature gate is missing")


def validate(root):
    root = Path(root)
    pins = json.loads((root / ".github/workflows/action-pins.json").read_text(encoding="utf-8"))["actions"]
    paths = sorted((root / ".github/workflows").glob("*.yml"))
    for path in paths:
        check_body(path.name, yaml.load(path.read_text(encoding="utf-8"), Loader=WorkflowLoader), pins)
    return len(paths)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, default=Path(__file__).resolve().parents[1])
    args = parser.parse_args()
    try:
        print(f"Workflow contract passed: {validate(args.root)} workflows")
    except (OSError, ValueError, KeyError, TypeError, yaml.YAMLError) as error:
        raise SystemExit(str(error))


if __name__ == "__main__":
    main()
