"""Run baseline review probes without accessing the project's database."""

import argparse
from pathlib import Path
import subprocess
import sys
import tempfile
import xml.etree.ElementTree as ET


def classpath(root: Path, modules: tuple[str, ...]) -> str:
    paths = []
    for module in modules:
        reports = sorted((root / module / "target/surefire-reports").glob("TEST-*.xml"))
        if not reports:
            raise ValueError(f"Build {module} first: no Surefire XML found")
        properties = ET.parse(reports[0]).getroot().find("properties")
        value = next(p.get("value") for p in properties if p.get("name") == "java.class.path")
        separator = ";" if sys.platform == "win32" else ":"
        paths.extend(value.split(separator))
    return (";" if sys.platform == "win32" else ":").join(dict.fromkeys(paths))


def run_java(source: Path, root: Path, modules: tuple[str, ...], directory: Path, with_root: bool) -> None:
    arguments = ["-cp", classpath(root, modules), str(source)]
    if with_root:
        arguments.append(str(root))
    argfile = directory / (source.stem + ".args")
    argfile.write_text("\n".join('"' + arg.replace("\\", "/").replace('"', '\\"') + '"' for arg in arguments), encoding="utf-8")
    subprocess.run(["java", "@" + str(argfile)], cwd=root, check=True, timeout=120)


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source-root", required=True, type=Path)
    parser.add_argument("--temp-root", required=True, type=Path)
    args = parser.parse_args()
    root = args.source_root.resolve()
    evidence = Path(__file__).resolve().parent
    with tempfile.TemporaryDirectory(prefix="aetheris-review-reproduction-", dir=args.temp_root) as name:
        directory = Path(name)
        run_java(evidence / "AetherisReviewProbe.java", root, ("services/metadata", "services/auth"), directory, True)
        run_java(evidence / "AetherisWorkflowReviewProbe.java", root, ("services/workflow",), directory, False)
        script = (root / "scripts/platform-release-acceptance.ps1").read_text(encoding="utf-8-sig")
        functions = script[script.index("function Read-ReportStatus"):script.index("$offlineChecks =")]
        fixture = directory / "release-fixture"
        fixture.mkdir()
        command = "$repoRoot = '" + str(fixture).replace("'", "''") + "'\n" + functions + "\n" + r'''
$old = Join-Path $repoRoot 'old-passed.json'
$new = Join-Path $repoRoot 'new-failed.json'
'{"status":"passed","startedAtUtc":"2026-01-01T00:00:00Z"}' | Set-Content -LiteralPath $old
'{"status":"failed","startedAtUtc":"2026-10-08T00:00:00Z"}' | Set-Content -LiteralPath $new
(Get-Item $old).LastWriteTimeUtc = [DateTime]::Parse('2026-01-01T00:00:00Z')
(Get-Item $new).LastWriteTimeUtc = [DateTime]::Parse('2026-10-08T00:00:00Z')
$result = Read-LatestReportStatus 'review-only' '.' '*.json' @('new-failed.json')
Write-Output ('RELEASE_SELECTED_PATH=' + $result.path)
Write-Output ('RELEASE_SELECTED_STATUS=' + $result.status)
'''
        probe = directory / "report-selection.ps1"
        probe.write_text(command, encoding="utf-8-sig")
        subprocess.run(["pwsh", "-NoProfile", "-File", str(probe)], cwd=root, check=True, timeout=30)


if __name__ == "__main__":
    main()
