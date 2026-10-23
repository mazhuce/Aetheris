#!/usr/bin/env python3
"""Scan project image defaults and verify same-tag official OCI digest locks.

--check is offline and fails closed on unresolved entries. --allow-unresolved is
an explicit diagnostic option, never a complete-lock acceptance. --online also
resolves current official tags and detects drift; it never rewrites the lock.
--resolve --output FILE emits a candidate (no source edits). No layers, images,
containers, builds, pushes or credentials from Docker configuration are used.
Build ARG overrides remain operator-controlled and are outside the default lock.
"""
import argparse
import base64
import datetime
import hashlib
import json
from pathlib import Path
import re
import urllib.error
import urllib.parse
import urllib.request

INDEX_TYPES = {"application/vnd.oci.image.index.v1+json", "application/vnd.docker.distribution.manifest.list.v2+json"}
MANIFEST_TYPES = {"application/vnd.oci.image.manifest.v1+json", "application/vnd.docker.distribution.manifest.v2+json"}
ACCEPT = ", ".join(sorted(INDEX_TYPES | MANIFEST_TYPES))
DIGEST = re.compile(r"sha256:[0-9a-f]{64}\Z")
MAX_METADATA = 2 * 1024 * 1024
SCOPE = "Project Dockerfile and Compose dependency defaults; application images use signed release manifests; ARG overrides are not locked"
OFFICIAL_REPOSITORIES = {"library/maven", "library/eclipse-temurin", "library/node", "library/nginx", "library/redis", "library/postgres", "library/mariadb", "library/mysql", "library/flink", "library/zookeeper", "prom/prometheus", "clickhouse/clickhouse-server", "cockroachdb/cockroach", "tabulario/iceberg-rest", "trinodb/trino"}


def utc():
    return datetime.datetime.now(datetime.timezone.utc).isoformat()


def sha(raw):
    return "sha256:" + hashlib.sha256(raw).hexdigest()


def strict_json(raw):
    def pairs(items):
        result = {}
        for key, value in items:
            if key in result:
                raise ValueError("Duplicate JSON field")
            result[key] = value
        return result
    return json.loads(raw, object_pairs_hook=pairs)


def project_files(root):
    # Exact project locations; never recurse into user/private directories.
    patterns = ("*Dockerfile*", "docker-compose*.yml", "docker-compose*.yaml",
                "apps/*/Dockerfile*", "services/*/Dockerfile*", "scripts/database/runner/Dockerfile*",
                "infrastructure/*/docker-compose*.yml", "infrastructure/*/docker-compose*.yaml")
    return sorted({p for pattern in patterns for p in root.glob(pattern) if p.is_file()})


def validate_reference(reference):
    if not isinstance(reference, str) or not reference or re.search(r"[\s#]", reference) or reference.count("@") > 1:
        raise ValueError("Invalid image reference")
    if "@" in reference and not DIGEST.fullmatch(reference.split("@", 1)[1]):
        raise ValueError("Invalid image digest")
    if not re.fullmatch(r"[A-Za-z0-9_./:@-]+", reference):
        raise ValueError("Unsupported image reference syntax")
    return reference


def compose_references(path, image_arguments):
    # Parse nodes rather than rendered Compose data: preserve source line marks,
    # accept Compose's !override/!reset tags without executing constructors, and
    # observe build.args defaults which can override pinned Dockerfile ARGs.
    import yaml
    def mapping(node):
        if not isinstance(node, yaml.MappingNode):
            raise ValueError("Expected Compose mapping")
        result = {}
        for key, value in node.value:
            if not isinstance(key, yaml.ScalarNode) or key.value in result:
                raise ValueError("Duplicate/invalid Compose mapping key")
            result[key.value] = value
        return result
    document = mapping(yaml.compose(path.read_text(encoding="utf-8-sig")))
    for service in mapping(document.get("services")).values():
        fields = mapping(service)
        if "image" in fields:
            node = fields["image"]
            if not isinstance(node, yaml.ScalarNode) or node.style in ("|", ">"):
                raise ValueError("Unsupported Compose image scalar")
            yield node.start_mark.line + 1, node.value, None
        build = fields.get("build")
        if not isinstance(build, yaml.MappingNode):
            continue
        arguments = mapping(build).get("args")
        if arguments is None:
            continue
        if isinstance(arguments, yaml.MappingNode):
            values = mapping(arguments)
        elif isinstance(arguments, yaml.SequenceNode):
            values = {}
            for node in arguments.value:
                if not isinstance(node, yaml.ScalarNode):
                    raise ValueError("Invalid Compose build argument")
                key, _, value = node.value.partition("=")
                if key in values:
                    raise ValueError("Duplicate Compose build argument")
                values[key] = yaml.ScalarNode("tag:yaml.org,2002:str", value, start_mark=node.start_mark)
        else:
            raise ValueError("Invalid Compose build arguments")
        for key, node in values.items():
            if key not in image_arguments:
                continue
            if not isinstance(node, yaml.ScalarNode):
                raise ValueError("Invalid image build argument")
            value = node.value if node.value and node.tag != "tag:yaml.org,2002:null" else "${" + key + "}"
            yield node.start_mark.line + 1, value, key


