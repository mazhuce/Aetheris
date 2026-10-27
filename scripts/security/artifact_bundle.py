"""Reuse exact locally built JAR bytes, never historical runtime outcomes."""
from __future__ import annotations
import hashlib
import io
import json
from pathlib import Path
import re
import zipfile

class BundleError(ValueError):pass
def digest(path):return hashlib.sha256(Path(path).read_bytes()).hexdigest()
def owned(root,name):
    candidate=root/str(name);resolved=candidate.resolve()
    if not resolved.is_relative_to(root/'output') or not resolved.is_file() or candidate.is_symlink():raise BundleError('ArtifactInputOutsideOwnedOutput')
    for parent in candidate.parents:
        if parent==root:break
        if parent.is_symlink():raise BundleError('ArtifactInputSymlink')
    return resolved
def source_files(root,modules):
    paths=[root/'pom.xml',root/'configs/database/services.json']
    for module in [*modules.values(),'libs/security-protocol','libs/security-servlet']:
        if not (root/module/'pom.xml').is_file() or not (root/module/'src/main').is_dir():raise BundleError('ArtifactSourceLayoutMissing')
        paths.extend([root/module/'pom.xml',*(root/module/'src/main').rglob('*')])
    return sorted({p for p in paths if p.is_file()})
def class_tree(root,folder):
    base=root/folder/'target/classes';values={p.relative_to(base).as_posix():digest(p) for p in base.rglob('*') if p.is_file()}
    if not any(p.endswith('.class') for p in values):raise BundleError('CurrentCompiledClassesMissing')
    return values
def verify_archive(archive,expected,prefix=''):
    names=[n for n in archive.namelist() if not n.endswith('/')]
    if len(names)!=len(set(names)):raise BundleError('AmbiguousJarEntries')
    classes={n.removeprefix(prefix) for n in names if n.startswith(prefix) and n.endswith('.class')}
    if classes!={n for n in expected if n.endswith('.class')}:raise BundleError('JarClassSetMismatch')
    for name,value in expected.items():
        try:raw=archive.read(prefix+name)
        except KeyError:raise BundleError('JarResourceMissing') from None
        if hashlib.sha256(raw).hexdigest()!=value:raise BundleError('JarClassOrResourceMismatch')
def verify(root,manifest_path,expected_manifest_sha256,modules):
    root=Path(root).resolve()
    if not isinstance(expected_manifest_sha256,str) or not re.fullmatch('[a-f0-9]{64}',expected_manifest_sha256):raise BundleError('ExpectedManifestAnchorRequired')
    manifest_path=owned(root,manifest_path)
    if digest(manifest_path)!=expected_manifest_sha256:raise BundleError('ArtifactManifestAnchorMismatch')
    manifest=json.loads(manifest_path.read_text(encoding='utf-8'));report_path=owned(root,manifest['producerReport'])
    if digest(report_path)!=manifest.get('producerReportSha256'):raise BundleError('ArtifactProducerReportChanged')
    report=json.loads(report_path.read_text(encoding='utf-8'));run=report.get('runId')
    if not isinstance(run,str) or not re.fullmatch('[a-f0-9]{32}',run) or manifest_path.parent!=report_path.parent/run or report.get('status')!='passed':raise BundleError('ArtifactProvenanceMismatch')
    if set(manifest.get('artifacts',{}))!=set(modules) or set(report.get('artifacts',{}))!=set(modules):raise BundleError('ArtifactModuleSetMismatch')
    sources=source_files(root,modules)
    if not sources:raise BundleError('EmptyArtifactSourceSet')
    prefixes=tuple(module+'/src/main/' for module in [*modules.values(),'libs/security-protocol','libs/security-servlet'])
    fixed={'pom.xml','configs/database/services.json',*(module+'/pom.xml' for module in [*modules.values(),'libs/security-protocol','libs/security-servlet'])}
    historical={p for p in report.get('sourceHashes',{}) if p in fixed or p.startswith(prefixes)}
    if historical!={p.relative_to(root).as_posix() for p in sources}:raise BundleError('ArtifactSourceSetChanged')
    for p in sources:
        if p.is_symlink() or report.get('sourceHashes',{}).get(p.relative_to(root).as_posix())!=digest(p):raise BundleError('ArtifactSourceChanged:'+p.relative_to(root).as_posix())
    libraries={name:class_tree(root,'libs/'+name) for name in ('security-protocol','security-servlet')}
    result={}
    for name,module in modules.items():
        entry=manifest['artifacts'][name];jar=owned(root,entry['path'])
        if jar.parent!=manifest_path.parent/'retained-jars' or digest(jar)!=entry['sha256'] or entry['sha256']!=report['artifacts'][name]['sha256'] or jar.stat().st_size!=entry.get('bytes'):raise BundleError('ArtifactBytesChanged')
        with zipfile.ZipFile(jar) as archive:
            verify_archive(archive,class_tree(root,module),'BOOT-INF/classes/')
            if name=='gateway' and any(n.startswith('BOOT-INF/lib/security-servlet-') for n in archive.namelist()):raise BundleError('ReactiveGatewayCannotUseServletLibrary')
            for library in (['security-protocol'] if name=='gateway' else ['security-protocol','security-servlet']):
                entries=[n for n in archive.namelist() if n.startswith('BOOT-INF/lib/'+library+'-') and n.endswith('.jar')]
                if len(entries)!=1:raise BundleError('AmbiguousSharedLibrary')
                with zipfile.ZipFile(io.BytesIO(archive.read(entries[0]))) as nested:verify_archive(nested,libraries[library])
        result[name]=jar
    return result,{'manifest':manifest_path.relative_to(root).as_posix(),'manifestSha256':expected_manifest_sha256,
        'originalBuildReport':report_path.relative_to(root).as_posix(),'originalBuildReportSha256':digest(report_path),
        'artifactSourceFilesVerified':len(sources),'currentClassesAndResourcesVerified':True,'historicalRuntimeChecksCopied':False,
        'scope':'Exact anchored JAR bytes reused as inputs; every runtime check is executed again under this new run/configuration.'}
