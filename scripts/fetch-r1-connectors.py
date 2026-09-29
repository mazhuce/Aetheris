#!/usr/bin/env python3
"""Fetch and verify the connector artifacts declared by the R1 allowlist.

The output directory is intentionally outside Git's tracked artifact set. Each
file is downloaded to a temporary sibling and atomically renamed only after
the SHA-256 in the allowlist matches.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import pathlib
import sys
import tempfile
import tomllib
from urllib.request import Request, urlopen


ROOT = pathlib.Path(__file__).resolve().parents[1]
DEFAULT_ALLOWLIST = ROOT / "configs" / "lakehouse" / "connector-allowlist.toml"


class ConnectorFetchError(RuntimeError):
    pass


def artifact_name(coordinate: str) -> str:
    parts = coordinate.split(":")
    if len(parts) != 3 or any(not part for part in parts):
        raise ConnectorFetchError(f"invalid Maven coordinate: {coordinate}")
    return f"{parts[1]}-{parts[2]}.jar"


def sha256(path: pathlib.Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def load_connectors(path: pathlib.Path) -> list[dict[str, str]]:
    try:
        data = tomllib.loads(path.read_text(encoding="utf-8"))
        connectors = data["connector"]
    except (OSError, KeyError, TypeError, tomllib.TOMLDecodeError) as error:
        raise ConnectorFetchError(f"cannot read connector allowlist: {error}") from error
    if not isinstance(connectors, list) or not connectors:
        raise ConnectorFetchError("connector allowlist is empty")
    return connectors


def fetch(output: pathlib.Path, allowlist: pathlib.Path, check_only: bool = False) -> list[dict[str, object]]:
    connectors = load_connectors(allowlist)
    if not check_only:
        output.mkdir(parents=True, exist_ok=True)
    results: list[dict[str, object]] = []
    for connector in connectors:
        coordinate = str(connector.get("coordinate", ""))
        expected = str(connector.get("sha256", "")).lower()
        if len(expected) != 64 or expected == "pending_capture":
            raise ConnectorFetchError(f"connector has no captured SHA-256: {connector.get('id')}")
        name = artifact_name(coordinate)
        target = output / name
        if target.exists():
            actual = sha256(target)
            if actual != expected:
                raise ConnectorFetchError(f"existing artifact digest mismatch: {name}")
            results.append({
                "id": connector.get("id"), "coordinate": coordinate,
                "source": connector.get("source"), "license": connector.get("license"),
                "license_source": connector.get("license_source"),
                "kind": connector.get("kind"), "driver": connector.get("driver"),
                "file": name, "sha256": actual, "status": "verified",
            })
            continue
        if check_only:
            raise ConnectorFetchError(f"missing connector artifact: {target}")
        source = str(connector.get("source", ""))
        if not source.startswith("https://repo1.maven.org/"):
            raise ConnectorFetchError(f"untrusted connector source: {source}")
        fd, temporary = tempfile.mkstemp(prefix=f".{name}.", dir=output)
        os.close(fd)
        temporary_path = pathlib.Path(temporary)
        try:
            request = Request(source, headers={"User-Agent": "aetheris-r1-connector-fetch/1"})
            with urlopen(request, timeout=180) as response, temporary_path.open("wb") as stream:
                while chunk := response.read(1024 * 1024):
                    stream.write(chunk)
            actual = sha256(temporary_path)
            if actual != expected:
                raise ConnectorFetchError(f"downloaded artifact digest mismatch: {name}")
            temporary_path.replace(target)
            results.append({
                "id": connector.get("id"), "coordinate": coordinate,
                "source": source, "license": connector.get("license"),
                "license_source": connector.get("license_source"),
                "kind": connector.get("kind"), "driver": connector.get("driver"),
                "file": name, "sha256": actual, "status": "downloaded",
            })
        finally:
            temporary_path.unlink(missing_ok=True)
    if not check_only:
        manifest = {
            "schemaVersion": 1,
            "allowlist": allowlist.name,
            "connectors": results,
        }
        manifest_path = output / "CONNECTOR-MANIFEST.json"
        fd, temporary = tempfile.mkstemp(prefix=".CONNECTOR-MANIFEST.", dir=output)
        os.close(fd)
        temporary_path = pathlib.Path(temporary)
        try:
            temporary_path.write_text(json.dumps(manifest, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
            temporary_path.replace(manifest_path)
        finally:
            temporary_path.unlink(missing_ok=True)
    return results


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=pathlib.Path, required=True)
    parser.add_argument("--allowlist", type=pathlib.Path, default=DEFAULT_ALLOWLIST)
    parser.add_argument("--check-only", action="store_true")
    args = parser.parse_args(argv)
    try:
        result = fetch(args.output, args.allowlist, args.check_only)
    except (ConnectorFetchError, OSError) as error:
        print(f"R1 connector fetch failed: {error}", file=sys.stderr)
        return 1
    print(json.dumps({"status": "verified", "connectors": result}, ensure_ascii=False))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