def inventory(root):
    root = Path(root)
    images, local_images, required_images, unresolved = {}, {}, {}, []
    paths = project_files(root)
    image_arguments = set()
    for path in paths:
        if "Dockerfile" in path.name:
            for line in path.read_text(encoding="utf-8-sig").splitlines():
                match = re.match(r"\s*FROM\s+(?:--platform=\S+\s+)?(\S+)", line, re.I)
                if match:
                    image_arguments.update(m[1] or m[2] for m in re.finditer(r"\$\{(\w+)\}|\$(\w+)", match[1]))
    for path in paths:
        name = path.relative_to(root).as_posix()
        dockerfile = "Dockerfile" in path.name
        defaults, stages, seen_from = {}, set(), False
        physical = path.read_text(encoding="utf-8-sig").splitlines() if dockerfile else []
        logical, pending, start = [], "", 0
        for number, line in enumerate(physical, 1):
            if not pending:
                start = number
            if line.rstrip().endswith("\\"):
                pending += line.rstrip()[:-1] + " "
                continue
            logical.append((start, pending + line))
            pending = ""
        if pending:
            raise ValueError("Unterminated Dockerfile continuation")
        if not dockerfile:
            logical = [(number, (value, argument)) for number, value, argument in compose_references(path, image_arguments)]
        for number, line in logical:
            if dockerfile:
                arg = re.fullmatch(r"\s*ARG\s+(\w+)(?:=(.*))?\s*", line, re.I)
                if arg and not seen_from and arg[2] is not None:
                    defaults[arg[1]] = arg[2].strip().strip('"\'')
                match = re.fullmatch(r"\s*FROM\s+(?:--platform=\S+\s+)?(\S+)(?:\s+AS\s+([A-Za-z0-9_.-]+))?\s*", line, re.I)
                if not match:
                    if re.match(r"\s*FROM\b", line, re.I):
                        raise ValueError("Unsupported FROM syntax")
                    continue
                raw = match[1]
                seen_from = True
                is_stage = raw.lower() in stages
                if match[2]:
                    stages.add(match[2].lower())
                if is_stage or raw.lower() == "scratch":
                    continue
                reference = re.sub(r"\$\{(\w+)\}|\$(\w+)", lambda m: defaults.get(m[1] or m[2], m[0]), raw)
            else:
                raw, argument = line
                if not raw or raw in ("|", ">"):
                    raise ValueError("Unsupported Compose image scalar")
                reference = re.sub(r"\$\{\w+:-([^{}]+)\}", lambda m: m[1], raw)
            source = {"path": name, "line": number}
            if not dockerfile and argument is not None:
                source.update(kind="compose-build-arg", argument=argument)
            if re.fullmatch(r"\$\{AETHERIS_IMAGE_PREFIX:-aetheris-local\}-(?:system|metadata|serving|quality|ingestion|governance|workflow|ai-agent|auth|gateway|web):\$\{AETHERIS_IMAGE_TAG:-[A-Za-z0-9_.-]+\}", raw):
                # Explicit application release references, not third-party bases.
                local_images.setdefault(raw, []).append(source)
                continue
            if name == "docker-compose.database-migrate.yml" and re.fullmatch(r"\$\{AETHERIS_DB_MIGRATION_IMAGE:\?[^{}]+\}", raw):
                # A required reviewed build artifact, with no upstream image default.
                required_images.setdefault(raw, []).append(source)
                continue
            if "$" in reference:
                unresolved.append({"reference": raw, **source})
                continue
            validate_reference(reference)
            if reference.startswith(("aetheris-local/", "aetheris-web-dev:")):
                local_images.setdefault(reference, []).append(source)
            else:
                images.setdefault(reference, []).append(source)
    return {"schemaVersion": 2, "scope": SCOPE,
            "images": [{"reference": ref, "digestStatus": "already-pinned" if "@" in ref else "resolution-and-review-required", "sources": sources} for ref, sources in sorted(images.items())],
            "localBuildImages": [{"reference": ref, "sources": sources} for ref, sources in sorted(local_images.items())],
            "requiredReleaseImages": [{"reference": ref, "sources": sources} for ref, sources in sorted(required_images.items())],
            "unresolvedDefaults": unresolved}


