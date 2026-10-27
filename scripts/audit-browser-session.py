#!/usr/bin/env python3
"""Owned PostgreSQL Auth V10 -> V11 and real runtime-role browser family concurrency acceptance."""
from __future__ import annotations
import argparse
import hashlib
import importlib.util
import json
from pathlib import Path
import re
import shutil
import sys
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
OUTPUT = ROOT / 'output/audit-browser-session-postgres'
TEST = 'com.aetheris.auth.service.BrowserSessionPostgresIT'
METHODS = frozenset((
    'twoInstancesConsumeOneRefreshVersionExactlyOnce',
    'committedRefreshThenLogoutMakesEveryLateTokenInThatFamilyInactive',
    'committedLogoutThenRefreshNeverProducesNewCredential',
    'racingRefreshAndLogoutAlwaysEndsRevokedAcrossNewStoreInstance',
    'oldFamilyLogoutCannotRevokeANewerLoginOfTheSameAccount',
    'repeatLogoutKeepsSameRevocationAndNeverChangesAccountApprovalVersions',
    'mismatchedStableSubjectOrFamilyCannotReadRotateOrRevokeAnotherFamily',
    'disabledOrChangedAccountVersionsInvalidateEveryBrowserFamily',
    'familyExpiryRejectsAccessAndRefreshWithoutProcessMemory',
    'loginCannotIssueFamilyAfterPasswordOrAuthoritySnapshotChanged',
    'sameNameRecreatedUidCannotInheritOldFamilyAndOldLogoutCannotRevokeNewOne',
    'refreshCannotExtendTheAbsoluteFamilyHorizon'))
spec = importlib.util.spec_from_file_location('browser_family_identity', ROOT / 'scripts/audit-identity-chain.py')
identity = importlib.util.module_from_spec(spec); spec.loader.exec_module(identity)
base = identity.base
identity.OUTPUT = OUTPUT
identity.LABEL = 'aetheris.browser-family.audit'
identity.REQUIRED_CASES = frozenset(('auth-v10-baseline', 'auth-v11-upgrade-preserves-history',
    'current-migration-set-applied', 'runtime-role-grants-verified', 'fresh-postgres-family-suite', 'all-family-cases-pass'))


def source_hashes():
    values = identity.source_hashes()
    paths = [Path(__file__), *(ROOT / 'services/auth/src/test').rglob('*.java')]
    values.update({p.relative_to(ROOT).as_posix(): hashlib.sha256(p.read_bytes()).hexdigest() for p in paths})
    return values


