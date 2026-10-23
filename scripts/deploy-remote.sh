#!/usr/bin/env bash
set -euo pipefail

# Application images and migration sources come from a signed release manifest.
# Database preparation is explicit; deployment only verifies operator readiness.
project_dir="${AETHERIS_DEPLOY_PATH:-$(pwd)}"
cd -- "$project_dir"
command -v python3 >/dev/null || { echo "python3 is required" >&2; exit 2; }
manifest="${AETHERIS_RELEASE_MANIFEST:?A verified release manifest is required}"
bundle="${AETHERIS_RELEASE_MANIFEST_BUNDLE:?A release signature bundle is required}"
state_file="${AETHERIS_DEPLOY_STATE_FILE:-.aetheris-last-good-image-tag}"
release_state="${AETHERIS_DEPLOY_RELEASE_STATE:-.aetheris-last-good-release.json}"
db_config="${AETHERIS_DB_OPERATOR_CONFIG:-configs/database/private/operator.local.json}"
db_receipt="${AETHERIS_DB_READINESS_RECEIPT:-configs/database/private/readiness.local.json}"
db_binding="${AETHERIS_DB_DEPLOYMENT_BINDING:-configs/database/private/deployment-readiness.local.json}"
base_compose="$PWD/docker-compose.yml"
roles_compose="$PWD/docker-compose.database-roles.yml"
export AETHERIS_IMAGE_PREFIX="${AETHERIS_IMAGE_PREFIX:-ghcr.io/mazhuce/aetheris}"
export AETHERIS_COSIGN_POLICY="${AETHERIS_COSIGN_POLICY:-required}"
case "$AETHERIS_COSIGN_POLICY" in required|disabled) ;; *) echo "Invalid cosign policy" >&2; exit 2 ;; esac

python3 scripts/release-manifest.py validate --manifest "$manifest" --commit "$(git rev-parse HEAD)"
expected_identity="$(python3 scripts/release-manifest.py identity --manifest "$manifest")"
if [[ -n "${AETHERIS_COSIGN_CERT_IDENTITY:-}" && "$AETHERIS_COSIGN_CERT_IDENTITY" != "$expected_identity" ]]; then
  echo "Signature identity differs from the release manifest" >&2
  exit 2
fi
export AETHERIS_COSIGN_CERT_IDENTITY="$expected_identity"
verify_bundle() {
  local file="$1" signature="$2" expected
  expected="$(python3 scripts/release-manifest.py identity --manifest "$file")"
  if [[ "$AETHERIS_COSIGN_POLICY" == required ]]; then
    command -v cosign >/dev/null || { echo "cosign is required" >&2; return 2; }
    cosign verify-blob --bundle "$signature" --certificate-identity "$expected" \
      --certificate-oidc-issuer https://token.actions.githubusercontent.com "$file" >/dev/null
  else
    echo "WARNING: explicitly bypassing release signature checks" >&2
  fi
}
verify_bundle "$manifest" "$bundle"

work_dir="$(mktemp -d /tmp/aetheris-deploy.XXXXXXXXXX)"
cleanup() {
  rm -f -- "$work_dir/candidate.json" "$work_dir/rollback.json" "$work_dir/rollback-manifest.json" "$work_dir/rollback-bundle.json"
  rmdir -- "$work_dir"
}
trap cleanup EXIT
python3 scripts/release-manifest.py override --manifest "$manifest" --output "$work_dir/candidate.json"
export COMPOSE_FILE="$base_compose:$roles_compose:$work_dir/candidate.json"
commit="$(python3 scripts/release-manifest.py commit --manifest "$manifest")"
requested_tag="sha-${commit:0:7}"
export AETHERIS_IMAGE_TAG="$requested_tag"

docker compose config --quiet
verify_database_admission() {
  # Resolved Compose contains secrets: consume it in memory without echo/files.
  docker compose config --format json | python3 scripts/database/deploy_gate.py \
    --manifest "$AETHERIS_RELEASE_MANIFEST" --config "$db_config" \
    --receipt "$db_receipt" --record "$db_binding"
}
verify_database_admission
docker compose pull
bash scripts/verify-image-signatures.sh
candidate_up=1
docker compose up -d --remove-orphans --pull never || candidate_up=0
if (( ! candidate_up )) || ! bash scripts/deploy-healthcheck.sh; then
  echo "Deployment health check failed; attempting verified rollback" >&2
  docker compose ps >&2 || true
  # Container logs can contain credentials; collect them through a separate,
  # access-controlled diagnostics path instead of emitting them into CI output.
  if [[ -f "$release_state" ]]; then
    python3 scripts/release-manifest.py load-state --state "$release_state" \
      --manifest-output "$work_dir/rollback-manifest.json" --bundle-output "$work_dir/rollback-bundle.json"
    verify_bundle "$work_dir/rollback-manifest.json" "$work_dir/rollback-bundle.json"
    python3 scripts/release-manifest.py override --manifest "$work_dir/rollback-manifest.json" --output "$work_dir/rollback.json"
    export AETHERIS_RELEASE_MANIFEST="$work_dir/rollback-manifest.json"
    AETHERIS_COSIGN_CERT_IDENTITY="$(python3 scripts/release-manifest.py identity --manifest "$AETHERIS_RELEASE_MANIFEST")"
    export AETHERIS_COSIGN_CERT_IDENTITY
    export COMPOSE_FILE="$base_compose:$roles_compose:$work_dir/rollback.json"
  else
    echo "No verified last-known-good release is available; manual intervention is required" >&2
    exit 1
  fi
  # No shared-account fallback or automatic database downgrade on rollback.
  verify_database_admission
  docker compose pull
  bash scripts/verify-image-signatures.sh
  rollback_up=1
  docker compose up -d --remove-orphans --pull never || rollback_up=0
  if (( ! rollback_up )) || ! bash scripts/deploy-healthcheck.sh; then
    echo "Rollback health check failed; manual intervention is required" >&2
    exit 1
  fi
  echo "Rollback completed successfully" >&2
  exit 1
fi

# Preserve the exact signed bytes as one atomic public-data state object.
python3 scripts/release-manifest.py save-state --manifest "$manifest" --bundle "$bundle" --output "$release_state"
tmp_state="${state_file}.tmp.$$"
printf '%s\n' "$requested_tag" > "$tmp_state"
mv -f "$tmp_state" "$state_file"
echo "Deployment completed for release commit $commit"
