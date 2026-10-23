import importlib.util
from pathlib import Path
import unittest
from unittest.mock import patch
import io
import json
import tempfile
import zipfile
import subprocess
import os

ROOT = Path(__file__).resolve().parents[2]


class ReleaseManifestTests(unittest.TestCase):
    def setUp(self):
        spec = importlib.util.spec_from_file_location("release_manifest", ROOT / "scripts/release-manifest.py")
        self.module = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(self.module)
        self.commit = "a" * 40

    def manifest(self):
        return {"schemaVersion": 1, "repository": "mazhuce/Aetheris", "commit": self.commit,
                "ci": {"runId": 12, "attempt": 1, "requiredJobs": sorted(self.module.ci.REQUIRED_JOBS)},
                "build": {"runId": 14, "attempt": 1, "ref": "refs/heads/main"},
                "images": {name: f"ghcr.io/mazhuce/aetheris-{name}@sha256:" + "b" * 64 for name in self.module.SERVICES}}

    def test_complete_digest_manifest_produces_real_compose_override(self):
        body = self.manifest()
        self.module.validate(body, self.commit)
        override = self.module.compose_override(body)
        expected={name if name in {"ai-agent","gateway","web"} else name+"-service" for name in self.module.SERVICES}
        self.assertEqual(set(override["services"]), expected)
        self.assertTrue(all("@sha256:" in item["image"] for item in override["services"].values()))

    def test_v2_binds_all_nine_migration_sources_and_rejects_incomplete_or_unsafe_paths(self):
        body=dict(self.manifest(),schemaVersion=2,databaseSources=self.module.database_sources())
        self.module.validate(body)
        for value in ({}, {'../escape.sql':'1'*64}, {'services/auth/src/main/resources/db/migration/V1__test.sql':'1'*64}):
            with self.subTest(sources=value),self.assertRaises(ValueError):
                self.module.validate(dict(body,databaseSources=value))

    def test_override_replaces_actual_base_compose_services_without_creating_aliases(self):
        # Invoke the real Compose parser against the real base. No daemon, image
        # pull, existing .env or real credential is needed for configuration merge.
        with tempfile.TemporaryDirectory(prefix='.release-merge-',dir=ROOT/'scripts/tests') as directory:
            path=Path(directory)
            override=path/'images.json';override.write_text(json.dumps(self.module.compose_override(self.manifest())),encoding='utf-8')
            envfile=path/'fixture.env'
            envfile.write_text('\n'.join(name+'=fixture-value-with-at-least-thirty-two-bytes' for name in
                ('DB_PASSWORD','JWT_SECRET','AETHERIS_INTERNAL_TOKEN','AETHERIS_DATASOURCE_MASTER_KEY','MINIO_ROOT_PASSWORD','POSTGRES_PASSWORD','REDIS_PASSWORD'))+'\n',encoding='utf-8')
            env={k:v for k,v in os.environ.items() if not k.startswith(('DB_','JWT_','AETHERIS_','MINIO_','POSTGRES_','COMPOSE_','REDIS_'))}
            completed=subprocess.run(['docker','compose','--env-file',str(envfile),'-f',str(ROOT/'docker-compose.yml'),'-f',str(override),'config','--format','json'],
                capture_output=True,text=True,encoding='utf-8',errors='replace',env=env,timeout=30)
            self.assertEqual(completed.returncode,0,'Compose fixture merge failed')
            resolved=json.loads(completed.stdout)['services']
            for name,image in self.manifest()['images'].items():
                actual=name if name in {'ai-agent','gateway','web'} else name+'-service'
                self.assertEqual(resolved[actual]['image'],image)
                if name!=actual:self.assertNotIn(name,resolved)

    def test_tag_only_missing_service_wrong_commit_and_untrusted_ref_fail(self):
        for mutation in ("tag", "missing", "commit", "ref", "registry", "attempt"):
            body = self.manifest()
            if mutation == "tag": body["images"]["auth"] = "ghcr.io/mazhuce/aetheris-auth:latest"
            elif mutation == "missing": del body["images"]["auth"]
            elif mutation == "commit": body["commit"] = "c" * 40
            elif mutation == "ref": body["build"]["ref"] = "refs/heads/untrusted"
            elif mutation == "registry": body["images"]["auth"] = "evil.invalid/auth@sha256:" + "b" * 64
            else: body["ci"]["attempt"] = 0
            with self.subTest(mutation=mutation), self.assertRaises(ValueError):
                self.module.validate(body, self.commit)

    def test_shell_inputs_are_validated_before_transfer(self):
        body = self.manifest()
        self.module.validate_deploy_inputs(body, "", "required", "")
        for tag, policy, identity in (("x'; touch /tmp/pwn; '", "required", ""), ("", "required;id", ""), ("", "required", "x'$(id)")):
            with self.assertRaises(ValueError):
                self.module.validate_deploy_inputs(body, tag, policy, identity)

    def artifact(self, body=None, extra_file=False):
        buffer = io.BytesIO()
        with zipfile.ZipFile(buffer, "w") as archive:
            archive.writestr("release-manifest.json", json.dumps(body or self.manifest()))
            archive.writestr("release-manifest.bundle.json", "{}")
            if extra_file: archive.writestr("../escape.txt", "untrusted")
        return buffer.getvalue()

    def github(self, path):
        if "/artifacts?" in path:
            return {"artifacts": [{"id": 20, "name": "release-manifest-" + self.commit + "-1", "expired": False}]}
        return {"id": 14, "head_sha": self.commit, "event": "push", "status": "completed", "conclusion": "success", "run_attempt": 1,
                "head_repository": {"full_name": "mazhuce/Aetheris"}, "path": ".github/workflows/build.yml"}

    def test_download_rechecks_build_and_ci_before_writing_exact_files(self):
        with tempfile.TemporaryDirectory() as directory, patch.object(self.module.ci, "fetch_json", side_effect=self.github), \
                patch.object(self.module.ci, "request_bytes", return_value=self.artifact()), patch.object(self.module.ci, "verify_binding") as verify:
            body, _ = self.module.fetch_release("mazhuce/Aetheris", 14, directory)
            self.assertEqual(body["commit"], self.commit)
            verify.assert_called_once_with("mazhuce/Aetheris", self.commit, 12, 1)
            self.assertEqual({p.name for p in Path(directory).iterdir()}, {"release-manifest.json", "release-manifest.bundle.json"})

    def test_foreign_build_attempt_and_archive_traversal_are_rejected(self):
        wrong_attempt = self.manifest()
        wrong_attempt["build"]["attempt"] = 2
        for data in (self.artifact(wrong_attempt), self.artifact(extra_file=True)):
            with tempfile.TemporaryDirectory() as directory, patch.object(self.module.ci, "fetch_json", side_effect=self.github), \
                    patch.object(self.module.ci, "request_bytes", return_value=data), patch.object(self.module.ci, "verify_binding"):
                with self.assertRaises(ValueError): self.module.fetch_release("mazhuce/Aetheris", 14, directory)
                self.assertFalse(list(Path(directory).iterdir()))

    def test_expired_artifact_is_not_downloaded(self):
        def github(path):
            body = self.github(path)
            if "/artifacts?" in path: body["artifacts"][0]["expired"] = True
            return body
        with tempfile.TemporaryDirectory() as directory, patch.object(self.module.ci, "fetch_json", side_effect=github), \
                patch.object(self.module.ci, "request_bytes") as download:
            with self.assertRaises(ValueError): self.module.fetch_release("mazhuce/Aetheris", 14, directory)
            download.assert_not_called()

    def test_ci_rerun_failure_cannot_be_hidden_by_signed_old_manifest(self):
        with tempfile.TemporaryDirectory() as directory, patch.object(self.module.ci, "fetch_json", side_effect=self.github), \
                patch.object(self.module.ci, "request_bytes", return_value=self.artifact()), patch.object(self.module.ci, "verify_binding", side_effect=ValueError("rerun failed")):
            with self.assertRaises(ValueError): self.module.fetch_release("mazhuce/Aetheris", 14, directory)
            self.assertFalse(list(Path(directory).iterdir()))
