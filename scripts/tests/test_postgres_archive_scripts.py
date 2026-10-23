"""Exercise actual PowerShell entry points against a fake docker command."""
import json
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]
PWSH = shutil.which("pwsh")
ARCHIVE = b"PGDMP\0\xff\r\n\x80"


@unittest.skipUnless(PWSH, "PowerShell is required for script behavior tests")
class PostgresArchiveScriptTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix=".postgres-test-", dir=ROOT / "scripts/tests")
        self.addCleanup(self.temp.cleanup)
        self.directory = Path(self.temp.name)
        self.archive = self.directory / "backup with spaces.dump"
        self.calls = self.directory / "calls.json"

    def invoke(self, script, arguments, fail=""):
        # Arguments below are fixed test fixtures, never user-supplied shell text.
        driver = self.directory / "driver.ps1"
        code = r'''
$global:Calls = [System.Collections.Generic.List[object]]::new()
function global:docker {
    $items = @($args | ForEach-Object { [string]$_ })
    $global:Calls.Add($items)
    $global:LASTEXITCODE = 0
    if ($env:GATE_TEST_FAIL -and ($items -contains $env:GATE_TEST_FAIL)) { $global:LASTEXITCODE = 17; return }
    if ($items -contains 'mktemp') { Write-Output '/tmp/aetheris-pg.Test123'; return }
    if ($items[0] -eq 'cp' -and $items[1] -match '^[^/\\:]+:/tmp/') {
        [IO.File]::WriteAllBytes($items[2], [byte[]](80,71,68,77,80,0,255,13,10,128))
    }
}
try {
    & $env:GATE_TEST_SCRIPT __ARGUMENTS__
    $code = 0
} catch { Write-Output $_.Exception.Message; $code = 1 }
finally { ConvertTo-Json -InputObject @($global:Calls.ToArray()) -Depth 6 | Set-Content -LiteralPath $env:GATE_TEST_CALLS -Encoding utf8 }
exit $code
'''.replace("__ARGUMENTS__", arguments)
        driver.write_text(code, encoding="utf-8")
        import os
        env = dict(os.environ, GATE_TEST_SCRIPT=str(ROOT / "scripts" / script),
                   GATE_TEST_FAIL=fail, GATE_TEST_CALLS=str(self.calls), GATE_TEST_ARCHIVE=str(self.archive))
        result = subprocess.run([PWSH, "-NoProfile", "-File", str(driver)], env=env, capture_output=True, text=True, encoding="utf-8", errors="replace")
        calls = json.loads(self.calls.read_text(encoding="utf-8-sig"))
        return result, calls

    def test_backup_preserves_all_binary_bytes_and_cleans_container(self):
        result, calls = self.invoke("backup-postgres.ps1", "-OutputFile $env:GATE_TEST_ARCHIVE")
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertEqual(self.archive.read_bytes(), ARCHIVE)
        self.assertTrue(any("pg_dump" in c and "-f" in c for c in calls))
        self.assertTrue(any("rm" in c for c in calls))
        self.assertTrue(any("rmdir" in c for c in calls))
        self.assertFalse(any("-T" in c for c in calls))

    def test_backup_dump_and_copy_failures_do_not_publish(self):
        for failure in ("pg_dump", "cp"):
            with self.subTest(failure=failure):
                result, calls = self.invoke("backup-postgres.ps1", "-OutputFile $env:GATE_TEST_ARCHIVE", failure)
                self.assertNotEqual(result.returncode, 0)
                self.assertFalse(self.archive.exists())
                self.assertTrue(any("rm" in c for c in calls))
                self.assertFalse(list(self.directory.glob("*.partial")))

    def test_cleanup_failure_cannot_report_success(self):
        result, _ = self.invoke("backup-postgres.ps1", "-OutputFile $env:GATE_TEST_ARCHIVE", "rm")
        self.assertNotEqual(result.returncode, 0)

    def test_existing_backup_is_preserved_without_force(self):
        self.archive.write_bytes(ARCHIVE)
        result, calls = self.invoke("backup-postgres.ps1", "-OutputFile $env:GATE_TEST_ARCHIVE")
        self.assertNotEqual(result.returncode, 0)
        self.assertEqual(calls, [])
        self.assertEqual(self.archive.read_bytes(), ARCHIVE)

    def test_whatif_never_calls_docker(self):
        self.archive.write_bytes(ARCHIVE)
        for script, arguments in (("backup-postgres.ps1", "-OutputDir . -WhatIf"),
                                  ("restore-postgres.ps1", "-BackupFile $env:GATE_TEST_ARCHIVE -Clean -WhatIf")):
            result, calls = self.invoke(script, arguments)
            self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
            self.assertEqual(calls, [])

    def test_restore_checks_archive_and_uses_transaction_and_cleanup(self):
        self.archive.write_bytes(ARCHIVE)
        result, calls = self.invoke("restore-postgres.ps1", "-BackupFile $env:GATE_TEST_ARCHIVE -Clean -Confirm:$false")
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        restore = next(c for c in calls if "--single-transaction" in c)
        self.assertIn("--clean", restore)
        self.assertIn("--exit-on-error", restore)
        self.assertTrue(any("--list" in c for c in calls))
        self.assertTrue(any("rmdir" in c for c in calls))
        self.assertEqual(self.archive.read_bytes(), ARCHIVE)

    def test_bad_digest_and_bad_header_fail_before_docker(self):
        self.archive.write_bytes(ARCHIVE)
        result, calls = self.invoke("restore-postgres.ps1", "-BackupFile $env:GATE_TEST_ARCHIVE -ExpectedSha256 ('0' * 64)")
        self.assertNotEqual(result.returncode, 0)
        self.assertEqual(calls, [])
        self.archive.write_bytes(b"wrong")
        result, calls = self.invoke("restore-postgres.ps1", "-BackupFile $env:GATE_TEST_ARCHIVE")
        self.assertNotEqual(result.returncode, 0)
        self.assertEqual(calls, [])

    def test_restore_native_failure_is_nonzero_and_cleans_up(self):
        self.archive.write_bytes(ARCHIVE)
        result, calls = self.invoke("restore-postgres.ps1", "-BackupFile $env:GATE_TEST_ARCHIVE", "--single-transaction")
        self.assertNotEqual(result.returncode, 0)
        self.assertTrue(any("rmdir" in c for c in calls))
