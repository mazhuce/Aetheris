import importlib.util
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch


ROOT = Path(__file__).resolve().parents[2]
SPEC = importlib.util.spec_from_file_location("validate_r3_ha", ROOT / "scripts" / "validate-r3-ha.py")
validator = importlib.util.module_from_spec(SPEC)
assert SPEC.loader is not None
SPEC.loader.exec_module(validator)


class R3HaValidatorTests(unittest.TestCase):
    def test_current_overlay_passes(self):
        validator.validate()

    def test_missing_standby_is_rejected(self):
        overlay = validator.OVERLAY.read_text(encoding="utf-8")
        modified = overlay.replace("  flink-jobmanager-standby:\n", "  flink-jobmanager-standby-removed:\n", 1)
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "docker-compose.r3-ha.yml"
            path.write_text(modified, encoding="utf-8")
            with patch.object(validator, "OVERLAY", path):
                with self.assertRaisesRegex(ValueError, "standby"):
                    validator.validate()

    def test_non_loopback_port_is_rejected(self):
        overlay = validator.OVERLAY.read_text(encoding="utf-8")
        modified = overlay.replace('"127.0.0.1:18186:8083"', '"0.0.0.0:18186:8083"', 1)
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "docker-compose.r3-ha.yml"
            path.write_text(modified, encoding="utf-8")
            with patch.object(validator, "OVERLAY", path):
                with self.assertRaisesRegex(ValueError, "loopback"):
                    validator.validate()


if __name__ == "__main__":
    unittest.main()
