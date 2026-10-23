import importlib.util
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[2]


class DocumentIntegrityTests(unittest.TestCase):
    def setUp(self):
        spec = importlib.util.spec_from_file_location("document_integrity", ROOT / "scripts/check-document-integrity.py")
        self.checker = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(self.checker)

    def test_nul_empty_and_invalid_utf8_are_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            for content in (b"\0" * 40, b" \n", b"# Title\0hidden", b"\xff"):
                (root / "README.md").write_bytes(content)
                with self.subTest(content=content), self.assertRaises(ValueError):
                    self.checker.validate(root, ["README.md"], ["README.md"])

    def test_broken_relative_link_is_rejected_but_remote_and_anchor_are_allowed(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            readme = root / "README.md"
            readme.write_text("# Test\n[Web](https://example.invalid/a) [Anchor](#test)\n", encoding="utf-8")
            self.checker.validate(root, ["README.md"], ["README.md"])
            readme.write_text("# Test\n[Missing](docs/missing.md)", encoding="utf-8")
            with self.assertRaisesRegex(ValueError, "missing"):
                self.checker.validate(root, ["README.md"], ["README.md"])

    def test_repository_core_documents_are_readable_and_linked(self):
        self.checker.validate(ROOT)

    def test_current_untracked_markdown_is_checked_before_commit(self):
        with tempfile.TemporaryDirectory(prefix="document-inventory-",dir=ROOT/"output") as directory:
            root = Path(directory)
            (root / "README.md").write_text("# Existing", encoding="utf-8")
            (root / "new-contract.md").write_bytes(b"# New\0corrupt")
            # Simulate Git's tracked and untracked inventories; the filesystem validation is real.
            def inventory(command):
                return b"README.md\0new-contract.md\0" if "--others" in command else b"README.md\0"
            with patch.object(self.checker.subprocess,"check_output",side_effect=inventory):
                with self.assertRaisesRegex(ValueError,"NUL-corrupt"):
                    self.checker.validate(root,core=["README.md"])

    def test_editor_line_suffix_keeps_the_actual_file_and_workspace_boundary_checked(self):
        with tempfile.TemporaryDirectory(prefix="document-links-",dir=ROOT/"output") as directory:
            root=Path(directory)
            (root/"Example.java").write_text("class Example {}",encoding="utf-8")
            (root/"README.md").write_text("[Source](Example.java:1)",encoding="utf-8")
            self.checker.validate(root,["README.md"],["README.md"])
            for target in ("Missing.java:1","../outside.java:1"):
                (root/"README.md").write_text("[Source]("+target+")",encoding="utf-8")
                with self.subTest(target=target),self.assertRaises(ValueError):
                    self.checker.validate(root,["README.md"],["README.md"])
