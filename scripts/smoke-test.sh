#!/usr/bin/env bash
set -euo pipefail
trap 'echo "Smoke test failed at line $LINENO" >&2' ERR

base_url="${AETHERIS_BASE_URL:-http://localhost:8080}"
admin_username="${INITIAL_ADMIN_USERNAME:-admin}"
admin_password="${INITIAL_ADMIN_PASSWORD:?INITIAL_ADMIN_PASSWORD is required}"
db_user="${POSTGRES_USER:-aetheris}"
db_name="${POSTGRES_DB:-aetheris}"
db_password="${POSTGRES_PASSWORD:?POSTGRES_PASSWORD is required}"

for command_name in curl docker; do
  command -v "$command_name" >/dev/null || { echo "$command_name is required" >&2; exit 1; }
done
# 本地 Compose 不应经过企业 HTTP 代理；所有请求都有硬超时，避免 CI 无限等待。
curl() { command curl --noproxy localhost,127.0.0.1 --connect-timeout 5 --max-time 70 "$@"; }
if command -v python3 >/dev/null && python3 -c 'import json' >/dev/null 2>&1; then
  json_python="python3"
elif command -v python >/dev/null && python -c 'import json' >/dev/null 2>&1; then
  json_python="python"
else
  echo "python3 or python is required" >&2
  exit 1
fi
json_object() { "$json_python" scripts/smoke-json.py object "$@"; }
json_get() { "$json_python" scripts/smoke-json.py get "$1"; }

echo "Checking gateway health"
for attempt in $(seq 1 90); do
  if curl --silent --fail "$base_url/actuator/health" >/dev/null; then break; fi
  if [[ "$attempt" == 90 ]]; then echo "gateway did not become healthy" >&2; exit 1; fi
  sleep 2
done

echo "Authenticating smoke administrator"
login_payload=$(json_object username "$admin_username" password "$admin_password")
admin_login=""
for attempt in $(seq 1 15); do
  if admin_login=$(curl --silent --fail --request POST "$base_url/api/auth/login" \
    --header 'Content-Type: application/json' --data "$login_payload"); then break; fi
  if [[ "$attempt" == 15 ]]; then echo "admin login did not become available" >&2; exit 1; fi
  sleep 2
done
echo "Smoke administrator authenticated"
admin_token=$(json_get token <<<"$admin_login")
admin_auth="Authorization: Bearer $admin_token"

datasource_id=""
ingestion_id=""
quality_id=""
workflow_id=""
dataset_id=""
viewer_id=""

cleanup() {
  set +e
  [[ -n "$dataset_id" ]] && curl --silent --request DELETE "$base_url/api/analytics/datasets/$dataset_id" --header "$admin_auth" >/dev/null
  [[ -n "$quality_id" ]] && curl --silent --request DELETE "$base_url/api/quality/rules/$quality_id" --header "$admin_auth" >/dev/null
  [[ -n "$ingestion_id" ]] && curl --silent --request DELETE "$base_url/api/ingestion/tasks/$ingestion_id" --header "$admin_auth" >/dev/null
  [[ -n "$workflow_id" ]] && curl --silent --request DELETE "$base_url/api/workflow/tasks/$workflow_id" --header "$admin_auth" >/dev/null
  [[ -n "$datasource_id" ]] && curl --silent --request DELETE "$base_url/api/metadata/datasources/$datasource_id" --header "$admin_auth" >/dev/null
  [[ -n "$viewer_id" ]] && curl --silent --request DELETE "$base_url/api/auth/users/$viewer_id" --header "$admin_auth" >/dev/null
  docker compose exec -T postgres psql -U "$db_user" -d "$db_name" \
    -c 'DROP SCHEMA IF EXISTS smoke CASCADE' >/dev/null 2>&1
}
trap cleanup EXIT

echo "Preparing isolated smoke data"
docker compose exec -T postgres psql -v ON_ERROR_STOP=1 -U "$db_user" -d "$db_name" <<'SQL'
DROP SCHEMA IF EXISTS smoke CASCADE;
CREATE SCHEMA smoke;
CREATE TABLE smoke.source_orders (id BIGINT PRIMARY KEY, name TEXT NOT NULL, amount NUMERIC(12,2) NOT NULL);
INSERT INTO smoke.source_orders VALUES (1, 'A', 10.00), (2, 'B', 20.00), (3, 'C', 30.00);
CREATE TABLE smoke.target_orders (id BIGINT PRIMARY KEY, name TEXT NOT NULL, amount NUMERIC(12,2) NOT NULL);
SQL

datasource_payload=$(json_object name smoke-postgres type postgresql host postgres port:int 5432 \
  databaseName "$db_name" username "$db_user" password "$db_password" status active)
