import copy
import importlib.util
import json
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[2]
try:
    import yaml
except ImportError:
    yaml = None


@unittest.skipUnless(yaml, "CI documentation job explicitly installs the pinned YAML parser")
class WorkflowReleaseContractTests(unittest.TestCase):
    def setUp(self):
        spec = importlib.util.spec_from_file_location("workflow_contract", ROOT / "scripts/check-workflow-contract.py")
        self.module = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(self.module)
        self.pins = json.loads((ROOT / ".github/workflows/action-pins.json").read_text(encoding="utf-8"))["actions"]

    def test_current_workflows_parse_and_use_verified_action_shas(self):
        self.assertEqual(self.module.validate(ROOT), 6)

    def test_moving_action_ref_and_raw_input_shell_injection_are_rejected(self):
        body = {"permissions": {"contents": "read"}, "jobs": {"example": {"steps": [{"uses": "actions/checkout@v4"}]}}}
        with self.assertRaises(ValueError): self.module.check_body("example.yml", body, self.pins)
        body["jobs"]["example"]["steps"] = [{"run": "echo '${{ inputs.image_tag }}'"}]
        with self.assertRaises(ValueError): self.module.check_body("example.yml", body, self.pins)

    def test_disconnected_image_build_is_rejected(self):
        body = yaml.load((ROOT / ".github/workflows/build.yml").read_text(encoding="utf-8"), Loader=self.module.WorkflowLoader)
        body["jobs"]["build"]["needs"] = []
        with self.assertRaisesRegex(ValueError, "disconnected"):
            self.module.check_body("build.yml", body, self.pins)
