#!/usr/bin/env bash
set -euo pipefail

# Real R5 acceptance: workflow-shaped runner submission, Spark 3.5.6,
# Iceberg 1.7.1 REST Catalog, and the isolated R1 MinIO object store.
repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
env_file="$repo_root/configs/lakehouse/.env.r1"
if [[ ! -f "$env_file" ]]; then
  echo "missing $env_file" >&2
  exit 2
fi
# shellcheck disable=SC1090
set -a
source "$env_file"
set +a

# Keep local Spark driver networking deterministic on WSL/Linux. Callers can
# override this for a VM/host-side interface when the runner needs one.
export SPARK_LOCAL_IP="${SPARK_LOCAL_IP:-127.0.0.1}"

report_dir="$repo_root/artifacts/r5-maintenance-runner"
artifact_dir="$report_dir/approved"
state_dir="$report_dir/state"
report_file="$report_dir/r5-maintenance-runtime-report.json"
mkdir -p "$artifact_dir" "$state_dir"
chmod 700 "$state_dir"
rm -f "$report_file" "$state_dir"/runner.log "$state_dir"/runner.pid
rm -rf "$state_dir/jobs"

# Spark distributions may ship a different Iceberg runtime.  Use a hard-link
# copy for this acceptance only and remove that bundled jar, so the pinned
# repository 1.7.1 runtime is the sole Iceberg Spark implementation.
spark_home="/tmp/aetheris-spark-3.5.6-r5"
if [[ ! -x "$spark_home/bin/spark-submit" ]]; then
  rm -rf "$spark_home"
  cp -a /usr/local/spark-3.5.6-bin-hadoop3 "$spark_home"
  rm -f "$spark_home/jars/iceberg-spark-runtime-3.5_2.12-1.9.1.jar"
fi
spark_submit="$spark_home/bin/spark-submit"
# WSL/VM runners may need the host-side virtual interface while the default
# remains loopback for local Linux execution.  The host values are explicit so
# a runner never silently discovers or broadens a network route.
r5_catalog_host="${AETHERIS_R5_CATALOG_HOST:-127.0.0.1}"
r5_s3_host="${AETHERIS_R5_S3_HOST:-127.0.0.1}"
catalog_uri="http://${r5_catalog_host}:${R1_CATALOG_PORT:-18181}"
s3_endpoint="http://${r5_s3_host}:${R1_MINIO_PORT:-19000}"
# Spark 3.5.6 bundles Hadoop 3.3.4 client API/runtime.  The Iceberg
# DeleteOrphanFiles action used by this acceptance requires the Hadoop 3.4
# prefetch API, so remove only those two bundled client jars in this isolated
# runner copy and add the pinned, separately captured compatibility set below.
rm -f "$spark_home"/jars/hadoop-client-api-3.3.4.jar \
      "$spark_home"/jars/hadoop-client-runtime-3.3.4.jar
runner_hadoop_dir="$repo_root/infrastructure/lakehouse/connectors"
for required_jar in \
  hadoop-common-3.4.0.jar \
  hadoop-client-api-3.4.0.jar \
  hadoop-client-runtime-3.4.0.jar \
  hadoop-aws-3.4.0.jar \
  woodstox-core-6.4.0.jar \
  stax2-api-4.2.1.jar \
  commons-configuration2-2.8.0.jar \
  re2j-1.7.jar \
  s3-transfer-manager-2.23.19.jar; do
  if [[ ! -f "$runner_hadoop_dir/$required_jar" ]]; then
    echo "missing Runner compatibility jar: $runner_hadoop_dir/$required_jar" >&2
    exit 2
  fi
