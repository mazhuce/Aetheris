#!/usr/bin/env bash
set -euo pipefail

policy="${AETHERIS_COSIGN_POLICY:-required}"
case "$policy" in
  required) ;;
  disabled)
    echo "WARNING: image signature verification explicitly disabled (AETHERIS_COSIGN_POLICY=disabled)." >&2
    exit 0
    ;;
  *) echo "AETHERIS_COSIGN_POLICY must be required or disabled" >&2; exit 2 ;;
esac
command -v docker >/dev/null || { echo "docker is required" >&2; exit 2; }
command -v cosign >/dev/null || { echo "cosign is required" >&2; exit 2; }
prefix="${AETHERIS_IMAGE_PREFIX:-ghcr.io/mazhuce/aetheris}"
tag="${AETHERIS_IMAGE_TAG:-latest}"
identity="${AETHERIS_COSIGN_CERT_IDENTITY:-https://github.com/mazhuce/Aetheris/.github/workflows/build.yml@refs/heads/main}"
issuer="${AETHERIS_COSIGN_CERT_OIDC_ISSUER:-https://token.actions.githubusercontent.com}"
services="${AETHERIS_IMAGE_SERVICES:-system metadata serving quality ingestion governance workflow ai-agent auth gateway web}"
manifest="${AETHERIS_RELEASE_MANIFEST:-}"
if [[ -n "$manifest" ]]; then
  command -v python3 >/dev/null || { echo "python3 is required" >&2; exit 2; }
  python3 scripts/release-manifest.py validate --manifest "$manifest" >/dev/null
  # The complete manifest always requires all application images.
  services="system metadata serving quality ingestion governance workflow ai-agent auth gateway web"
fi
read -r -a service_list <<< "$services"
for service in "${service_list[@]}"; do
  if [[ -n "$manifest" ]]; then
    digest="$(python3 scripts/release-manifest.py image --manifest "$manifest" --service "$service")"
    local_digests="$(docker image inspect --format '{{range .RepoDigests}}{{println .}}{{end}}' "$digest")"
    if ! grep -Fx -- "$digest" <<< "$local_digests" >/dev/null; then
      echo "Pulled image does not contain the release manifest digest" >&2
      exit 1
    fi
  else
    image="${prefix}-${service}:${tag}"
    digest="$(docker image inspect --format '{{range .RepoDigests}}{{println .}}{{end}}' "$image" 2>/dev/null \
      | awk -v prefix="${prefix}-${service}@sha256:" 'index($0, prefix) == 1 { print; exit }')"
    if [[ -z "$digest" ]]; then echo "No registry digest found; refusing tag-only verification" >&2; exit 1; fi
  fi
  echo "Verifying $digest"
  cosign verify --certificate-identity "$identity" --certificate-oidc-issuer "$issuer" "$digest" >/dev/null
done
echo "Cosign verification passed for: $services"