datasource=$(curl --silent --fail --request POST "$base_url/api/metadata/datasources" \
  --header "$admin_auth" --header 'Content-Type: application/json' --data "$datasource_payload")
datasource_id=$(json_get id <<<"$datasource")

echo "Verifying ingestion execution"
ingestion_payload=$(json_object name smoke-ingestion sourceType jdbc \
  sourceDatasourceId:int "$datasource_id" targetDatasourceId:int "$datasource_id" \
  sourceSql "SELECT id, name, amount FROM smoke.source_orders ORDER BY id" \
  targetTable smoke.target_orders writeMode replace batchSize:int 2 status enabled)
ingestion=$(curl --silent --fail --request POST "$base_url/api/ingestion/tasks" \
  --header "$admin_auth" --header 'Content-Type: application/json' --data "$ingestion_payload")
ingestion_id=$(json_get id <<<"$ingestion")
ingestion_run=$(curl --silent --fail --request POST "$base_url/api/ingestion/tasks/$ingestion_id/run" --header "$admin_auth")
[[ "$(json_get status <<<"$ingestion_run")" == "success" ]]
[[ "$(json_get rowsWritten <<<"$ingestion_run")" == "3" ]]

echo "Verifying workflow execution"
workflow_payload=$(json_object name smoke-workflow datasourceId:int "$datasource_id" \
  datasourceName smoke-postgres taskSql "INSERT INTO smoke.target_orders (id, name, amount) SELECT 4, 'D', 40.00" \
  targetTable smoke.target_orders timeoutSeconds:int 30 maxRetries:int 0 status enabled)
workflow=$(curl --silent --fail --request POST "$base_url/api/workflow/tasks" \
  --header "$admin_auth" --header 'Content-Type: application/json' --data "$workflow_payload")
workflow_id=$(json_get task.id <<<"$workflow")
workflow_run=$(curl --silent --fail --request POST "$base_url/api/workflow/tasks/$workflow_id/run" \
  --header "$admin_auth" --header 'Content-Type: application/json' --data '{"bizDate":"2026-09-02"}')
[[ "$(json_get status <<<"$workflow_run")" == "success" ]]

echo "Verifying quality execution"
quality_payload=$(json_object name smoke-quality ruleType not_null datasourceId:int "$datasource_id" \
  targetTable smoke.target_orders expression "id IS NOT NULL" threshold:int 0 severity high status enabled)
quality=$(curl --silent --fail --request POST "$base_url/api/quality/rules" \
  --header "$admin_auth" --header 'Content-Type: application/json' --data "$quality_payload")
quality_id=$(json_get id <<<"$quality")
quality_run=$(curl --silent --fail --request POST "$base_url/api/quality/rules/$quality_id/run" --header "$admin_auth")
[[ "$(json_get status <<<"$quality_run")" == "passed" ]]
[[ "$(json_get violationCount <<<"$quality_run")" == "0" ]]

echo "Verifying analytics query"
dataset_payload=$(json_object name smoke-dataset datasourceId:int "$datasource_id" datasourceName smoke-postgres \
  querySql "SELECT COUNT(*) AS row_count FROM smoke.target_orders" refreshSeconds:int 30)
dataset=$(curl --silent --fail --request POST "$base_url/api/analytics/datasets" \
  --header "$admin_auth" --header 'Content-Type: application/json' --data "$dataset_payload")
dataset_id=$(json_get id <<<"$dataset")
dataset_result=$(curl --silent --fail --request POST "$base_url/api/analytics/datasets/$dataset_id/query" --header "$admin_auth")
[[ "$(json_get rows.0.0 <<<"$dataset_result")" == "4" ]]

echo "Verifying viewer authorization"
viewer_payload='{"username":"smoke-viewer","password":"smoke-viewer-password","nickname":"Smoke Viewer","role":"viewer","status":"active"}'
viewer=$(curl --silent --fail --request POST "$base_url/api/auth/users" \
  --header "$admin_auth" --header 'Content-Type: application/json' --data "$viewer_payload")
viewer_id=$(json_get id <<<"$viewer")
viewer_login=$(curl --silent --fail --request POST "$base_url/api/auth/login" \
  --header 'Content-Type: application/json' --data '{"username":"smoke-viewer","password":"smoke-viewer-password"}')
viewer_token=$(json_get token <<<"$viewer_login")
viewer_status=$(curl --silent --output /dev/null --write-out '%{http_code}' --request POST \
  "$base_url/api/workflow/tasks/$workflow_id/run" --header "Authorization: Bearer $viewer_token" \
  --header 'Content-Type: application/json' --data '{}')
[[ "$viewer_status" == "403" ]]

echo "Aetheris smoke test passed"