done
# SparkSubmit itself resolves Hadoop classes before --jars is applied.  Put the
# same captured compatibility jars into this isolated Spark copy's launcher
# classpath as well; the repository/runtime baseline is untouched.
cp "$runner_hadoop_dir/hadoop-common-3.4.0.jar" "$spark_home/jars/hadoop-common-3.4.0.jar"
cp "$runner_hadoop_dir/hadoop-client-api-3.4.0.jar" "$spark_home/jars/hadoop-client-api-3.4.0.jar"
cp "$runner_hadoop_dir/hadoop-client-runtime-3.4.0.jar" "$spark_home/jars/hadoop-client-runtime-3.4.0.jar"
cp "$runner_hadoop_dir/hadoop-aws-3.4.0.jar" "$spark_home/jars/hadoop-aws-3.4.0.jar"
cp "$runner_hadoop_dir/woodstox-core-6.4.0.jar" "$spark_home/jars/woodstox-core-6.4.0.jar"
cp "$runner_hadoop_dir/stax2-api-4.2.1.jar" "$spark_home/jars/stax2-api-4.2.1.jar"
cp "$runner_hadoop_dir/commons-configuration2-2.8.0.jar" "$spark_home/jars/commons-configuration2-2.8.0.jar"
cp "$runner_hadoop_dir/re2j-1.7.jar" "$spark_home/jars/re2j-1.7.jar"
cp "$runner_hadoop_dir/iceberg-aws-bundle-1.7.1.jar" "$spark_home/jars/iceberg-aws-bundle-1.7.1.jar"
cp "$runner_hadoop_dir/s3-transfer-manager-2.23.19.jar" "$spark_home/jars/s3-transfer-manager-2.23.19.jar"
spark_jars="$repo_root/infrastructure/lakehouse/connectors/iceberg-spark-runtime-3.5_2.12-1.7.1.jar,$repo_root/infrastructure/lakehouse/connectors/iceberg-aws-bundle-1.7.1.jar,$repo_root/infrastructure/lakehouse/connectors/hadoop-common-3.4.0.jar,$repo_root/infrastructure/lakehouse/connectors/hadoop-client-api-3.4.0.jar,$repo_root/infrastructure/lakehouse/connectors/hadoop-client-runtime-3.4.0.jar,$repo_root/infrastructure/lakehouse/connectors/hadoop-aws-3.4.0.jar,$repo_root/infrastructure/lakehouse/connectors/woodstox-core-6.4.0.jar,$repo_root/infrastructure/lakehouse/connectors/stax2-api-4.2.1.jar,$repo_root/infrastructure/lakehouse/connectors/commons-configuration2-2.8.0.jar,$repo_root/infrastructure/lakehouse/connectors/re2j-1.7.jar,$repo_root/infrastructure/lakehouse/connectors/s3-transfer-manager-2.23.19.jar,$repo_root/infrastructure/lakehouse/connectors/aws-java-sdk-bundle-1.12.262.jar"
if [[ ! -x "$spark_submit" ]]; then
  echo "missing Spark submit: $spark_submit" >&2
  exit 2
fi

credentials_file="$state_dir/aws-credentials"
umask 077
cat > "$credentials_file" <<EOF
[default]
aws_access_key_id=$R1_MINIO_ACCESS_KEY
aws_secret_access_key=$R1_MINIO_SECRET_KEY
EOF
chmod 600 "$credentials_file"
token_file="$state_dir/runner-token"
openssl rand -hex 32 > "$token_file"
runner_token="$(tr -d '\r\n' < "$token_file")"

export AETHERIS_RUNNER_SPARK_CATALOG_MODE=rest
export AETHERIS_RUNNER_CATALOG_NAME=iceberg
export AETHERIS_RUNNER_CATALOG_URI="$catalog_uri"
export AETHERIS_RUNNER_CATALOG_WAREHOUSE=s3://warehouse/
export AETHERIS_RUNNER_S3_ENDPOINT="$s3_endpoint"
export AETHERIS_RUNNER_S3_REGION=us-east-1
export AETHERIS_RUNNER_SPARK_JARS="$spark_jars"
export AWS_PROFILE=default
export AWS_SHARED_CREDENTIALS_FILE="$credentials_file"
export AWS_ACCESS_KEY_ID="$R1_MINIO_ACCESS_KEY"
export AWS_SECRET_ACCESS_KEY="$R1_MINIO_SECRET_KEY"
export AWS_REGION=us-east-1
export AWS_DEFAULT_REGION=us-east-1

