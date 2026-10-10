#!/usr/bin/env bash
set -euo pipefail

# Verify every Aetheris application image by the digest resolved after pull.
# This intentionally fails closed.  A deployment can skip verification only
# with the explicit AETHERIS_COSIGN_POLICY=disabled setting.

policy="${AETHERIS_COSIGN_POLICY:-required}"
case "$policy" in
  required) ;;
  disabled)
    echo "WARNING: image signature verification explicitly disabled (AETHERIS_COSIGN_POLICY=disabled)." >&2
    exit 0
    ;;
  *)
    echo "AETHERIS_COSIGN_POLICY must be required or disabled" >&2
    exit 2
    ;;
esac

command -v docker >/dev/null || { echo "docker is required" >&2; exit 2; }
command -v cosign >/dev/null || {
  echo "cosign is required when AETHERIS_COSIGN_POLICY=required" >&2
  exit 2
}

prefix="${AETHERIS_IMAGE_PREFIX:-ghcr.io/mazhuce/aetheris}"
tag="${AETHERIS_IMAGE_TAG:-latest}"
identity="${AETHERIS_COSIGN_CERT_IDENTITY:-https://github.com/mazhuce/Aetheris/.github/workflows/build.yml@refs/heads/main}"
issuer="${AETHERIS_COSIGN_CERT_OIDC_ISSUER:-https://token.actions.githubusercontent.com}"
services="${AETHERIS_IMAGE_SERVICES:-system metadata serving quality ingestion governance workflow ai-agent auth gateway web}"
read -r -a service_list <<< "$services"

for service in "${service_list[@]}"; do
  image="${prefix}-${service}:${tag}"
  # RepoDigests is populated by docker compose pull.  Refuse a tag-only
  # verification: the registry tag could move between inspection and verify.
  digest="$(docker image inspect --format '{{range .RepoDigests}}{{println .}}{{end}}' "$image" 2>/dev/null \
    | awk -v prefix="${prefix}-${service}@sha256:" 'index($0, prefix) == 1 { print; exit }')"
  if [[ -z "$digest" ]]; then
    echo "No registry digest found for ${image}; refusing tag-only verification" >&2
    exit 1
  fi
  echo "Verifying ${digest}"
  cosign verify \
    --certificate-identity "$identity" \
    --certificate-oidc-issuer "$issuer" \
    "$digest" >/dev/null
done

echo "Cosign verification passed for: $services"
