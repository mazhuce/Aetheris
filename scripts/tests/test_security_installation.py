import hashlib
import importlib.util
import json
import os
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

ROOT=Path(__file__).resolve().parents[2]
spec=importlib.util.spec_from_file_location('security_installation',ROOT/'scripts/security/installation.py')
tool=importlib.util.module_from_spec(spec);spec.loader.exec_module(tool)


class SecurityInstallationTests(unittest.TestCase):
    def test_missing_java_home_has_no_path_launcher_fallback(self):
        with patch.dict(os.environ,{},clear=True),self.assertRaises(ValueError):tool.java()

    def test_existing_or_external_directory_is_refused_without_java(self):
        tool.PRIVATE.mkdir(parents=True,exist_ok=True)
        with tempfile.TemporaryDirectory(prefix='keys-guard-',dir=tool.PRIVATE) as directory:
            marker=Path(directory)/'keep.txt';marker.write_text('retain',encoding='utf-8')
            for path in (directory,ROOT/'tmp'/'outside-key-generation'):
                with patch.object(tool,'java') as java,self.assertRaises(ValueError):tool.generate(path)
                java.assert_not_called()
            self.assertEqual(marker.read_text(encoding='utf-8'),'retain')

    def test_real_jdk_generates_unique_keys_public_only_registry_and_never_overwrites(self):
        # Required for this explicit real test; no skip that could turn missing
        # compiler support into a passing installation result.
        tool.PRIVATE.mkdir(parents=True,exist_ok=True)
        with tempfile.TemporaryDirectory(prefix='keys-real-',dir=tool.PRIVATE) as parent:
            target=Path(parent)/'new-installation';value=tool.generate(target)
            self.assertEqual(value['status'],'generated-not-installed');self.assertTrue(value['requiresPermissionsReview'])
            registry_bytes=(target/'public-registry.json').read_bytes();registry=json.loads(registry_bytes)
            self.assertEqual(hashlib.sha256(registry_bytes).hexdigest(),value['registrySha256'])
            self.assertEqual(len(registry['keys']),11);self.assertEqual(len({x['publicKey'] for x in registry['keys']}),11)
            self.assertEqual([x['caller'] for x in registry['keys'] if x['purpose']=='USER'],['gateway'])
            self.assertNotIn(b'PRIVATE KEY',registry_bytes)
            originals={file.name:hashlib.sha256(file.read_bytes()).hexdigest() for file in target.iterdir()}
            self.assertEqual(len(originals),13)
            with self.assertRaises(ValueError):tool.generate(target)
            self.assertEqual(originals,{file.name:hashlib.sha256(file.read_bytes()).hexdigest() for file in target.iterdir()})


if __name__=='__main__':unittest.main()