cp "$repo_root/spark/approved/aetheris_lakehouse_maintenance.py" "$artifact_dir/aetheris_lakehouse_maintenance.py"
cp "$repo_root/spark/approved/aetheris_r5_acceptance_setup.py" "$artifact_dir/aetheris_r5_acceptance_setup.py"
cp "$repo_root/spark/approved/aetheris_r5_acceptance_verify.py" "$artifact_dir/aetheris_r5_acceptance_verify.py"
cp "$repo_root/spark/approved/aetheris_r5_acceptance_cleanup.py" "$artifact_dir/aetheris_r5_acceptance_cleanup.py"

runner_pid=""
cleanup_runner() {
  if [[ -n "$runner_pid" ]] && kill -0 "$runner_pid" 2>/dev/null; then
    kill "$runner_pid" 2>/dev/null || true
    wait "$runner_pid" 2>/dev/null || true
  fi
  rm -f "$credentials_file" "$token_file"
}
trap cleanup_runner EXIT

python3 "$repo_root/scripts/wsl-spark-job-runner.py" \
  --listen 127.0.0.1 --port 18082 \
  --artifact-root "$artifact_dir" --state-root "$state_dir/jobs" \
  --token-file "$token_file" --spark-submit "$spark_submit" \
  --max-concurrency 1 --default-timeout 300 > "$state_dir/runner.log" 2>&1 &
runner_pid=$!
for _ in $(seq 1 30); do
  if curl -fsS -H "X-Aetheris-Runner-Token: $runner_token" http://127.0.0.1:18082/health >/dev/null; then break; fi
  sleep 1
done
curl -fsS -H "X-Aetheris-Runner-Token: $runner_token" http://127.0.0.1:18082/health >/dev/null

sha256_file() { sha256sum "$1" | awk '{print $1}'; }
upload_artifact() {
  local key="$1" file="$2" digest body
  digest="$(sha256_file "$file")"
  body="$state_dir/upload-$key.json"
  python3 - "$file" "$digest" > "$body" <<'PY'
import base64, json, pathlib, sys
path, digest = sys.argv[1:]
print(json.dumps({"sha256": digest, "contentBase64": base64.b64encode(pathlib.Path(path).read_bytes()).decode("ascii")}))
PY
  curl -fsS -X POST -H "X-Aetheris-Runner-Token: $runner_token" -H 'Content-Type: application/json' \
    --data-binary "@$body" "http://127.0.0.1:18082/v1/artifacts/$key" >/dev/null
  printf '%s' "$digest"
}

submit_job() {
  local key request_key attempt_id request
  key="$1"
  request_key="$2"
  attempt_id="$3"
  request="$state_dir/request-$request_key.json"
  shift 3
  python3 - "$key" "$request_key" "$attempt_id" "$@" > "$request" <<'PY'
import json, sys
key, request_key, attempt_id, mode = sys.argv[1:5]
body = {
    "requestKey": request_key,
    "artifactKey": key,
    "artifactDigest": sys.argv[5],
    "artifactType": "pyspark",
    "parameters": {"bizDate": "2026-09-20", "attemptId": attempt_id},
    "resources": {"cores": 1, "driverMemoryMb": 1024, "timeoutSeconds": 300},
}
if mode == "maintenance":
    body["parameters"]["maintenance"] = {"operation": sys.argv[6], "statement": sys.argv[7]}
elif mode == "schema":
    body["parameters"]["schemaChange"] = {"operation": sys.argv[6], "statement": sys.argv[7]}
print(json.dumps(body))
PY
  curl -fsS -X POST -H "X-Aetheris-Runner-Token: $runner_token" -H 'Content-Type: application/json' \
    --data-binary "@$request" http://127.0.0.1:18082/v1/jobs
}

job_status() {
  curl -fsS -H "X-Aetheris-Runner-Token: $runner_token" "http://127.0.0.1:18082/v1/jobs/$1"
}

