#!/usr/bin/env bash
set -euo pipefail

# Run from the checked-out deployment directory after git pull.  The script
# keeps the last known-good image tag and automatically restores it if the new
# stack fails its container and gateway health checks.

project_dir="${AETHERIS_DEPLOY_PATH:-$(pwd)}"
cd -- "$project_dir"
state_file="${AETHERIS_DEPLOY_STATE_FILE:-.aetheris-last-good-image-tag}"
requested_tag="${AETHERIS_IMAGE_TAG:-}"
if [[ -z "$requested_tag" ]]; then
  requested_tag="sha-$(git rev-parse --short=7 HEAD)"
fi
if [[ ! "$requested_tag" =~ ^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$ ]]; then
  echo "Invalid AETHERIS_IMAGE_TAG" >&2
  exit 2
fi

previous_tag=""
if [[ -f "$state_file" ]]; then
  previous_tag="$(head -n 1 "$state_file" | tr -d '\r\n')"
fi
if [[ -n "$previous_tag" && ! "$previous_tag" =~ ^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$ ]]; then
  echo "Ignoring invalid previous deployment state" >&2
  previous_tag=""
fi

# On older installations the state file may not exist.  Recover the currently
# running gateway tag so a failed first upgraded deployment can still roll back.
if [[ -z "$previous_tag" ]]; then
  gateway_id="$(docker compose ps -q gateway 2>/dev/null | head -n 1 || true)"
  current_image=""
  if [[ -n "$gateway_id" ]]; then
    current_image="$(docker inspect --format '{{.Config.Image}}' "$gateway_id" 2>/dev/null || true)"
  fi
  candidate="${current_image##*:}"
  if [[ "$candidate" =~ ^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$ ]]; then
    previous_tag="$candidate"
  fi
fi

export AETHERIS_IMAGE_PREFIX="${AETHERIS_IMAGE_PREFIX:-ghcr.io/mazhuce/aetheris}"
export AETHERIS_IMAGE_TAG="$requested_tag"
export AETHERIS_COSIGN_POLICY="${AETHERIS_COSIGN_POLICY:-required}"

echo "Pulling ${AETHERIS_IMAGE_PREFIX}-*:${AETHERIS_IMAGE_TAG}"
docker compose pull
bash scripts/verify-image-signatures.sh
candidate_up=1
docker compose up -d --remove-orphans || candidate_up=0

if (( ! candidate_up )) || ! bash scripts/deploy-healthcheck.sh; then
  echo "Deployment health check failed; collecting logs before rollback" >&2
  docker compose ps >&2 || true
  docker compose logs --no-color --tail=120 >&2 || true
  if [[ -z "$previous_tag" || "$previous_tag" == "$requested_tag" ]]; then
    echo "No distinct last-known-good image tag is available; leaving failed deployment for inspection" >&2
    exit 1
  fi

  echo "Rolling back to image tag ${previous_tag}" >&2
  export AETHERIS_IMAGE_TAG="$previous_tag"
  rollback_up=1
  docker compose up -d --remove-orphans || rollback_up=0
  if (( ! rollback_up )) || ! bash scripts/deploy-healthcheck.sh; then
    echo "Rollback health check failed; manual intervention is required" >&2
    docker compose ps >&2 || true
    exit 1
  fi
  echo "Rollback completed successfully" >&2
  exit 1
fi

tmp_state="${state_file}.tmp.$$"
printf '%s\n' "$requested_tag" > "$tmp_state"
mv -f "$tmp_state" "$state_file"
docker image prune -f
echo "Deployment completed: ${AETHERIS_IMAGE_PREFIX}-*:${requested_tag}"
