#!/usr/bin/env bash
set -euo pipefail

timeout_seconds="${AETHERIS_DEPLOY_HEALTH_TIMEOUT_SECONDS:-180}"
poll_seconds="${AETHERIS_DEPLOY_HEALTH_POLL_SECONDS:-5}"
gateway_port="${GATEWAY_PORT:-8080}"
deadline=$((SECONDS + timeout_seconds))

command -v docker >/dev/null || { echo "docker is required" >&2; exit 2; }

services="$(docker compose config --services)"
while (( SECONDS < deadline )); do
  all_healthy=1
  pending=()
  while IFS= read -r service; do
    [[ -z "$service" ]] && continue
    ids="$(docker compose ps -q "$service" 2>/dev/null || true)"
    if [[ -z "$ids" ]]; then
      all_healthy=0
      pending+=("${service}=missing")
      continue
    fi
    while IFS= read -r id; do
      [[ -z "$id" ]] && continue
      state="$(docker inspect --format '{{.State.Status}}' "$id" 2>/dev/null || echo missing)"
      health="$(docker inspect --format '{{if .State.Health}}{{.State.Health.Status}}{{else}}none{{end}}' "$id" 2>/dev/null || echo missing)"
      if [[ "$state" != running || ( "$health" != healthy && "$health" != none ) ]]; then
        all_healthy=0
        pending+=("${service}=${state}/${health}")
      fi
    done <<< "$ids"
  done <<< "$services"

  if (( all_healthy )); then
    if ! command -v curl >/dev/null; then
      echo "curl is unavailable; relying on container health checks"
      exit 0
    fi
    if curl --noproxy '*' --silent --show-error --fail \
      --connect-timeout 3 --max-time 5 "http://127.0.0.1:${gateway_port}/actuator/health" >/dev/null; then
      echo "Deployment health check passed (${#pending[@]} pending services)."
      exit 0
    fi
    pending+=("gateway=http-unavailable")
  fi
  echo "Waiting for healthy services: ${pending[*]:-unknown}"
  sleep "$poll_seconds"
done

echo "Deployment health check failed after ${timeout_seconds}s" >&2
docker compose ps >&2 || true
exit 1