wait_job() {
  local job_id="$1" status body
  for _ in $(seq 1 180); do
    body="$(job_status "$job_id")"
    status="$(python3 -c 'import json,sys; print(json.load(sys.stdin).get("status", "unknown"))' <<<"$body")"
    case "$status" in
      succeeded|failed|cancelled|timed_out|unknown) printf '%s' "$body"; return 0 ;;
    esac
    sleep 2
  done
  echo "job $job_id timed out while polling" >&2
  return 1
}

setup_digest="$(upload_artifact aetheris_r5_acceptance_setup.py "$artifact_dir/aetheris_r5_acceptance_setup.py")"
maintenance_digest="$(upload_artifact aetheris_lakehouse_maintenance.py "$artifact_dir/aetheris_lakehouse_maintenance.py")"
verify_digest="$(upload_artifact aetheris_r5_acceptance_verify.py "$artifact_dir/aetheris_r5_acceptance_verify.py")"
cleanup_digest="$(upload_artifact aetheris_r5_acceptance_cleanup.py "$artifact_dir/aetheris_r5_acceptance_cleanup.py")"

setup_response="$(submit_job aetheris_r5_acceptance_setup.py r5-setup-20260920 1 normal "$setup_digest")"
setup_id="$(python3 -c 'import json,sys; print(json.load(sys.stdin)["id"])' <<<"$setup_response")"
setup_result="$(wait_job "$setup_id")"
setup_status="$(python3 -c 'import json,sys; print(json.load(sys.stdin).get("status"))' <<<"$setup_result")"
if [[ "$setup_status" != succeeded ]]; then
  echo "setup failed: $setup_result" >&2
  exit 1
fi
first_snapshot="$(python3 -c 'import json,sys; r=json.load(sys.stdin).get("result",{}); print(r.get("snapshotIds", [""])[0])' <<<"$setup_result")"
if [[ -z "$first_snapshot" ]]; then
  echo "setup did not return a snapshot id" >&2
  exit 1
fi

storage_statement="SELECT COUNT(*) AS file_count, COALESCE(SUM(file_size_in_bytes), 0) AS total_bytes FROM iceberg.acceptance.r5_runner_acceptance.files"
storage_response="$(submit_job aetheris_lakehouse_maintenance.py r5-storage-20260920 2 maintenance "$maintenance_digest" storage_estimate "$storage_statement")"
storage_id="$(python3 -c 'import json,sys; print(json.load(sys.stdin)["id"])' <<<"$storage_response")"
storage_result="$(wait_job "$storage_id")"

travel_statement="SELECT COUNT(*) AS row_count FROM iceberg.acceptance.r5_runner_acceptance VERSION AS OF $first_snapshot"
travel_response="$(submit_job aetheris_lakehouse_maintenance.py r5-travel-20260920 3 maintenance "$maintenance_digest" time_travel "$travel_statement")"
travel_id="$(python3 -c 'import json,sys; print(json.load(sys.stdin)["id"])' <<<"$travel_response")"
travel_result="$(wait_job "$travel_id")"

rewrite_statement="CALL iceberg.system.rewrite_data_files(table => 'acceptance.r5_runner_acceptance')"
rewrite_response="$(submit_job aetheris_lakehouse_maintenance.py r5-rewrite-20260920 4 maintenance "$maintenance_digest" rewrite_data_files "$rewrite_statement")"
rewrite_id="$(python3 -c 'import json,sys; print(json.load(sys.stdin)["id"])' <<<"$rewrite_response")"
rewrite_result="$(wait_job "$rewrite_id")"

expire_statement="CALL iceberg.system.expire_snapshots(table => 'acceptance.r5_runner_acceptance', older_than => TIMESTAMP '2026-08-21 00:00:00')"
expire_response="$(submit_job aetheris_lakehouse_maintenance.py r5-expire-20260920 5 maintenance "$maintenance_digest" expire_snapshots "$expire_statement")"
expire_id="$(python3 -c 'import json,sys; print(json.load(sys.stdin)["id"])' <<<"$expire_response")"
expire_result="$(wait_job "$expire_id")"

