import hashlib
import importlib.util
import tempfile
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
SPEC = importlib.util.spec_from_file_location("fetch_r1_connectors", ROOT / "scripts" / "fetch-r1-connectors.py")
fetcher = importlib.util.module_from_spec(SPEC)
assert SPEC.loader is not None
SPEC.loader.exec_module(fetcher)


class R1ConnectorFetcherTests(unittest.TestCase):
    def test_existing_artifact_is_verified_without_network(self):
        payload = b"fixture-jar"
        digest = hashlib.sha256(payload).hexdigest()
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            output = root / "connectors"
            output.mkdir()
            (output / "example-1.0.jar").write_bytes(payload)
            allowlist = root / "allowlist.toml"
            allowlist.write_text(
                "[[connector]]\n"
                "id='example'\n"
                "coordinate='org.example:example:1.0'\n"
                "source='https://repo1.maven.org/maven2/org/example/example/1.0/example-1.0.jar'\n"
                f"sha256='{digest}'\nlicense='Apache-2.0'\nstatus='candidate'\n",
                encoding="utf-8",
            )
            result = fetcher.fetch(output, allowlist, check_only=False)
            self.assertEqual("verified", result[0]["status"])
            manifest = (output / "CONNECTOR-MANIFEST.json")
            self.assertTrue(manifest.exists())
            self.assertIn('"license": "Apache-2.0"', manifest.read_text(encoding="utf-8"))

    def test_coordinate_filename_is_stable(self):
        self.assertEqual("iceberg-1.7.1.jar", fetcher.artifact_name("org.apache.iceberg:iceberg:1.7.1"))
        with self.assertRaises(fetcher.ConnectorFetchError):
            fetcher.artifact_name("invalid")


if __name__ == "__main__":
    unittest.main()
