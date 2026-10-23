import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]
PWSH = shutil.which("pwsh")


@unittest.skipUnless(PWSH, "PowerShell is required for script behavior tests")
class DatasourceScriptBehaviorTests(unittest.TestCase):
    def invoke(self, failure="", start=False, keep=False):
        with tempfile.TemporaryDirectory(prefix=".release-test-", dir=ROOT / "scripts/tests") as directory:
            root = Path(directory)
            report = root / "report.json"
            (root / "env").write_text("R1_SOURCE_POSTGRES_PASSWORD=fixture-only\nR1_SOURCE_POSTGRES_USER=fixture\nR1_SOURCE_POSTGRES_DB=fixture\n", encoding="utf-8")
            (root / "compose.yml").write_text("services: {}", encoding="utf-8")
            driver = root / "driver.ps1"
            driver.write_text(r'''
$global:ProbeId = 0
function global:docker {
    $items = @($args | ForEach-Object { [string]$_ })
    $global:LASTEXITCODE = 0
    if ($items[0] -eq 'inspect') { Write-Output 'MARIADB_PASSWORD=fixture-only'; return }
    if ($items[0] -eq 'ps') { Write-Output 'mariadb Up 1 minute (healthy)'; Write-Output 'clickhouse Up 1 minute (healthy)'; Write-Output 'cockroachdb Up 1 minute (healthy)'; return }
    if ($items -contains 'psql') {
        if (($items -join ' ') -match 'DELETE FROM') {
            if ($env:DS_TEST_FAIL -eq 'cleanup') { $global:LASTEXITCODE = 17 }; return
        }
        $global:ProbeId++; Write-Output $global:ProbeId; return
    }
    if ($items -contains 'sh') {
        if (($items -join ' ') -match '/execute') {
            if ($env:DS_TEST_FAIL -eq 'setup') { Write-Output '{"error":"application-error"}' } else { Write-Output '{"rowCount":0}' }; return
        }
        if ($global:ProbeId -eq 2 -and $env:DS_TEST_FAIL -eq 'query') { Write-Output '{"error":"application-error","rowCount":1,"rows":[[1]]}' }
        elseif ($global:ProbeId -eq 3 -and $env:DS_TEST_FAIL -eq 'value') { Write-Output '{"rowCount":1,"rows":[[2]]}' }
        else { Write-Output '{"rowCount":1,"rows":[[1]]}' }
        return
    }
    if ($items -contains 'up' -and $env:DS_TEST_FAIL -eq 'startup') { $global:LASTEXITCODE = 17 }
    if ($items -contains 'down' -and $env:DS_TEST_FAIL -eq 'stack-cleanup') { $global:LASTEXITCODE = 17 }
}
$params = @{ ComposeFilePath = $env:DS_TEST_COMPOSE; R1EnvFile = $env:DS_TEST_ENV; ReportPath = $env:DS_TEST_REPORT }
if ($env:DS_TEST_START -eq '1') { $params.StartSmokeStack = $true }
if ($env:DS_TEST_KEEP -eq '1') { $params.KeepStack = $true }
& $env:DS_TEST_SCRIPT @params
exit $LASTEXITCODE
''', encoding="utf-8")
            env = dict(os.environ, DS_TEST_FAIL=failure, DS_TEST_COMPOSE=str(root / "compose.yml"),
                       DS_TEST_ENV=str(root / "env"), DS_TEST_REPORT=str(report), DS_TEST_START=str(int(start)),
                       DS_TEST_KEEP=str(int(keep)), DS_TEST_SCRIPT=str(ROOT / "scripts/datasource-runtime-acceptance.ps1"))
            result = subprocess.run([PWSH, "-NoProfile", "-File", str(driver)], env=env,
                                    capture_output=True, text=True, encoding="utf-8", errors="replace")
            self.assertTrue(report.exists(), result.stdout + result.stderr)
            body = json.loads(report.read_text(encoding="utf-8-sig"))
            self.assertNotIn("fixture-only", json.dumps(body))
            return result, body

    def test_all_nine_real_script_results_pass_with_successful_cleanup(self):
        result, body = self.invoke(start=True)
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertEqual(body["status"], "passed")
        self.assertEqual(len(body["results"]), 9)
        self.assertTrue(body["cleanup"]["datasourceRowsRemoved"])
        self.assertTrue(body["cleanup"]["smokeStackStopped"])

    def test_http_200_error_wrong_value_and_setup_error_fail(self):
        for failure in ("query", "value", "setup"):
            with self.subTest(failure=failure):
                result, body = self.invoke(failure)
                self.assertNotEqual(result.returncode, 0)
                self.assertEqual(body["status"], "failed")

    def test_row_or_stack_cleanup_failure_blocks_success(self):
        for failure in ("cleanup", "stack-cleanup", "startup"):
            with self.subTest(failure=failure):
                result, body = self.invoke(failure, start=True)
                self.assertNotEqual(result.returncode, 0)
                self.assertEqual(body["status"], "failed")

    def test_keep_stack_is_diagnostic_and_cannot_pass_release(self):
        result, body = self.invoke(start=True, keep=True)
        self.assertNotEqual(result.returncode, 0)
        self.assertFalse(body["cleanup"]["smokeStackStopped"])
