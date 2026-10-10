#!/usr/bin/env python3
"""Fail when one Flyway schema contains duplicate versioned migrations.

Each Aetheris service owns a separate Flyway schema.  Therefore versions are
required to be unique within a service, while the same version may be reused
by another service (for example, every service can have a V1__init.sql).
"""

from __future__ import annotations

import argparse
import re
import sys
from pathlib import Path


VERSIONED_MIGRATION = re.compile(r"^V(?P<version>.+)__(?P<description>.+)\.sql$", re.IGNORECASE)


def find_duplicates(root: Path) -> tuple[list[str], int, int]:
    """Return duplicate descriptions, file count and service count."""

    duplicates: list[str] = []
    file_count = 0
    service_count = 0
    for migration_dir in sorted(
        root.glob("services/*/src/main/resources/db/migration")
    ):
        if not migration_dir.is_dir():
            continue
        service_count += 1
        by_version: dict[str, list[Path]] = {}
        for path in sorted(migration_dir.iterdir()):
            if not path.is_file() or path.suffix.lower() != ".sql":
                continue
            # Target/classes is intentionally outside this glob.  Only source
            # migrations are authoritative and should be checked in CI.
            file_count += 1
            if not path.name.startswith("V"):
                continue
            match = VERSIONED_MIGRATION.match(path.name)
            if not match:
                duplicates.append(
                    f"{migration_dir.parents[4].name}: invalid "
                    f"versioned migration filename {path.name}"
                )
                continue
            version = match.group("version")
            by_version.setdefault(version, []).append(path)
        for version, paths in by_version.items():
            if len(paths) > 1:
                rendered = ", ".join(str(p.relative_to(root)) for p in paths)
                duplicates.append(
                    f"{migration_dir.parents[4].name}: version "
                    f"V{version} appears more than once ({rendered})"
                )
    return duplicates, file_count, service_count


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--root",
        type=Path,
        default=Path.cwd(),
        help="repository root (defaults to the current directory)",
    )
    args = parser.parse_args(argv)
    root = args.root.resolve()
    errors, file_count, service_count = find_duplicates(root)
    if errors:
        print("Flyway migration validation failed:", file=sys.stderr)
        for error in errors:
            print(f"- {error}", file=sys.stderr)
        return 1
    print(
        f"Flyway migration validation passed: {file_count} files across "
        f"{service_count} services."
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
