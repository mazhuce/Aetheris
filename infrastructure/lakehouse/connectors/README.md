# R1 connector cache

This directory is populated by `scripts/fetch-r1-connectors.py` from the
reviewed Maven Central URLs in `configs/lakehouse/connector-allowlist.toml`.
JARs are ignored by Git and are verified by SHA-256 before a Compose start.
Every non-check-only fetch also writes `CONNECTOR-MANIFEST.json` with the
coordinate, immutable source URL, declared license, filename and verified
digest. The manifest contains no credentials and can be attached to an R1
release or recovery report.