orphan_statement="CALL iceberg.system.remove_orphan_files(table => 'acceptance.r5_runner_acceptance', older_than => TIMESTAMP '2026-08-21 00:00:00')"
orphan_response="$(submit_job aetheris_lakehouse_maintenance.py r5-orphan-20260920 6 maintenance "$maintenance_digest" remove_orphan_files "$orphan_statement")"
orphan_id="$(python3 -c 'import json,sys; print(json.load(sys.stdin)["id"])' <<<"$orphan_response")"
orphan_result="$(wait_job "$orphan_id")"

rollback_statement="CALL iceberg.system.rollback_to_snapshot('acceptance.r5_runner_acceptance', $first_snapshot)"
rollback_response="$(submit_job aetheris_lakehouse_maintenance.py r5-rollback-20260920 7 maintenance "$maintenance_digest" rollback "$rollback_statement")"
rollback_id="$(python3 -c 'import json,sys; print(json.load(sys.stdin)["id"])' <<<"$rollback_response")"
rollback_result="$(wait_job "$rollback_id")"

schema_statement='ALTER TABLE `iceberg`.`acceptance`.`r5_runner_acceptance` ADD COLUMN `ingested_at` TIMESTAMP'
schema_response="$(submit_job aetheris_lakehouse_maintenance.py r5-schema-20260920 8 schema "$maintenance_digest" add_column "$schema_statement")"
schema_id="$(python3 -c 'import json,sys; print(json.load(sys.stdin)["id"])' <<<"$schema_response")"
schema_result="$(wait_job "$schema_id")"

verify_response="$(submit_job aetheris_r5_acceptance_verify.py r5-verify-20260920 9 normal "$verify_digest")"
verify_id="$(python3 -c 'import json,sys; print(json.load(sys.stdin)["id"])' <<<"$verify_response")"
verify_result="$(wait_job "$verify_id")"

cleanup_response="$(submit_job aetheris_r5_acceptance_cleanup.py r5-cleanup-20260920 10 normal "$cleanup_digest")"
cleanup_id="$(python3 -c 'import json,sys; print(json.load(sys.stdin)["id"])' <<<"$cleanup_response")"
cleanup_result="$(wait_job "$cleanup_id")"

python3 - "$report_file" "$state_dir/jobs" "$maintenance_digest" <<'PY'
import json, pathlib, sys
report_path, jobs_root, digest = sys.argv[1:]
states = {}
for path in pathlib.Path(jobs_root).glob("*/state.json"):
    state = json.loads(path.read_text(encoding="utf-8"))
    states[state["requestKey"]] = state
def state(key):
    return states.get(key, {"requestKey": key, "status": "unknown", "errorMessage": "missing state"})
phases = {
    "setup": state("r5-setup-20260920"),
    "storageEstimate": state("r5-storage-20260920"),
    "timeTravel": state("r5-travel-20260920"),
    "rewriteDataFiles": state("r5-rewrite-20260920"),
    "expireSnapshots": state("r5-expire-20260920"),
    "removeOrphanFiles": state("r5-orphan-20260920"),
    "rollback": state("r5-rollback-20260920"),
    "schemaChange": state("r5-schema-20260920"),
    "verify": state("r5-verify-20260920"),
    "cleanup": state("r5-cleanup-20260920"),
}
passed = all(item.get("status") == "succeeded" for item in phases.values()) and phases["verify"].get("result", {}).get("hasIngestedAt") is True
report = {
    "schemaVersion": 1,
    "gate": "R5-maintenance-runner-runtime-gate",
    "status": "passed" if passed else "failed",
    "sparkVersion": "3.5.6",
    "icebergRuntime": "1.7.1",
    "catalogMode": "rest",
    "phases": phases,
    "artifactSha256": digest,
    "secretsIncluded": False,
}
pathlib.Path(report_path).write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
print(json.dumps({"status": report["status"], "report": report_path}))
if not passed:
    raise SystemExit(1)
PY
