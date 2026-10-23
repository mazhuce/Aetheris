#!/usr/bin/env python3
"""Prepare fresh independent S08 keys. This command never rotates/installs keys."""
import argparse
import json
import os
from pathlib import Path
import subprocess
import sys

ROOT=Path(__file__).resolve().parents[2]
PRIVATE=ROOT/'configs/security/private'


def java():
    directory=os.environ.get('JAVA_HOME')
    if not directory:raise ValueError('Set JAVA_HOME to the reviewed JDK21; PATH launchers are not used')
    executable=(Path(directory)/'bin'/('java.exe' if os.name=='nt' else 'java')).resolve(strict=True)
    if not executable.is_file():raise ValueError('Invalid JAVA_HOME')
    result=subprocess.run([str(executable),'-version'],capture_output=True,text=True,timeout=10)
    import re
    if result.returncode or not re.search(r'version "21(?:\.|\")',result.stderr+result.stdout):raise ValueError('JDK21 is required')
    return executable


def generate(directory):
    PRIVATE.mkdir(parents=True,exist_ok=True)
    target=Path(directory)
    target=(target if target.is_absolute() else ROOT/target).resolve()
    if PRIVATE.resolve() not in target.parents or target.exists() or not target.parent.is_dir():
        raise ValueError('Use a new child directory inside configs/security/private')
    result=subprocess.run([str(java()),'--source','21',str(ROOT/'scripts/security/GenerateKeys.java'),str(target)],
                          capture_output=True,text=True,encoding='utf-8',errors='replace',timeout=45)
    if result.returncode:raise ValueError('Fresh key generation failed; inspect the new directory, no overwrite was performed')
    value=json.loads((target/'installation.json').read_text(encoding='utf-8'))
    if value.get('status')!='generated-not-installed':raise ValueError('Incomplete key preparation')
    return value


def main():
    p=argparse.ArgumentParser(description=__doc__);sub=p.add_subparsers(dest='command',required=True)
    command=sub.add_parser('generate');command.add_argument('--directory',required=True)
    args=p.parse_args();value=generate(args.directory)
    print('Prepared '+str(len(value['services']))+' fresh service keys; not installed or rotated; review file access before use')


if __name__=='__main__':
    try:main()
    except Exception as error:
        print('Security installation preparation failed: '+type(error).__name__+'; no secret output',file=sys.stderr);sys.exit(1)
