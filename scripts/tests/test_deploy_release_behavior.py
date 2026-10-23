"""Actual Bash deployment entry points with docker/cosign/curl/git functions.

No registry, Docker daemon, SSH endpoint or database is contacted by these tests.
"""
import base64
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]
BASH = str(Path("C:/Program Files/Git/bin/bash.exe")) if os.name == "nt" else shutil.which("bash")


def available():
    if not BASH or not Path(BASH).exists(): return False
    return subprocess.run([BASH, "--noprofile", "--norc", "-c", "true"], capture_output=True).returncode == 0


@unittest.skipUnless(available(), "Bash is unavailable or MSYS initialization is sandbox-blocked")
class DeployReleaseBehaviorTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix=".release-test-deploy-", dir=ROOT / "scripts/tests")
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        (self.root / "scripts").mkdir()
        for name in ("deploy-remote.sh", "verify-image-signatures.sh", "deploy-healthcheck.sh", "release-manifest.py", "verify-ci-run.py"):
            shutil.copyfile(ROOT / "scripts" / name, self.root / "scripts" / name)
        (self.root / "docker-compose.yml").write_text("services: {}\n", encoding="utf-8")
        self.commit = "a" * 40
        self.services = ("system", "metadata", "serving", "quality", "ingestion", "governance", "workflow", "ai-agent", "auth", "gateway", "web")
        required = sorted(("deployment-contracts", "documentation-contracts", "security", "spark-script-contracts", "backend", "frontend", "smoke"))
        self.body = {"schemaVersion": 2, "repository": "mazhuce/Aetheris", "commit": self.commit,
                     "ci": {"runId": 10, "attempt": 1, "requiredJobs": required},
                     "build": {"runId": 11, "attempt": 1, "ref": "refs/heads/main"},
                     "images": {name: f"ghcr.io/mazhuce/aetheris-{name}@sha256:" + "b" * 64 for name in self.services},
                     "databaseSources": {f'services/{name}/src/main/resources/db/migration/V1__fixture.sql':'1'*64 for name in self.services if name not in {'web','gateway'}}}
        self.manifest = self.root / "manifest.json"
        self.bundle = self.root / "bundle.json"
        self.manifest.write_text(json.dumps(self.body), encoding="utf-8")
        self.bundle.write_text("{}", encoding="utf-8")
        self.calls = self.root / "calls.log"

    def invoke(self, bad_signature=False, fail_candidate=False, commit=None, bad_database=False):
        driver = self.root / "driver.sh"
        driver.write_text(r'''
set -euo pipefail
python3() {
  if [[ "$1" == scripts/database/deploy_gate.py ]]; then
    cat >/dev/null
    printf 'database-admission | compose=%s\n' "${COMPOSE_FILE:-}" >> "$AETHERIS_TEST_CALLS"
    [[ "$AETHERIS_TEST_BAD_DATABASE" != 1 ]]
  else "$AETHERIS_TEST_PYTHON" "$@"; fi
}
git() { printf '%s\n' "$AETHERIS_TEST_COMMIT"; }
docker() {
  printf 'docker %s | compose=%s\n' "$*" "${COMPOSE_FILE:-base}" >> "$AETHERIS_TEST_CALLS"
  if [[ "$*" == *'config --services'* ]]; then printf '%s\n' gateway; return 0; fi
  if [[ "$*" == *'ps -q'* ]]; then printf '%s\n' container123; return 0; fi
  if [[ "$*" == *'.State.Status'* ]]; then printf '%s\n' running; return 0; fi
  if [[ "$*" == *'.State.Health'* ]]; then printf '%s\n' healthy; return 0; fi
  if [[ "$*" == *'.Config.Image'* ]]; then printf '%s\n' ghcr.io/mazhuce/aetheris-gateway:sha-old1234; return 0; fi
  if [[ "$*" == *'.RepoDigests'* ]]; then
    value="${*: -1}"
    if [[ "$value" == *@sha256:* ]]; then printf '%s\n' "$value"; else printf '%s@sha256:%064d\n' "${value%:*}" 1; fi
    return 0
  fi
  return 0
}
cosign() {
  printf 'cosign %s\n' "$*" >> "$AETHERIS_TEST_CALLS"
  [[ "$AETHERIS_TEST_BAD_SIGNATURE" != 1 ]]
}
curl() {
  if [[ "$AETHERIS_TEST_FAIL_CANDIDATE" == 1 && "${COMPOSE_FILE:-}" == *candidate.json* ]]; then return 1; fi
  return 0
}
export -f python3 git docker cosign curl
bash scripts/deploy-remote.sh
''', encoding="utf-8")
        env = dict(os.environ, BASH_ENV="", AETHERIS_TEST_PYTHON=Path(sys.executable).as_posix(),
                   AETHERIS_TEST_CALLS=self.calls.as_posix(), AETHERIS_TEST_COMMIT=commit or self.commit,
                   AETHERIS_TEST_BAD_SIGNATURE=str(int(bad_signature)), AETHERIS_TEST_FAIL_CANDIDATE=str(int(fail_candidate)),
                   AETHERIS_TEST_BAD_DATABASE=str(int(bad_database)),
                   AETHERIS_DEPLOY_PATH=self.root.as_posix(), AETHERIS_RELEASE_MANIFEST=self.manifest.as_posix(),
                   AETHERIS_RELEASE_MANIFEST_BUNDLE=self.bundle.as_posix(), AETHERIS_COSIGN_POLICY="required",
                   AETHERIS_DEPLOY_HEALTH_TIMEOUT_SECONDS="1", AETHERIS_DEPLOY_HEALTH_POLL_SECONDS="1")
        result = subprocess.run([BASH, "--noprofile", "--norc", "driver.sh"], cwd=self.root, env=env,
                                capture_output=True, text=True, encoding="utf-8", errors="replace", timeout=60)
        calls = self.calls.read_text(encoding="utf-8") if self.calls.exists() else ""
        return result, calls

    def test_success_uses_digest_override_and_atomically_records_public_state(self):
        result, calls = self.invoke()
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        state = json.loads((self.root / ".aetheris-last-good-release.json").read_text(encoding="utf-8"))
        self.assertEqual(base64.b64decode(state["manifestBase64"]), self.manifest.read_bytes())
        self.assertIn("candidate.json", calls)
        self.assertEqual(calls.count("cosign verify --"), 11)
        self.assertIn("--pull never", calls)
        self.assertIn('docker-compose.database-roles.yml',calls)
        self.assertLess(calls.index('database-admission'),calls.index('docker compose pull'))
        self.assertNotIn('image prune',calls)

    def test_database_admission_failure_blocks_before_pull_or_up(self):
        result,calls=self.invoke(bad_database=True)
        self.assertNotEqual(result.returncode,0)
        self.assertIn('database-admission',calls)
        self.assertNotIn('docker compose pull',calls)
        self.assertNotIn('docker compose up',calls)
        self.assertFalse((self.root/'.aetheris-last-good-release.json').exists())

    def test_bad_signature_and_wrong_commit_stop_before_compose_pull(self):
        for bad_signature, commit in ((True, self.commit), (False, "c" * 40)):
            with self.subTest(signature=bad_signature):
                self.calls.unlink(missing_ok=True)
                result, calls = self.invoke(bad_signature=bad_signature, commit=commit)
                self.assertNotEqual(result.returncode, 0)
                self.assertNotIn("docker compose pull", calls)
                self.assertFalse((self.root / ".aetheris-last-good-release.json").exists())

    def test_health_failure_uses_previous_manifest_and_does_not_publish_candidate(self):
        previous = dict(self.body, commit="d" * 40)
        previous["images"] = {name: f"ghcr.io/mazhuce/aetheris-{name}@sha256:" + "e" * 64 for name in self.services}
        old_bytes = json.dumps(previous).encode()
        state = {"schemaVersion": 1, "manifestBase64": base64.b64encode(old_bytes).decode(), "bundleBase64": base64.b64encode(b"{}").decode()}
        state_path = self.root / ".aetheris-last-good-release.json"
        state_path.write_text(json.dumps(state), encoding="utf-8")
        result, calls = self.invoke(fail_candidate=True)
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("Rollback completed successfully", result.stderr)
        self.assertIn("rollback.json", calls)
        self.assertIn("e" * 64, calls)
        self.assertEqual(json.loads(state_path.read_text()), state)

    def test_legacy_tag_cannot_restore_shared_database_credentials(self):
        (self.root / ".aetheris-last-good-image-tag").write_text("sha-old1234\n", encoding="utf-8")
        result, calls = self.invoke(fail_candidate=True)
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("No verified last-known-good release", result.stderr)
        self.assertNotIn(":sha-old1234", calls)
        self.assertFalse((self.root / ".aetheris-last-good-release.json").exists())
