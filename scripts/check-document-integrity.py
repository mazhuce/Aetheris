#!/usr/bin/env python3
"""Reject corrupt current Markdown (tracked and non-ignored new files) and broken core-document links."""
from __future__ import annotations

import argparse
from pathlib import Path
import re
import subprocess
import sys
from urllib.parse import unquote, urlsplit

CORE = ("README.md", "docs/roadmap.md", "docs/r1-compatibility-matrix.md", "scripts/README.md")
LINK = re.compile(r"!?\[[^\]]*\]\(([^)]+)\)")
EDITOR_LINE = re.compile(r"^(.*(?:\.[A-Za-z0-9]+|Dockerfile)):[1-9][0-9]*$")


def validate(root, paths=None, core=CORE):
    root = Path(root).resolve()
    if paths is None:
        raw = subprocess.check_output(["git", "-C", str(root), "ls-files", "--cached", "--others", "--exclude-standard", "-z", "--", "*.md"])
        paths = [name.decode("utf-8") for name in raw.split(b"\0") if name]
    texts = {}
    for name in set(paths) | set(core):
        path = (root / name).resolve()
        if not path.is_relative_to(root) or not path.is_file():
            raise ValueError("missing document: " + name)
        content = path.read_bytes()
        if not content or b"\0" in content:
            raise ValueError("empty or NUL-corrupt document: " + name)
        try:
            text = content.decode("utf-8-sig")
        except UnicodeDecodeError as error:
            raise ValueError("document is not UTF-8: " + name) from error
        if not text.strip():
            raise ValueError("empty document: " + name)
        texts[name] = text
    linked_documents = set(core) | {name for name in texts if name.startswith("docs/remediation/")}
    for name in linked_documents:
        # Code samples may deliberately demonstrate non-existing paths.
        text = re.sub(r"(?ms)^```.*?^```[^\n]*", "", texts[name])
        for target in LINK.findall(text):
            target = target.strip().split(' "', 1)[0].strip("<>")
            # Local editor links support file.ext:line. Normalize before urlsplit so
            # Example.java:1 cannot be mistaken for an external URI scheme.
            line = EDITOR_LINE.fullmatch(unquote(target))
            if line:
                target = line.group(1)
            parsed = urlsplit(target)
            if parsed.scheme or not parsed.path:
                continue
            target_path = unquote(parsed.path)
            path = (root / target_path.lstrip("/")) if target_path.startswith("/") else (root / name).parent / target_path
            if not path.resolve().is_relative_to(root) or not path.exists():
                raise ValueError("missing relative link in " + name + ": " + target_path)
    return len(texts)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, default=Path(__file__).resolve().parents[1])
    args = parser.parse_args()
    try:
        count = validate(args.root)
        print(f"Document integrity passed: {count} Markdown files; core and remediation relative links checked")
        return 0
    except (OSError, ValueError, subprocess.SubprocessError) as error:
        print(str(error), file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