class Audit(identity.Audit):
    def __init__(self, output):
        super().__init__(output)
        self.report.update(purpose='isolated-postgres-browser-family', releaseEligible=False,
            limits='Real PostgreSQL, V10-to-V11 Auth migration, runtime role and two store instances. No actual browser, Gateway process, Redis fault injection, durable pipeline execution or full release acceptance.')

    def setup_database(self):
        self.checkpoint('owned-auth-v10-to-v11-and-current-service-migrations')
        self.prepare_database()
        self.runner({**self.base, 'command': 'provision-empty', 'sql': base.database.bootstrap()})
        current = ROOT / 'services/auth/src/main/resources/db/migration'
        historical = self.folder / 'auth-v10-migrations'; historical.mkdir(exist_ok=False)
        old = sorted(p for p in current.glob('V*__*.sql') if int(re.fullmatch(r'V([0-9]+)__.+[.]sql', p.name).group(1)) <= 10)
        if len(old) != 10: raise base.AuditFailure('ExpectedExactAuthV10History')
        for p in old: shutil.copyfile(p, historical / p.name)
        migrations = []
        for service, schema in base.database_contract.services().items():
            directory = historical if service == 'auth' else ROOT / 'services' / service / 'src/main/resources/db/migration'
            migrations.append(self.runner({**self.base, 'command': 'migrate', 'schema': schema, 'directory': str(directory)}))
        history_sql = "SELECT json_agg(json_build_object('version',version,'checksum',checksum,'success',success) ORDER BY installed_rank) FROM auth.flyway_schema_history WHERE version::int<=10"
        before = self.sql(history_sql, True)
        self.check('auth-v10-baseline', self.sql("SELECT to_regclass('auth.browser_session') IS NULL") == 't')
        upgrade = self.runner({**self.base, 'command': 'migrate', 'schema': 'auth', 'directory': str(current)})
        after = self.sql(history_sql, True)
        self.check('auth-v11-upgrade-preserves-history', before == after and upgrade['migrationsExecuted'] == 1
                   and upgrade['pending'] == 0 and upgrade['validated'] is True)
        migrations.append(upgrade)
        self.check('current-migration-set-applied', sum(m['migrationsExecuted'] for m in migrations) == len(self.report['migrationSourceHashes']))
        self.report['migrations'] = migrations
        self.report['upgrade'] = {'scope': 'Auth V10 -> V11; other service schemas use this run current sources',
            'historyPreserved': before == after, 'historicalFileHashes': {p.name: hashlib.sha256(p.read_bytes()).hexdigest() for p in old}}
        inventory = self.runner({**self.base, 'command': 'inventory'}); base.database.check_owned_inventory(inventory)
        self.runner({**self.base, 'command': 'sql', 'sql': 'BEGIN;\n' + base.database_contract.grants_sql(inventory) + '\nCOMMIT;'})
        self.report['roleVerification'] = self.runner({**self.base, 'command': 'verify', 'directories': base.database.directories()})
        self.check('runtime-role-grants-verified', True)

    def tests(self):
        self.checkpoint('real-postgres-refresh-logout-concurrency')
        suite = ROOT / 'services/auth/target/surefire-reports' / ('TEST-' + TEST + '.xml')
        prior = hashlib.sha256(suite.read_bytes()).hexdigest() if suite.exists() else None
        env = identity.clean_environment()
        env.update(AETHERIS_BROWSER_FAMILY_URL='jdbc:postgresql://127.0.0.1:' + str(self.report['ports']['postgres']) + '/aetheris_api_key_audit',
            AETHERIS_BROWSER_FAMILY_PASSWORD=self.base['credentials']['auth']['runtime'], AETHERIS_BROWSER_FAMILY_RUN=self.run_id)
        mvn = shutil.which('mvn.cmd' if sys.platform == 'win32' else 'mvn')
        if not mvn: raise base.AuditFailure('MavenMissing')
        args = [mvn, '-B', '-ntp', '-o', '-pl', 'services/auth', '-am', '-Dtest=BrowserSessionPostgresIT',
                '-Dsurefire.failIfNoSpecifiedTests=false', '-Daetheris.browser.family.audit=' + self.run_id, 'test']
        try:
            stdout = self.command(args, env=env, timeout=180)
            (self.folder / 'postgres-it.log').write_text(base.redact(stdout, self.secrets), encoding='utf-8')
            self.report['testExitCode'] = 0
        except base.AuditFailure:
            self.report['testExitCode'] = 1
        if not suite.is_file(): raise base.AuditFailure('MissingPostgresFamilyReport')
        raw = suite.read_bytes(); xml = ET.fromstring(raw)
        properties = {p.get('name'): p.get('value') for p in xml.findall('properties/property')}
        self.check('fresh-postgres-family-suite', xml.get('name') == TEST
            and properties.get('aetheris.browser.family.audit') == self.run_id and hashlib.sha256(raw).hexdigest() != prior)
        self.report['tests'] = {k: int(xml.get(k, '-1')) for k in ('tests', 'failures', 'errors', 'skipped')}
        self.report['testCases'] = [case.get('name') for case in xml.findall('testcase')]
        self.report['rawTestReportSha256'] = hashlib.sha256(raw).hexdigest()
        for props in list(xml.findall('properties')): xml.remove(props)
        archived = base.redact(ET.tostring(xml, encoding='unicode'), self.secrets)
        target = self.folder / 'postgres-it.xml'; target.write_text(archived, encoding='utf-8')
        self.report['testReportSha256'] = hashlib.sha256(target.read_bytes()).hexdigest()
        self.check('all-family-cases-pass', self.report['testExitCode'] == 0 and set(self.report['testCases']) == METHODS
            and self.report['tests'] == {'tests': len(METHODS), 'failures': 0, 'errors': 0, 'skipped': 0})

    def run(self):
        self.report['sourceHashes'] = source_hashes()
        self.report['migrationSourceHashes'] = base.database_contract.source_manifest()
        try:
            self.java = base.resolve_java21(); self.report['jdk'] = {'executable': self.java, 'specificationVersion': '21'}
            self.setup_containers(); self.setup_database(); self.tests()
            if source_hashes() != self.report['sourceHashes']: raise base.AuditFailure('SourceChangedDuringRun')
            self.report['sourceHashesMatchAtEnd'] = True; self.report['status'] = 'passed'
        except (Exception, KeyboardInterrupt) as failure:
            self.report['status'] = 'failed'
            self.report['failure'] = {'type': type(failure).__name__, 'message': base.redact(str(failure), self.secrets)
                if isinstance(failure, base.AuditFailure) else 'Operation failed; inspect sanitized report'}
        finally:
            self.cleanup()
        return self.report


def main():
    parser = argparse.ArgumentParser(description=__doc__); parser.add_argument('--output', required=True)
    report = Audit(parser.parse_args().output).run()
    print('Browser family PostgreSQL ' + report['status'] + '; tests=' + str(report.get('tests')) + '; cleanup=' + str(report['cleanup']))
    return 0 if report['status'] == 'passed' else 1


if __name__ == '__main__': sys.exit(main())
