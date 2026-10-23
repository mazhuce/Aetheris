"""Actual PS5.1/PS7 mock-only seed acceptance; never contacts APIs/DB/Docker."""
import datetime
import hashlib
import json
import os
from pathlib import Path
import subprocess
import unittest
import uuid

ROOT = Path(__file__).resolve().parents[2]
SCRIPT = ROOT / "scripts/seed-realistic-platform-data.ps1"
ENGINES = (
    ("ps51", Path(os.environ.get("WINDIR", r"C:\Windows")) / "System32/WindowsPowerShell/v1.0/powershell.exe", 5),
    ("ps7", Path(os.environ.get("ProgramFiles", r"C:\Program Files")) / "PowerShell/7/pwsh.exe", 7),
)
DRIVER = r'''
$ErrorActionPreference='Stop'
[Console]::OutputEncoding=New-Object System.Text.UTF8Encoding($false)
$global:Calls=New-Object Collections.ArrayList
$global:Prompts=New-Object Collections.ArrayList
$global:DockerCalls=New-Object Collections.ArrayList
$global:NextId=100;$global:Resources=@{};$global:States=@{};$global:Edges=@{};$global:Session=$null;$global:FixtureError=$null
$global:Mode=$env:SEED_FIXTURE_FAILURE
$global:Resources['/api/workflow/tasks/21']=@{id=21;name='交易-DWD-订单明细日加工';targetTable='dwd.dwd_order';taskSql='old SQL';status='disabled';cronExpression='0 0 8 * * ?'}
if($global:Mode -eq 'name-workflow-variant'){$global:Resources['/api/workflow/tasks/21'].name="交$([char]0x200d)易-DWD-订单明细日加工"}
foreach($entry in @(
    @('name-quality-variant','/api/quality/rules/31',"交$([char]0x200d)易-DWD-订单主键非空"),
    @('name-quality-replacement-variant','/api/quality/rules/31',"订$([char]0x200d)单表空值检查"),
    @('name-ingestion-variant','/api/ingestion/tasks/32',"交$([char]0x200d)易-ADS-订单指标同步至PostgreSQL"),
    @('name-ingestion-replacement-variant','/api/ingestion/tasks/32',"订$([char]0x200d)单数据每日同步")
)){
    if($global:Mode -eq $entry[0]){$global:Resources[$entry[1]]=@{id=$(if($entry[1] -like '*31'){31}else{32});name=$entry[2];status='enabled'}}
}
function New-State { @{executionRevision=3;definitionSha256=('a'*64);approved=$false;bindingGeneration=7;scheduleGeneration=17;cronBound=$false} }
foreach($key in @($global:Resources.Keys)){$global:States[$key]=New-State}
function global:Read-Host {
    param([string]$Prompt,[switch]$AsSecureString)
    [void]$global:Prompts.Add($Prompt)
    if(-not $AsSecureString){throw 'Unexpected prompt'}
    ConvertTo-SecureString 'fixture-password-not-an-account' -AsPlainText -Force
}
function Invoke-FixtureRequest {
    param([string]$Method,[string]$Uri,[string]$ContentType,[object]$Body,[int]$TimeoutSec,[hashtable]$Headers,
      [Microsoft.PowerShell.Commands.WebRequestSession]$WebSession,[int]$MaximumRedirection)
    if(-not $Uri.StartsWith('http://127.0.0.1:1/')){throw 'Fixture refused external origin'}
    $path=$Uri.Substring('http://127.0.0.1:1'.Length)
    if($Body -is [byte[]]){$Body=[Text.Encoding]::UTF8.GetString($Body)}
    $payload=if($null -eq $Body){$null}else{$Body|ConvertFrom-Json}
    $record=[ordered]@{method=$Method;path=$path;body=$payload;cookieSession=($null -ne $WebSession);headersPresent=($null -ne $Headers -and $Headers.Count -gt 0)}
    if($path -eq '/api/auth/login'){$record.body=@{username=$payload.username}}
    [void]$global:Calls.Add($record)
    if($null -eq $WebSession -or $record.headersPresent -or $MaximumRedirection -ne 0){throw 'Cookie-only no-redirect contract required'}
    if($path -eq '/api/auth/login'){
        if($global:Mode -eq 'login'){throw 'Fixture login failure'}
        $global:Session=$WebSession
        $WebSession.Cookies.Add([uri]'http://127.0.0.1:1',(New-Object Net.Cookie('aetheris_access','fixture-access','/')))
        $WebSession.Cookies.Add([uri]'http://127.0.0.1:1/api/auth/login',(New-Object Net.Cookie('aetheris_refresh','fixture-refresh','/api/auth')))
        return @{username=$(if($global:Mode -eq 'login-unicode-mismatch'){"e$([char]0x301)"}else{$env:SEED_FIXTURE_USERNAME});role='admin';expiresIn=$(if($global:Mode -like 'refresh*'){1}else{900000})}
    }
    if(-not [object]::ReferenceEquals($WebSession,$global:Session) -or $WebSession.Cookies.GetCookies([uri]$Uri).Count -lt 1){throw 'Session/cookie missing'}
    if($path -eq '/api/auth/refresh'){
        if($global:Mode -eq 'refresh-failure'){throw 'Fixture refresh response lost'}
        return @{username=$env:SEED_FIXTURE_USERNAME;role='admin';expiresIn=900000}
    }
    if($path -eq '/api/auth/me'){
        return @{id=9;username=$(if($global:Mode -eq 'wrong-user'){'other'}elseif($global:Mode -eq 'unicode-mismatch'){"e$([char]0x301)"}else{$env:SEED_FIXTURE_USERNAME});role=$(if($global:Mode -eq 'nonadmin'){'viewer'}else{'admin'});status=$(if($global:Mode -eq 'disabled-user'){'disabled'}else{'active'})}
    }
    if($global:Mode -eq 'api'){throw 'Fixture API failure'}
    if($path -match '^/api/metadata/datasources/(41|42|43)$'){
        $id=[long]$Matches[1];return @{id=$id;type=$(if($global:Mode -eq 'wrong-engine'){'hive2'}elseif($id -eq 43){'postgresql'}else{'spark'});name='中文数据源';managed=($global:Mode -eq 'managed');writeConfigured=($global:Mode -ne 'no-writer');status=$(if($global:Mode -eq 'isolated'){'isolated'}elseif($global:Mode -eq 'disabled-ds'){'disabled'}elseif($global:Mode -eq 'inactive-ds'){'inactive'}elseif($global:Mode -eq 'null-ds'){$null}elseif($global:Mode -eq 'unknown-ds'){'unknown'}else{'active'})}
    }
    if($path -match '^/api/metadata/datasources/(41|42|43)/test$'){return @{success=$true;latencyMs=1}}
    if($path -eq '/api/metadata/datasources/41/query'){
        if($global:Mode -eq 'preflight'){return @{rowCount=0;columns=@('dt','row_count');rows=@()}}
        return @{rowCount=1;columns=@('dt','row_count');rows=@(,@($(if($global:Mode -eq 'bad-date'){'2026-02-30'}else{'2026-10-18'}),123))}
    }
    if($path -match '^/api/(workflow/tasks|quality/rules|ingestion/tasks)$'){
        if($Method -eq 'GET'){
            foreach($key in @($global:Resources.Keys)){
                if($key.StartsWith("$path/")){
                    if($path -eq '/api/workflow/tasks'){if($global:Mode -eq 'flat-task-list'){return $global:Resources[$key]};@{task=$global:Resources[$key]}}
                    else{$global:Resources[$key]}
                }
            }
            if($path -eq '/api/workflow/tasks'){@{task=@{id=901;name='123';targetTable='123123'}};@{task=@{id=902;name='123';targetTable='unrelated'}}}
            return
        }
        if($Method -eq 'POST'){
            $global:NextId++;$v=@{id=$global:NextId};foreach($p in $payload.PSObject.Properties){$v[$p.Name]=$p.Value}
            $key="$path/$($v.id)";$global:Resources[$key]=$v;$global:States[$key]=New-State
            if($path -eq '/api/workflow/tasks'){if($global:Mode -eq 'flat-task'){return $v};if($global:Mode -eq 'zero-task'){$v.id=0};if($global:Mode -eq 'returned-name-variant'){$v.name=$v.name.Insert(1,[string][char]0x200d)};return @{task=$v}}
            return $v
        }
    }
    if($path -match '^(/api/workflow/tasks/[0-9]+)/dependencies\?upstreamTaskId=([0-9]+)$'){
        $key=$Matches[1];$up=[long]$Matches[2]
        if(-not $global:Edges.ContainsKey($path)){$global:Edges[$path]=@{taskId=$global:Resources[$key].id;upstreamTaskId=$up};$global:States[$key].executionRevision++}
        return
    }
    if($path -match '^(/api/workflow/tasks/[0-9]+)/dependencies$'){
        $key=$Matches[1]
        foreach($edge in $global:Edges.Values){if($edge.taskId -eq $global:Resources[$key].id){$edge}}
        if($global:Mode -eq 'extra-dependency'){@{taskId=$global:Resources[$key].id;upstreamTaskId=888}}
        return
    }
    if($path -match '^(/api/(workflow/tasks|quality/rules|ingestion/tasks)/[0-9]+)/(approval|approve|cron-binding|run)$'){
        $key=$Matches[1];$action=$Matches[3];$s=$global:States[$key]
        if($action -eq 'approval'){return $s.Clone()}
        if($action -eq 'approve'){
            if($global:Mode -eq 'approval-conflict' -or $payload.expectedRevision -ne $s.executionRevision){throw 'Fixture 409 revision changed'}
            if(@($payload.PSObject.Properties).Count -ne 1){throw 'Unexpected approval fields'}
            $s.approved=$true;return $s.Clone()
        }
        if($action -eq 'cron-binding'){
            if($global:Mode -eq 'binding-conflict' -or $payload.expectedGeneration -ne $s.scheduleGeneration -or $payload.expectedRevision -ne $s.executionRevision -or -not $s.approved){throw 'Fixture 409 generation changed'}
            if(@($payload.PSObject.Properties).Count -ne 2){throw 'Unexpected binding fields'}
            $s.bindingGeneration++;$s.scheduleGeneration++;$s.cronBound=$true;return $s.Clone()
        }
        if(-not $s.approved){throw 'Unapproved run'}
        if($global:Mode -eq 'run-timeout' -or $global:Mode -eq 'run-401'){throw 'Fixture uncertain dispatch failure'}
        if($global:Mode -eq 'run-unknown'){return @{status='unknown'}}
        if($global:Mode -eq 'run-failed'){return @{status='failed'}}
        if($global:Mode -eq 'quality-failed' -and $key -like '/api/quality/*'){return @{status='failed';violationCount=4}}
        if($global:Mode -eq 'ingestion-unknown' -and $key -like '/api/ingestion/*'){return @{status='unknown'}}
        if($key -like '/api/quality/*'){return @{status='passed';violationCount=0}}
        return @{status='success';durationMs=1;rowsWritten=7}
    }
    if($path -match '^/api/(workflow/tasks|quality/rules|ingestion/tasks)/[0-9]+$'){
        if($Method -eq 'PUT'){
            $v=@{id=$global:Resources[$path].id};foreach($p in $payload.PSObject.Properties){$v[$p.Name]=$p.Value}
            $global:Resources[$path]=$v;$s=$global:States[$path];$s.executionRevision++;$s.scheduleGeneration++;$s.approved=$false
        }elseif($Method -ne 'GET'){throw 'Unexpected method'}
        $v=$global:Resources[$path].Clone()
        if($global:Mode -eq 'definition-drift' -and $Method -eq 'GET' -and $path -like '/api/workflow/*'){$v.taskSql='concurrent external SQL'}
        if($global:Mode -eq 'definition-unicode-drift' -and $Method -eq 'GET' -and $path -like '/api/workflow/*'){$v.taskSql=$v.taskSql.Replace('未知',"未$([char]0x200d)知")}
        if($path -like '/api/workflow/*'){return @{task=$v}};return $v
    }
    throw 'Fixture refused unexpected API operation'
}
function global:Invoke-RestMethod {
    param([string]$Method,[string]$Uri,[string]$ContentType,[object]$Body,[int]$TimeoutSec,[hashtable]$Headers,
      [Microsoft.PowerShell.Commands.WebRequestSession]$WebSession,[int]$MaximumRedirection)
    try { Invoke-FixtureRequest @PSBoundParameters } catch { $global:FixtureError=$_.Exception.Message; throw }
}
function global:docker {[void]$global:DockerCalls.Add('forbidden');throw 'Docker forbidden'}
$params=@{BaseUrl='http://127.0.0.1:1';ReadDataSourceId=41;WriteDataSourceId=42}
if($env:SEED_FIXTURE_OWNER_MODE -ne 'missing'){$params.Username=$env:SEED_FIXTURE_USERNAME}
if($global:Mode -ne 'missing-postgres'){$params.PostgresDataSourceId=43}
foreach($flag in ($env:SEED_FIXTURE_FLAGS -split ',')){if($flag){$params[$flag]=$true}}
$status='completed';$errorType=$null;$errorMessage=$null;$errorLocation=$null;$code=0;$output=New-Object Collections.ArrayList
try{
    & $env:SEED_FIXTURE_SCRIPT @params | ForEach-Object {[void]$output.Add($_)}
    if($global:Mode -eq 'rerun'){& $env:SEED_FIXTURE_SCRIPT @params | ForEach-Object {[void]$output.Add($_)}}
}catch{$status='failed';$errorType=$_.Exception.GetType().FullName;$errorMessage=$_.Exception.Message;$errorLocation=$_.ScriptStackTrace;$code=1}
finally{
    $r=[ordered]@{engine=$PSVersionTable.PSVersion.ToString();status=$status;errorType=$errorType;errorMessage=$errorMessage;errorLocation=$errorLocation;fixtureError=$global:FixtureError;calls=@($global:Calls);prompts=@($global:Prompts);dockerCalls=@($global:DockerCalls);cookiesCleared=($null -eq $global:Session -or $global:Session.Cookies.Count -eq 0);output=@($output)}
    [IO.File]::WriteAllText($env:SEED_FIXTURE_REPORT,($r|ConvertTo-Json -Depth 40),(New-Object Text.UTF8Encoding($false)))
}
exit $code
'''


class SeedRealisticPlatformDataTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        if not all(p.is_file() for _, p, _ in ENGINES):
            raise RuntimeError("Both actual PowerShell engines required; no skipped acceptance")
        stamp = datetime.datetime.now(datetime.timezone.utc).strftime("%Y%m%dT%H%M%SZ")
        cls.output = ROOT / "output/audit-seed-script-package" / ("v2-" + os.environ.get("SEED_AUDIT_LABEL", "run") + "-" + stamp + "-" + uuid.uuid4().hex[:8])
        cls.output.mkdir(parents=True)
        cls.before = SCRIPT.read_bytes()
        cls.results = []
        (cls.output / "source-before.ps1").write_bytes(cls.before)

    @classmethod
    def tearDownClass(cls):
        result = {"purpose": "Mock-only real PS5.1/PS7; no API/DB/Docker execution", "sourceSha256": hashlib.sha256(cls.before).hexdigest(),
                  "sourceUnchanged": SCRIPT.read_bytes() == cls.before, "invocations": cls.results,
                  "cleanup": {"allChildrenExited": all(r["exited"] for r in cls.results), "noContainersOrDatabaseCreated": True}}
        (cls.output / "report.json").write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding="utf-8")
        print("Seed v2 evidence: " + str(cls.output))
        if not result["sourceUnchanged"]:
            raise AssertionError("Source changed during acceptance")

    def invoke(self, engine, mode="", flags=(), owner="测试管理员甲", missing=False):
        name, executable, major = engine
        directory = self.output / (name + "-" + (mode or "success") + "-" + uuid.uuid4().hex[:8])
        directory.mkdir()
        driver = directory / "driver.ps1"
        driver.write_text(DRIVER, encoding="utf-8-sig")
        report = directory / "result.json"
        env = dict(os.environ, SEED_FIXTURE_SCRIPT=str(SCRIPT), SEED_FIXTURE_REPORT=str(report), SEED_FIXTURE_FAILURE=mode,
                   SEED_FIXTURE_USERNAME=owner, SEED_FIXTURE_FLAGS=",".join(flags), SEED_FIXTURE_OWNER_MODE="missing" if missing else "explicit")
        env["PSModulePath"] = str(executable.parent / "Modules")
        p = subprocess.Popen([str(executable), "-NoLogo", "-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass", "-File", str(driver)], env=env, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
        try:
            out, err = p.communicate(timeout=25)
        except subprocess.TimeoutExpired:
            p.kill(); p.communicate(timeout=5)
            self.fail("Mock child exceeded deadline")
        (directory / "stdout.log").write_bytes(out)
        (directory / "stderr.log").write_bytes(err)
        self.results.append({"engine": name, "case": directory.name, "pid": p.pid, "returnCode": p.returncode, "exited": p.poll() is not None})
        self.assertTrue(report.is_file(), (out + err).decode("utf-8", errors="replace"))
        b = json.loads(report.read_text(encoding="utf-8-sig"))
        self.assertEqual(int(b["engine"].split(".")[0]), major)
        self.assertEqual(b["dockerCalls"], [])
        self.assertTrue(b["cookiesCleared"])
        self.assertFalse(any(c["method"] == "DELETE" for c in b["calls"]))
        return p.returncode, b

    def test_cookie_only_default_saves_chinese_without_execution_or_binding(self):
        for engine in ENGINES:
            with self.subTest(engine=engine[0]):
                code, b = self.invoke(engine)
                self.assertEqual(code, 0, b)
                self.assertEqual(b["calls"][0]["body"], {"username": "测试管理员甲"})
                self.assertTrue(all(c["cookieSession"] and not c["headersPresent"] for c in b["calls"]))
                self.assertEqual(b["calls"][1]["path"], "/api/auth/me")
                self.assertFalse(any(c["path"].endswith(("/run", "/approve", "/cron-binding")) for c in b["calls"]))
                mutations = [c for c in b["calls"] if c["method"] in ("POST", "PUT") and c["body"] and "name" in c["body"]]
                self.assertEqual(len(mutations), 12)
                workflow = [c for c in mutations if c["path"].startswith("/api/workflow/")]
                self.assertEqual(len(workflow), 6)
                self.assertTrue(any("待付款" in c["body"]["taskSql"] for c in workflow))
                old = next(c for c in workflow if c["path"].endswith("/21"))
                self.assertEqual((old["body"]["cronExpression"], old["body"]["status"]), ("0 0 8 * * ?", "disabled"))
                self.assertTrue(all(c["body"]["datasourceId"] == 42 for c in workflow))
                ingest = [c for c in mutations if c["path"] == "/api/ingestion/tasks"]
                self.assertTrue(all(c["body"]["sourceDatasourceId"] == 41 and c["body"]["targetDatasourceId"] == 43 for c in ingest))
                summary = json.loads("\n".join(b["output"]))
                self.assertEqual((summary["executed"], summary["approved"], summary["boundSchedules"]), ([], [], []))
                self.assertEqual(len(summary["pendingApproval"]), 12)
                self.assertIn("用户域", summary["reviewOnlySql"]["domainSql"])
                self.assertIn("CREATE SCHEMA", summary["reviewOnlySql"]["landingSql"])

    def test_explicit_flags_order_and_exact_cas(self):
        for engine in ENGINES:
            with self.subTest(engine=engine[0]):
                code, b = self.invoke(engine, flags=("ApproveDefinitions", "RunTasks", "BindSchedules"))
                self.assertEqual(code, 0, b)
                c = b["calls"]
                a = [i for i, x in enumerate(c) if x["path"].endswith("/approve")]
                r = [i for i, x in enumerate(c) if x["path"].endswith("/run")]
                s = [i for i, x in enumerate(c) if x["path"].endswith("/cron-binding")]
                self.assertEqual((len(a), len(r), len(s)), (12, 12, 12))
                config = [i for i, x in enumerate(c) if x["method"] in ("POST", "PUT") and ((x["body"] and "name" in x["body"]) or "/dependencies?" in x["path"])]
                self.assertLess(max(config), min(a)); self.assertLess(max(a), min(r)); self.assertLess(max(r), min(s))
                self.assertTrue(all(set(c[i]["body"]) == {"expectedRevision"} for i in a))
                self.assertTrue(all(set(c[i]["body"]) == {"expectedRevision", "expectedGeneration"} and c[i]["body"]["expectedGeneration"] >= 17 for i in s))

    def test_flags_missing_target_or_owner_fail_before_io(self):
        for engine in ENGINES:
            for kwargs in ({"flags": ("RunTasks",)}, {"flags": ("BindSchedules",)}, {"mode": "missing-postgres"}, {"missing": True}, {"owner": "   "}):
                with self.subTest(engine=engine[0], kwargs=kwargs):
                    code, b = self.invoke(engine, **kwargs)
                    self.assertNotEqual(code, 0); self.assertEqual(b["calls"], []); self.assertEqual(b["prompts"], [])

    def test_explicit_approval_run_and_binding_are_independent(self):
        for engine in ENGINES:
            for flags, runs, binds in ((("ApproveDefinitions",), 0, 0), (("ApproveDefinitions", "RunTasks"), 12, 0), (("ApproveDefinitions", "BindSchedules"), 0, 12)):
                with self.subTest(engine=engine[0], flags=flags):
                    code, b = self.invoke(engine, flags=flags)
                    self.assertEqual(code, 0, b)
                    summary = json.loads("\n".join(b["output"]))
                    self.assertEqual((len(summary["approved"]), len(summary["executed"]), len(summary["boundSchedules"])), (12, runs, binds))

    def test_identity_preflight_and_task_shape_reject(self):
        for engine in ENGINES:
            for mode in ("login", "wrong-user", "nonadmin", "disabled-user", "api", "managed", "isolated", "disabled-ds", "no-writer", "wrong-engine", "preflight", "bad-date", "flat-task", "zero-task", "flat-task-list"):
                with self.subTest(engine=engine[0], mode=mode):
                    code, b = self.invoke(engine, mode=mode)
                    self.assertNotEqual(code, 0)
                    self.assertFalse(any(c["path"].endswith(("/run", "/approve", "/cron-binding")) for c in b["calls"]))
                    if mode in ("flat-task", "zero-task"):
                        self.assertTrue(any(c["method"] == "POST" and c["path"] == "/api/workflow/tasks" for c in b["calls"]))
                    elif mode == "flat-task-list":
                        self.assertTrue(any(c["method"] == "GET" and c["path"] == "/api/workflow/tasks" for c in b["calls"]))
                    else:
                        self.assertFalse(any(c["method"] == "POST" and c["path"] == "/api/workflow/tasks" for c in b["calls"]))

    def test_conflict_and_uncertain_run_never_replayed(self):
        for engine in ENGINES:
            for mode in ("definition-drift", "extra-dependency", "approval-conflict", "binding-conflict", "run-timeout", "run-unknown", "run-failed", "run-401", "quality-failed", "ingestion-unknown"):
                with self.subTest(engine=engine[0], mode=mode):
                    code, b = self.invoke(engine, mode=mode, flags=("ApproveDefinitions", "RunTasks", "BindSchedules"))
                    self.assertNotEqual(code, 0)
                    paths = [c["path"] for c in b["calls"] if c["path"].endswith(("/run", "/approve", "/cron-binding"))]
                    self.assertEqual(len(paths), len(set(paths)))
                    summary = json.loads("\n".join(b["output"]))
                    self.assertEqual(summary["status"], "stopped-partial")
                    self.assertFalse(summary["automaticallyRetried"])
                    if mode.startswith("run-"):
                        self.assertEqual(len([p for p in paths if p.endswith("/run")]), 1)
                        self.assertFalse(any(p.endswith("/cron-binding") for p in paths))
                    if mode in ("definition-drift", "extra-dependency", "approval-conflict"):
                        self.assertFalse(any(p.endswith(("/run", "/cron-binding")) for p in paths))
                    if mode == "approval-conflict": self.assertEqual(len(paths), 1)
                    if mode == "binding-conflict": self.assertEqual(len(paths), 25)
                    if mode in ("definition-drift", "extra-dependency"): self.assertEqual(paths, [])
                    if mode == "quality-failed": self.assertEqual(len(summary["executed"]), 6)
                    if mode == "ingestion-unknown": self.assertEqual(len(summary["executed"]), 10)

    def test_unchanged_second_run_does_not_put_or_create_again(self):
        for engine in ENGINES:
            with self.subTest(engine=engine[0]):
                code, b = self.invoke(engine, mode="rerun")
                self.assertEqual(code, 0, b)
                self.assertEqual(len(b["output"]), 2)
                definitions = [c for c in b["calls"] if c["method"] in ("POST", "PUT") and c["body"] and "name" in c["body"]]
                self.assertEqual(len(definitions), 12)

    def test_refresh_cookie_only_serial_and_failure_not_retried(self):
        for engine in ENGINES:
            for mode in ("refresh", "refresh-failure"):
                with self.subTest(engine=engine[0], mode=mode):
                    code, b = self.invoke(engine, mode=mode)
                    self.assertEqual(code, 0 if mode == "refresh" else 1, b)
                    self.assertEqual(len([c for c in b["calls"] if c["path"] == "/api/auth/refresh"]), 1)
                    if mode == "refresh-failure": self.assertEqual(len(b["calls"]), 2)

    def test_username_is_ordinal_not_culture_or_unicode_normalized(self):
        for engine in ENGINES:
            for mode in ("unicode-mismatch", "login-unicode-mismatch"):
                with self.subTest(engine=engine[0], mode=mode):
                    code, b = self.invoke(engine, mode=mode, owner="\u00e9")
                    self.assertNotEqual(code, 0)
                    self.assertEqual(len(b["calls"]), 2 if mode == "unicode-mismatch" else 1)

    def test_sql_and_returned_name_drift_are_ordinal_even_with_unchanged_versions(self):
        for engine in ENGINES:
            for mode in ("definition-unicode-drift", "returned-name-variant"):
                with self.subTest(engine=engine[0], mode=mode):
                    code, b = self.invoke(engine, mode=mode, flags=("ApproveDefinitions",))
                    self.assertNotEqual(code, 0)
                    self.assertFalse(any(c["path"].endswith("/approve") for c in b["calls"]))
                    self.assertTrue(any(c["method"] == "POST" and c["path"] == "/api/workflow/tasks" for c in b["calls"]))
                    if mode == "definition-unicode-drift":
                        self.assertTrue(any(c["method"] == "GET" and c["path"] == "/api/workflow/tasks/21" for c in b["calls"]))

    def test_existing_name_and_replacement_alias_are_exact_not_cultural(self):
        for engine in ENGINES:
            for mode, forbidden in (("name-workflow-variant", "/api/workflow/tasks/21"), ("name-quality-variant", "/api/quality/rules/31"), ("name-quality-replacement-variant", "/api/quality/rules/31"), ("name-ingestion-variant", "/api/ingestion/tasks/32"), ("name-ingestion-replacement-variant", "/api/ingestion/tasks/32")):
                with self.subTest(engine=engine[0], mode=mode):
                    code, b = self.invoke(engine, mode=mode)
                    self.assertEqual(code, 0, b)
                    self.assertFalse(any(c["method"] == "PUT" and c["path"] == forbidden for c in b["calls"]))

    def test_datasource_only_active_configuration_is_accepted(self):
        for engine in ENGINES:
            for mode in ("inactive-ds", "null-ds", "unknown-ds"):
                with self.subTest(engine=engine[0], mode=mode):
                    code, b = self.invoke(engine, mode=mode)
                    self.assertNotEqual(code, 0)
                    self.assertFalse(any(c["method"] in ("POST", "PUT") and c["path"].startswith(("/api/workflow/", "/api/quality/", "/api/ingestion/")) for c in b["calls"]))

if __name__ == "__main__":
    unittest.main()
