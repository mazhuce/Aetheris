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

    def test_service_parser_does_not_borrow_an_adjacent_services_image(self):
        compose = "services:\n  first:\n    profiles: [r1]\n  second:\n    image: postgres:16.9\n"
        self.assertNotIn("image:", matrix.service_block(compose, "first"))

    def test_generator_rejects_duplicate_image_instead_of_recording_first(self):
        compose = "services:\n  trino:\n    profiles: [r1, trino]\n    image: one\n    image: two\n"
        with self.assertRaises(matrix.CompatibilityMatrixError):
            matrix._service_entry(compose, "trino")

    def test_matrix_preserves_actual_immutable_pins_and_unresolved_minio_refs(self):
        current = matrix.build_matrix()
        services = {item["name"]: item["image"] for item in current["services"]}
        lock = json.loads((matrix.ROOT / ".github/workflows/base-image-inventory.json").read_text(encoding="utf-8"))
        for reference in services.values():
            row = next(item for item in lock["images"] if item["reference"] == reference)
            if reference.startswith("quay.io/minio/"):
                self.assertEqual("blocked", row["state"])
                self.assertNotIn("@", reference)
            else:
                self.assertEqual("pinned", row["state"])
                proof = row["officialResolution"]
                self.assertEqual(proof["reference"] + "@" + proof["digest"], reference)


if __name__ == "__main__":
    unittest.main()
