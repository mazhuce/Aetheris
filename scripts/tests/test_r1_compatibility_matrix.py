import json
import tempfile
import unittest
from pathlib import Path

import sys

sys.path.insert(0, str(Path(__file__).resolve().parents[2] / "scripts"))
import r1_compatibility_matrix as matrix


class R1CompatibilityMatrixTest(unittest.TestCase):
    def test_checked_in_matrix_matches_current_sources(self):
        matrix.check_matrix()

    def test_matrix_carries_profiles_and_connector_digests(self):
        current = matrix.build_matrix()
        services = {item["name"]: item for item in current["services"]}
        self.assertEqual(["r1", "flink"], services["flink-jobmanager"]["profiles"])
        self.assertEqual(["r1", "trino"], services["trino"]["profiles"])
        self.assertEqual("iceberg-v2", current["acceptance"]["tableFormat"])
        self.assertTrue(all(len(item["sha256"]) == 64 for item in current["connectors"]))

    def test_stale_matrix_is_rejected(self):
        current = matrix.build_matrix()
        current["runtime"]["trino"] = "drifted"
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "matrix.json"
            path.write_text(json.dumps(current), encoding="utf-8")
            with self.assertRaises(matrix.CompatibilityMatrixError):
                matrix.check_matrix(path)


if __name__ == "__main__":
    unittest.main()