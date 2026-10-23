"""Validate an opt-in resolved Compose model before a future deploy gate uses it."""
import json
import re
from pathlib import Path
import contract


def validate(model):
    for service,schema in contract.services().items():
        name='ai-agent' if service=='ai-agent' else service+'-service'
        item=model['services'][name];env=item.get('environment',{})
        expected={'DB_USER':contract.role(schema),'DB_PASSWORD':'','SPRING_DATASOURCE_USERNAME':contract.role(schema),
                  'SPRING_FLYWAY_ENABLED':'false','SPRING_CONFIG_IMPORT':'configtree:/run/secrets/'}
        if any(str(env.get(k))!=v for k,v in expected.items()):raise ValueError('Shared-account fallback or Flyway enabled in '+name)
        for key,value in env.items():
            if value and any(part in key.upper() for part in ('POSTGRES_PASSWORD','FLYWAY_PASSWORD','DB_ADMIN','MIGRATION_PASSWORD')):
                raise ValueError('Privileged database credentials in runtime environment')
        secrets=item.get('secrets',[])
        if len(secrets)!=1 or secrets[0].get('source')!='db_'+schema+'_runtime' or secrets[0].get('target')!='spring.datasource.password':
            raise ValueError('Each runtime must mount only its own database secret')
        for mount in item.get('volumes',[]):
            source=str(mount.get('source','') if isinstance(mount,dict) else mount)
            if '/private' in source.replace('\\','/') or 'docker.sock' in source:raise ValueError('Private directory/socket mounted into runtime')
    for schema in contract.services().values():
        job=model['services'].get('db-migrate-'+schema)
        if not job:continue
        if not re.fullmatch(r'[a-zA-Z0-9][^\s]+@sha256:[0-9a-f]{64}',job.get('image','')):raise ValueError('Migration runner image must be a reviewed digest')
        mounted={item['source'] for item in job.get('secrets',[])}
        if mounted!={'db_admin_password','db_'+schema+'_migration'}:raise ValueError('Unexpected secrets in migration job')
    return True


if __name__=='__main__':
    import argparse
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('--resolved',required=True)
    args=p.parse_args();validate(json.loads(Path(args.resolved).read_text(encoding='utf-8')))
    print('Nine runtime Compose role/secret boundaries verified; deployment still requires a fresh live readiness receipt')