def registry_reference(reference, mirror=False):
    validate_reference(reference)
    unpinned = reference.split("@", 1)[0]
    if unpinned.startswith("docker.1panel.live/library/"):
        unpinned = ("docker.1panel.live/library/" if mirror else "docker.io/library/") + unpinned.split("/library/", 1)[1]
    elif not unpinned.startswith(("docker.io/", "quay.io/")):
        unpinned = "docker.io/" + ("library/" if "/" not in unpinned else "") + unpinned
    host, repository = unpinned.split("/", 1)
    if ":" in repository.rsplit("/", 1)[-1]:
        repository, tag = repository.rsplit(":", 1)
    else:
        tag = "latest"
    if host == "docker.io" and repository not in OFFICIAL_REPOSITORIES:
        raise ValueError("Repository is not in the reviewed publisher directory")
    if host == "quay.io" and repository not in ("minio/minio", "minio/mc"):
        raise ValueError("Repository is not in the reviewed publisher directory")
    if host not in ("docker.io", "quay.io") and not (mirror and host == "docker.1panel.live"):
        raise ValueError("Unreviewed registry")
    return host, repository, tag, host + "/" + repository + ":" + tag


class SafeRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, request, fp, code, msg, headers, newurl):
        if urllib.parse.urlsplit(newurl).scheme != "https":
            raise ValueError("Registry redirect must use HTTPS")
        result = super().redirect_request(request, fp, code, msg, headers, newurl)
        if result is not None and urllib.parse.urlsplit(request.full_url).netloc != urllib.parse.urlsplit(newurl).netloc:
            result.remove_header("Authorization")
        return result


class Registry:
    def __init__(self):
        self.opener = urllib.request.build_opener(SafeRedirect())
        self.tokens = {}

    def read(self, url, headers):
        with self.opener.open(urllib.request.Request(url, headers=headers), timeout=15) as response:
            raw = response.read(MAX_METADATA + 1)
            if len(raw) > MAX_METADATA:
                raise ValueError("Registry metadata exceeds limit")
            return raw, response.headers

    def fetch(self, host, repository, kind, identifier):
        endpoint = "registry-1.docker.io" if host == "docker.io" else host
        url = "https://" + endpoint + "/v2/" + repository + "/" + kind + "/" + identifier
        headers = {"Accept": ACCEPT, "User-Agent": "Aetheris-base-image-inventory/2"}
        key = (endpoint, repository)
        if key in self.tokens:
            headers["Authorization"] = "Bearer " + self.tokens[key]
        try:
            return self.read(url, headers)
        except urllib.error.HTTPError as error:
            if error.code != 401:
                raise
            challenge = error.headers.get("WWW-Authenticate", "")
            if not challenge.startswith("Bearer "):
                raise ValueError("Unsupported registry challenge")
            parts = dict(re.findall(r'(\w+)="([^"\r\n]+)"', challenge))
            realm = urllib.parse.urlsplit(parts.get("realm", ""))
            allowed = {endpoint, "auth.docker.io"} if host == "docker.io" else {endpoint}
            if realm.scheme != "https" or realm.hostname not in allowed or realm.username or realm.password or realm.query:
                raise ValueError("Unreviewed registry authentication endpoint")
            query = urllib.parse.urlencode({"service": parts.get("service", endpoint), "scope": "repository:" + repository + ":pull"})
            token_raw, _ = self.read(parts["realm"] + "?" + query, {"User-Agent": headers["User-Agent"]})
            data = strict_json(token_raw)
            token = data.get("token") or data.get("access_token")
            if not isinstance(token, str) or not token or len(token) > 32768:
                raise ValueError("Invalid anonymous registry token")
            self.tokens[key] = token
            headers["Authorization"] = "Bearer " + token
            return self.read(url, headers)


