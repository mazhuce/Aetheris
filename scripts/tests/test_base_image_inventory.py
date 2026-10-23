import importlib.util
import copy
import json
from pathlib import Path
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location("base_image_inventory", ROOT / "scripts/base-image-inventory.py")
images = importlib.util.module_from_spec(spec)
spec.loader.exec_module(images)
OUTPUT = ROOT / "output/audit-base-image-package"


class BaseImageInventoryTests(unittest.TestCase):
    def fixture(self, text, tracked=False):
        OUTPUT.mkdir(parents=True, exist_ok=True)
        directory = tempfile.TemporaryDirectory(prefix="scanner-", dir=OUTPUT)
        self.addCleanup(directory.cleanup)
        root = Path(directory.name)
        subprocess.run(["git", "init", "-q", str(root)], check=True, capture_output=True)
        dockerfile = root / "services/example/Dockerfile"
        dockerfile.parent.mkdir(parents=True)
        dockerfile.write_text(text, encoding="utf-8")
        if tracked:
            subprocess.run(["git", "-C", str(root), "add", "services/example/Dockerfile"], check=True, capture_output=True)
        return root

    def test_untracked_dockerfile_and_arg_default_are_not_omitted(self):
        root = self.fixture("ARG BASE=postgres:16-alpine\nFROM ${BASE}\n")
        found = images.inventory(root)
        self.assertEqual([item["reference"] for item in found["images"]], ["postgres:16-alpine"])

    def test_stage_name_cannot_hide_the_external_image_in_its_own_from(self):
        root = self.fixture("FROM alpine AS alpine\nFROM alpine AS final\n", tracked=True)
        self.assertEqual([item["reference"] for item in images.inventory(root)["images"]], ["alpine"])

    def test_invalid_digest_is_rejected_instead_of_being_labelled_pinned(self):
        root = self.fixture("FROM postgres:16-alpine@sha256:abc\n", tracked=True)
        with self.assertRaises(ValueError):
            images.inventory(root)

    def test_valid_stage_reuse_and_scratch_do_not_become_registry_references(self):
        root = self.fixture("FROM alpine:3.20 AS build\nFROM build AS result\nFROM scratch\n", tracked=True)
        self.assertEqual([item["reference"] for item in images.inventory(root)["images"]], ["alpine:3.20"])

    def proof(self, reference="postgres:16-alpine", marker="fixture"):
        # Synthetic manifest bytes for deterministic tool tests, never release digests.
        descriptor = {"mediaType": "application/vnd.oci.image.manifest.v1+json", "digest": images.sha(marker.encode()),
                      "size": len(marker), "platform": {"os": "linux", "architecture": "amd64"}}
        raw = json.dumps({"schemaVersion": 2, "mediaType": "application/vnd.oci.image.index.v1+json", "manifests": [descriptor]}).encode()
        class FakeRegistry:
            def fetch(self, *args):
                return raw, {"Docker-Content-Digest": images.sha(raw)}
        return images.resolution(reference, FakeRegistry())

    def locked_fixture(self):
        proof = self.proof()
        reference = proof["reference"] + "@" + proof["digest"]
        root = self.fixture("FROM " + reference + "\n")
        lock = images.inventory(root)
        lock["images"][0].update(originalReference="postgres:16-alpine", state="pinned", officialResolution=proof)
        return root, lock

    def test_valid_lock_offline_and_online_drift_use_manifest_content_not_only_sha_spelling(self):
        root, lock = self.locked_fixture()
        self.assertEqual(images.validate_lock(root, lock), {"references": 1, "sources": 1, "blocked": 0})
        self.assertEqual(images.validate_lock(root, lock, online=True, resolver=lambda ref: self.proof()), {"references": 1, "sources": 1, "blocked": 0})
        with self.assertRaisesRegex(ValueError, "tag drift"):
            images.validate_lock(root, lock, online=True, resolver=lambda ref: self.proof(marker="changed real registry content"))

    def test_changed_tag_missing_or_new_reference_and_wrong_pin_fail(self):
        for mode in ("tag", "missing", "new", "pin"):
            with self.subTest(mode=mode):
                root, lock = self.locked_fixture()
                source = root / "services/example/Dockerfile"
                if mode == "tag":
                    source.write_text(source.read_text().replace(":16-alpine", ":17-alpine"))
                elif mode == "missing":
                    source.unlink()
                elif mode == "new":
                    added = root / "services/other/Dockerfile"
                    added.parent.mkdir()
                    added.write_text("FROM redis:7.4-alpine\n")
                else:
                    wrong = "sha256:" + "f" * 64
                    source.write_text(source.read_text().replace(lock["images"][0]["officialResolution"]["digest"], wrong))
                    lock["images"][0]["reference"] = lock["images"][0]["reference"].split("@")[0] + "@" + wrong
                with self.assertRaises(ValueError):
                    images.validate_lock(root, lock)

    def test_tampered_manifest_platform_or_repository_evidence_is_rejected(self):
        for mode in ("bytes", "platform", "repository", "type", "schema", "time"):
            with self.subTest(mode=mode):
                root, lock = self.locked_fixture()
                proof = lock["images"][0]["officialResolution"]
                if mode == "bytes": proof["rawManifestBase64"] += "AA=="
                elif mode == "platform": proof["platforms"][0]["platform"]["architecture"] = "arm64"
                elif mode == "repository": proof["reference"] = "evil.example/library/postgres:16-alpine"
                elif mode == "type": proof["mediaType"] = "application/vnd.oci.image.manifest.v1+json"
                elif mode == "schema": lock["schemaVersion"] = 1
                else: proof["resolvedAtUtc"] = "not-a-timestamp"
                with self.assertRaises(ValueError):
                    images.validate_lock(root, lock)

    def test_unresolved_entries_require_explicit_diagnostic_mode_and_cannot_be_called_complete(self):
        root = self.fixture("FROM quay.io/minio/minio:RELEASE.fixture\n")
        lock = images.inventory(root)
        lock["images"][0].update(originalReference=lock["images"][0]["reference"], state="blocked", officialResolution=None)
        with self.assertRaisesRegex(ValueError, "Unresolved"):
            images.validate_lock(root, lock)
        self.assertEqual(images.validate_lock(root, lock, require_all=False)["blocked"], 1)

    def test_source_migration_requires_explicit_unverified_same_tag_risk_record(self):
        proof = self.proof("redis:7.4-alpine")
        root = self.fixture("FROM " + proof["reference"] + "@" + proof["digest"] + "\n")
        lock = images.inventory(root)
        row = lock["images"][0]
        row.update(originalReference="docker.1panel.live/library/redis:7.4-alpine", state="pinned", officialResolution=proof)
        with self.assertRaisesRegex(ValueError, "risk metadata"):
            images.validate_lock(root, lock)
        row.update(originalRef=row["originalReference"], equivalence="unverified", sameTag=True, fullBuildPending=True,
                   mirrorComparison={"httpStatus": 403}, replacementReason="mirror HTTP403; explicitly authorized official supply-chain default migration")
        self.assertEqual(images.validate_lock(root, lock)["blocked"], 0)
        row["equivalence"] = "verified"
        with self.assertRaises(ValueError):
            images.validate_lock(root, lock)

    def test_arg_scope_compose_defaults_and_application_prefix_cannot_hide_unknown_image(self):
        root = self.fixture("ARG BASE=postgres:16-alpine\nFROM ${BASE} AS build\nARG BASE=mysql:8.0.40\nFROM ${BASE}\n")
        found = images.inventory(root)
        self.assertEqual(len(found["images"]), 1)
        self.assertEqual(len(found["images"][0]["sources"]), 2)
        compose = root / "docker-compose.yml"
        compose.write_text('services:\n  db:\n    image: "${DB_IMAGE:-postgres:16-alpine}" # default\n')
        self.assertEqual(len(images.inventory(root)["images"][0]["sources"]), 3)
        compose.write_text('services:\n  hostile:\n    image: evil/AETHERIS_IMAGE_PREFIX-hidden:latest\n')
        self.assertTrue(any(x["reference"] == "evil/AETHERIS_IMAGE_PREFIX-hidden:latest" for x in images.inventory(root)["images"]))

    def test_compose_build_arg_override_is_a_separate_actual_default_and_drift_is_detected(self):
        proof = self.proof()
        pinned = proof["reference"] + "@" + proof["digest"]
        root = self.fixture("ARG BASE=" + pinned + "\nFROM ${BASE}\n")
        compose = root / "docker-compose.yml"
        compose.write_text("services:\n  example:\n    build:\n      context: ./services/example\n      args:\n        BASE: ${BUILD_BASE:-" + pinned + "}\n")
        lock = images.inventory(root)
        self.assertEqual(len(lock["images"][0]["sources"]), 2)
        self.assertEqual(lock["images"][0]["sources"][0]["kind"], "compose-build-arg")
        lock["images"][0].update(originalReference="postgres:16-alpine", state="pinned", officialResolution=proof)
        images.validate_lock(root, lock)
        compose.write_text(compose.read_text().replace(pinned, "postgres:17-alpine"))
        with self.assertRaises(ValueError):
            images.validate_lock(root, lock)

    def test_scanning_does_not_traverse_private_output_or_node_modules(self):
        root = self.fixture("FROM postgres:16-alpine\n")
        for directory in ("configs/database/private/fixture", "output/fixture", "apps/web/node_modules/example"):
            path = root / directory / "Dockerfile"
            path.parent.mkdir(parents=True)
            path.write_text("FROM untrusted/private:latest\n")
        self.assertEqual([x["reference"] for x in images.inventory(root)["images"]], ["postgres:16-alpine"])


if __name__ == "__main__":
    unittest.main()
