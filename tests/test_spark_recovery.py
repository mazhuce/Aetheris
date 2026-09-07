"""Recovery must refuse missing storage before any service or directory creation."""
import os
import pathlib
import subprocess
import tempfile
import unittest
import xml.etree.ElementTree as ET

SCRIPT = pathlib.Path(__file__).resolve().parents[1] / 'scripts' / 'wsl-hive-test-server.sh'


@unittest.skipIf(os.name == 'nt', 'Run in WSL/Linux: recovery script requires bash and /proc')
class RecoveryPreflightTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = pathlib.Path(self.temporary.name)
        self.catalog = self.root / 'metastore'
        self.catalog.mkdir()
        (self.catalog / 'service.properties').write_text('existing catalog')
        (self.catalog / 'seg0').mkdir()
        self.hadoop = self.root / 'hadoop'
        self.hive = self.root / 'hive'
        for role in ('namenode', 'datanode'):
            directory = self.hadoop / role / 'current'
            directory.mkdir(parents=True)
            (directory / 'VERSION').write_text('existing storage')
        self.write_xml(self.hadoop / 'etc/hadoop/hdfs-site.xml', {
            'dfs.namenode.name.dir': (self.hadoop / 'namenode').as_uri(),
            'dfs.datanode.data.dir': (self.hadoop / 'datanode').as_uri(),
        })
        self.configure_catalog('jdbc:derby:;databaseName=' + str(self.catalog) + ';create=true')
        self.runtime = self.root / 'runtime'

    @staticmethod
    def write_xml(path, values):
        path.parent.mkdir(parents=True, exist_ok=True)
        root = ET.Element('configuration')
        for key, value in values.items():
            prop = ET.SubElement(root, 'property')
            ET.SubElement(prop, 'name').text = key
            ET.SubElement(prop, 'value').text = value
        ET.ElementTree(root).write(path)

    def configure_catalog(self, url):
        self.write_xml(self.hive / 'conf/hive-site.xml', {'javax.jdo.option.ConnectionURL': url})

    def run_script(self, action):
        env = dict(os.environ, HIVE_HOME=str(self.hive), HADOOP_HOME=str(self.hadoop),
                   AETHERIS_HIVE_RUNTIME_DIR=str(self.runtime))
        return subprocess.run(['bash', str(SCRIPT), action], env=env,
                              capture_output=True, text=True, timeout=10)

    def test_existing_storage_passes_without_mutation(self):
        result = self.run_script('preflight')
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertFalse(self.runtime.exists())

    def test_start_refuses_missing_derby_before_preparing_runtime(self):
        (self.catalog / 'service.properties').unlink()
        result = self.run_script('start')
        self.assertNotEqual(result.returncode, 0)
        self.assertIn('existing absolute Derby catalog not found', result.stderr)
        self.assertFalse(self.runtime.exists())

    def test_start_refuses_missing_hdfs_version_without_formatting(self):
        version = self.hadoop / 'namenode/current/VERSION'
        version.unlink()
        result = self.run_script('start')
        self.assertNotEqual(result.returncode, 0)
        self.assertIn('no formatting is performed', result.stderr)
        self.assertFalse(version.exists())
        self.assertFalse(self.runtime.exists())

    def test_relative_derby_location_is_refused(self):
        self.configure_catalog('jdbc:derby:metastore_db;create=true')
        result = self.run_script('preflight')
        self.assertNotEqual(result.returncode, 0)
        self.assertFalse((self.root / 'metastore_db').exists())


if __name__ == '__main__':
    unittest.main()
