import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]


class R3HaAcceptanceEntryTests(unittest.TestCase):
    def test_entrypoint_uses_isolated_ha_endpoints_and_overlay(self):
        script = (ROOT / "scripts" / "r3-ha-acceptance.ps1").read_text(encoding="utf-8")
        for marker in (
            "docker-compose.r3-ha.yml",
            "aetheris-r3-ha",
            "http://127.0.0.1:18184",
            "http://127.0.0.1:18185",
            "http://127.0.0.1:18186",
            "validate-r3-ha-report.py",
        ):
            self.assertIn(marker, script)

    def test_entrypoint_forwards_fail_closed_fault_switches(self):
        script = (ROOT / "scripts" / "r3-ha-acceptance.ps1").read_text(encoding="utf-8")
        self.assertIn('if ($SkipNetworkFaultInjection) { $args += "-SkipNetworkFaultInjection" }', script)
        self.assertIn('if ($SkipFaultInjection) { $args += "-SkipFaultInjection" }', script)


if __name__ == "__main__":
    unittest.main()