def manifest(raw, expected=None):
    if len(raw) > MAX_METADATA or expected is not None and sha(raw) != expected:
        raise ValueError("Manifest content digest mismatch")
    data = strict_json(raw)
    if data.get("schemaVersion") != 2 or data.get("mediaType") not in INDEX_TYPES | MANIFEST_TYPES:
        raise ValueError("Unsupported manifest media type")
    media = data["mediaType"]
    descriptors = data.get("manifests") if media in INDEX_TYPES else [data.get("config"), *data.get("layers", [])]
    if not isinstance(descriptors, list) or not descriptors:
        raise ValueError("Missing manifest descriptors")
    for item in descriptors:
        if not isinstance(item, dict) or not DIGEST.fullmatch(item.get("digest", "")) or type(item.get("size")) is not int or item["size"] < 0:
            raise ValueError("Invalid OCI descriptor")
    if media in INDEX_TYPES:
        if any(not isinstance(item.get("platform"), dict) or
               any(not isinstance(item["platform"].get(key), str) or not item["platform"][key] for key in ("os", "architecture"))
               or item.get("mediaType") not in INDEX_TYPES | MANIFEST_TYPES for item in descriptors):
            raise ValueError("Manifest index lacks platform evidence")
    return data


def resolution(reference, registry=None, mirror=False):
    registry = registry or Registry()
    host, repository, tag, canonical = registry_reference(reference, mirror)
    raw, headers = registry.fetch(host, repository, "manifests", tag)
    digest = sha(raw)
    if headers.get("Docker-Content-Digest") not in (None, digest):
        raise ValueError("Registry header digest mismatch")
    data = manifest(raw)
    platforms, config = [], None
    if data["mediaType"] in INDEX_TYPES:
        platforms = [{"digest": x["digest"], "mediaType": x["mediaType"], "platform": x["platform"]} for x in data["manifests"]]
    else:
        config, _ = registry.fetch(host, repository, "blobs", data["config"]["digest"])
        if sha(config) != data["config"]["digest"]:
            raise ValueError("Image configuration digest mismatch")
        image = strict_json(config)
        if not isinstance(image.get("os"), str) or not isinstance(image.get("architecture"), str):
            raise ValueError("Image configuration lacks platform")
        platform = {key: image[key] for key in ("os", "architecture", "variant") if key in image}
        platforms = [{"digest": digest, "mediaType": data["mediaType"], "platform": platform}]
    return {"reference": canonical, "resolvedAtUtc": utc(), "registry": host, "repository": repository, "tag": tag,
            "digest": digest, "mediaType": data["mediaType"], "rawManifestBase64": base64.b64encode(raw).decode("ascii"),
            "configBase64": base64.b64encode(config).decode("ascii") if config else None, "platforms": platforms}


def verify_resolution(item):
    timestamp = datetime.datetime.fromisoformat(item["resolvedAtUtc"])
    if timestamp.utcoffset() != datetime.timedelta(0) or timestamp > datetime.datetime.now(datetime.timezone.utc) + datetime.timedelta(minutes=5):
        raise ValueError("Invalid resolution UTC timestamp")
    host, repository, tag, canonical = registry_reference(item["reference"])
    if (host, repository, tag, canonical) != (item["registry"], item["repository"], item["tag"], item["reference"]):
        raise ValueError("Resolution repository binding mismatch")
    if not DIGEST.fullmatch(item["digest"]):
        raise ValueError("Invalid locked digest")
    raw = base64.b64decode(item["rawManifestBase64"], validate=True)
    data = manifest(raw, item["digest"])
    if item["mediaType"] != data["mediaType"]:
        raise ValueError("Locked manifest type mismatch")
    if data["mediaType"] in INDEX_TYPES:
        expected = [{"digest": x["digest"], "mediaType": x["mediaType"], "platform": x["platform"]} for x in data["manifests"]]
        if item.get("configBase64") is not None:
            raise ValueError("Index must not masquerade as a platform manifest")
    else:
        config = base64.b64decode(item["configBase64"], validate=True)
        if sha(config) != data["config"]["digest"]:
            raise ValueError("Locked image configuration mismatch")
        config = strict_json(config)
        expected = [{"digest": item["digest"], "mediaType": data["mediaType"], "platform": {key: config[key] for key in ("os", "architecture", "variant") if key in config}}]
    if expected != item["platforms"]:
        raise ValueError("Locked platform evidence mismatch")
    return data


def validate_lock(root, lock, require_all=True, online=False, resolver=resolution):
    if lock.get("schemaVersion") != 2:
        raise ValueError("Expected base-image lock schemaVersion2")
    current = inventory(root)
    rows = lock.get("images", [])
    if len({x["reference"] for x in rows}) != len(rows):
        raise ValueError("Duplicate locked image")
    expected = [{"reference": x["reference"], "sources": x["sources"]} for x in rows]
    actual = [{"reference": x["reference"], "sources": x["sources"]} for x in current["images"]]
    if (sorted(expected, key=lambda x:x["reference"]) != actual or current["localBuildImages"] != lock.get("localBuildImages")
            or current["requiredReleaseImages"] != lock.get("requiredReleaseImages") or current["unresolvedDefaults"] != lock.get("unresolvedDefaults")):
        raise ValueError("Image defaults or reference coverage differ from lock")
    if current["unresolvedDefaults"]:
        raise ValueError("Unresolved image default")
    blocked = []
    for row in rows:
        proof = row.get("officialResolution")
        if proof is not None:
            verify_resolution(proof)
            if registry_reference(row["originalReference"])[3] != proof["reference"]:
                raise ValueError("Original same-tag reference binding mismatch")
        if row.get("state") == "pinned":
            if proof is None or row["reference"] != proof["reference"] + "@" + proof["digest"]:
                raise ValueError("Source pin does not match official evidence")
            if row["originalReference"].startswith("docker.1panel.live/"):
                # Explicit E08 source migration, never a claim that an inaccessible
                # mirror served the same config/layers as today's official tag.
                if (row.get("originalRef") != row["originalReference"] or row.get("equivalence") != "unverified"
                        or row.get("sameTag") is not True or row.get("fullBuildPending") is not True
                        or row.get("mirrorComparison", {}).get("httpStatus") != 403
                        or row.get("replacementReason") != "mirror HTTP403; explicitly authorized official supply-chain default migration"):
                    raise ValueError("Mirror source migration lacks explicit risk metadata")
        elif row.get("state") == "blocked" and row["reference"] == row["originalReference"]:
            blocked.append(row["reference"])
        else:
            raise ValueError("Invalid image lock state")
        if online and proof is not None and resolver(proof["reference"])["digest"] != proof["digest"]:
            raise ValueError("Official tag drift detected: " + proof["reference"])
    if blocked and require_all:
        raise ValueError("Unresolved base-image locks: " + str(len(blocked)))
    return {"references": len(rows), "sources": sum(len(x["sources"]) for x in rows), "blocked": len(blocked)}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, default=Path(__file__).resolve().parents[1])
    parser.add_argument("--output", type=Path)
    parser.add_argument("--check", action="store_true")
    parser.add_argument("--online", action="store_true")
    parser.add_argument("--allow-unresolved", action="store_true")
    parser.add_argument("--resolve", action="store_true")
    args = parser.parse_args()
    output = args.output or Path(".github/workflows/base-image-inventory.json")
    output = output if output.is_absolute() else args.root / output
    try:
        if args.check:
            summary = validate_lock(args.root, strict_json(output.read_bytes()), not args.allow_unresolved, args.online)
            print(json.dumps({"status": "diagnostic-unresolved" if summary["blocked"] else "verified", **summary}))
        elif args.resolve:
            if args.output is None:
                parser.error("--resolve requires an explicit --output candidate path")
            scan = inventory(args.root)
            registry = Registry()
            for row in scan["images"]:
                row["originalReference"] = row["reference"]
                row["state"] = "blocked"
                try:
                    row["officialResolution"] = resolution(row["reference"], registry)
                    row["reason"] = "Candidate only: source changes and mirror equivalence require explicit review"
                except Exception as error:
                    row["officialResolution"] = None
                    row["reason"] = type(error).__name__ + (":" + str(error.code) if isinstance(error, urllib.error.HTTPError) else "")
            output.write_text(json.dumps(scan, indent=2) + "\n", encoding="utf-8")
            print("Candidate metadata written; no source edits, layers or runtime activity")
        else:
            print(json.dumps(inventory(args.root), indent=2))
    except (ValueError, KeyError, TypeError, OSError) as error:
        raise SystemExit("Base-image check failed: " + str(error))


if __name__ == "__main__":
    main()
