#!/usr/bin/env bash
set -euo pipefail

ACTION="${1:-start}"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
STATE_DIR="${AETHERIS_SPARK_RUNNER_STATE_DIR:-${XDG_STATE_HOME:-$HOME/.local/state}/aetheris-spark-runner}"
CONFIG_DIR="${XDG_CONFIG_HOME:-$HOME/.config}/aetheris"
TOKEN_FILE="${AETHERIS_SPARK_RUNNER_TOKEN_FILE:-$CONFIG_DIR/spark-runner.token}"
RUNNER_PID_FILE="$STATE_DIR/runner.pid"
RELAY_PID_FILE="$STATE_DIR/relay.pid"
RUNNER_LOG="$STATE_DIR/runner.log"
RELAY_SCRIPT="$STATE_DIR/loopback-relay.py"
SPARK_SUBMIT="${AETHERIS_SPARK_SUBMIT:-/usr/local/spark-3.5.6-bin-hadoop3/bin/spark-submit}"
HIVE_STATE="${AETHERIS_HIVE_RUNTIME_DIR:-${XDG_STATE_HOME:-$HOME/.local/state}/aetheris-hive}"
PORT="${AETHERIS_SPARK_RUNNER_PORT:-18081}"
RELAY_HOST="${AETHERIS_SPARK_RUNNER_RELAY_HOST:-$(hostname -I | awk '{print $1}')}"

is_owned_process() {
  local pid_file="$1" expected="$2" pid command
  [[ -f "$pid_file" ]] || return 1
  pid="$(cat "$pid_file")"
  [[ "$pid" =~ ^[0-9]+$ ]] || return 1
  command="$(tr '\0' ' ' < "/proc/$pid/cmdline" 2>/dev/null || true)"
  [[ -n "$command" && "$command" == *"$expected"* ]]
}

wait_port() {
  local host="$1" port="$2"
  for _ in $(seq 1 60); do
    if timeout 1 bash -c "</dev/tcp/$host/$port" 2>/dev/null; then return 0; fi
    sleep 0.25
  done
  return 1
}

prepare() {
  [[ -x "$SPARK_SUBMIT" ]] || { echo "spark-submit not found: $SPARK_SUBMIT" >&2; return 1; }
  [[ -f "$PROJECT_DIR/scripts/wsl-spark-job-runner.py" ]] || { echo "runner source missing" >&2; return 1; }
  [[ -d "$PROJECT_DIR/spark/approved" ]] || { echo "approved artifact directory missing" >&2; return 1; }
  [[ -f "$TOKEN_FILE" ]] || { echo "runner token missing; pipe it to '$0 install-token'" >&2; return 1; }
  [[ "$(wc -c < "$TOKEN_FILE")" -ge 33 ]] || { echo "runner token must be at least 32 bytes" >&2; return 1; }
  mkdir -p "$STATE_DIR/jobs"
  chmod 700 "$STATE_DIR" "$STATE_DIR/jobs" "$CONFIG_DIR"
  chmod 600 "$TOKEN_FILE"
  cat > "$RELAY_SCRIPT" <<'PY'
import socket
import sys
import threading

listen_host, port = sys.argv[1], int(sys.argv[2])

def copy(source, target):
    try:
        while data := source.recv(65536):
            target.sendall(data)
    except OSError:
        pass
    finally:
        try:
            target.shutdown(socket.SHUT_WR)
        except OSError:
            pass

with socket.socket() as listener:
    listener.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    listener.bind((listen_host, port))
    listener.listen()
    while True:
        client, _ = listener.accept()
        try:
            target = socket.create_connection(("127.0.0.1", port), timeout=5)
        except OSError:
            client.close()
            continue
        threading.Thread(target=copy, args=(client, target), daemon=True).start()
        threading.Thread(target=copy, args=(target, client), daemon=True).start()
PY
  chmod 700 "$RELAY_SCRIPT"
}

install_token() {
  if [[ -t 0 ]]; then
    echo "refusing interactive token input; pipe a generated token on stdin" >&2
    return 1
  fi
  local token
  IFS= read -r token
  [[ "${#token}" -ge 32 ]] || { echo "runner token must be at least 32 characters" >&2; return 1; }
  mkdir -p "$CONFIG_DIR"
  umask 077
  printf '%s\n' "$token" > "$TOKEN_FILE"
  echo "runner token installed with mode 600"
}

start() {
  prepare
  if ! is_owned_process "$RUNNER_PID_FILE" "wsl-spark-job-runner.py"; then
    nohup env \
      AETHERIS_RUNNER_HADOOP_CONF_DIR="$HIVE_STATE/hdfs-conf" \
      AETHERIS_RUNNER_HIVE_CONF_DIR="$HIVE_STATE/hive-conf" \
      python3 "$PROJECT_DIR/scripts/wsl-spark-job-runner.py" \
      --listen 127.0.0.1 --port "$PORT" \
      --artifact-root "$PROJECT_DIR/spark/approved" \
      --state-root "$STATE_DIR/jobs" \
      --token-file "$TOKEN_FILE" \
      --spark-submit "$SPARK_SUBMIT" \
      --max-concurrency 1 --max-log-bytes 10485760 \
      >> "$RUNNER_LOG" 2>&1 < /dev/null &
    echo $! > "$RUNNER_PID_FILE"
  fi
  wait_port 127.0.0.1 "$PORT" || { tail -40 "$RUNNER_LOG"; return 1; }
  if [[ "$RELAY_HOST" != "127.0.0.1" ]] && ! is_owned_process "$RELAY_PID_FILE" "$RELAY_SCRIPT"; then
    nohup python3 "$RELAY_SCRIPT" "$RELAY_HOST" "$PORT" >> "$RUNNER_LOG" 2>&1 < /dev/null &
    echo $! > "$RELAY_PID_FILE"
  fi
  wait_port "$RELAY_HOST" "$PORT" || { tail -40 "$RUNNER_LOG"; return 1; }
  echo "Spark runner ready on loopback; relay $RELAY_HOST:$PORT"
}

stop() {
  local pid_file expected pid
  for entry in "$RELAY_PID_FILE:$RELAY_SCRIPT" "$RUNNER_PID_FILE:wsl-spark-job-runner.py"; do
    pid_file="${entry%%:*}"
    expected="${entry#*:}"
    if is_owned_process "$pid_file" "$expected"; then
      pid="$(cat "$pid_file")"
      kill "$pid"
      for _ in $(seq 1 40); do kill -0 "$pid" 2>/dev/null || break; sleep 0.1; done
    fi
    rm -f "$pid_file"
  done
  echo "Spark runner stopped"
}

status() {
  if is_owned_process "$RUNNER_PID_FILE" "wsl-spark-job-runner.py"; then
    echo "runner: running (PID $(cat "$RUNNER_PID_FILE"))"
  else
    echo "runner: stopped"
  fi
  if is_owned_process "$RELAY_PID_FILE" "$RELAY_SCRIPT"; then
    echo "relay: running ($RELAY_HOST:$PORT, PID $(cat "$RELAY_PID_FILE"))"
  else
    echo "relay: stopped"
  fi
}

health() {
  [[ -f "$TOKEN_FILE" ]] || { echo "runner: unavailable (token missing)"; return 1; }
  local token
  token="$(cat "$TOKEN_FILE")"
  curl --fail --silent --show-error --max-time 3 \
    -H "X-Aetheris-Runner-Token: $token" "http://127.0.0.1:$PORT/health"
  echo
}

case "$ACTION" in
  install-token) install_token ;;
  start) start ;;
  stop) stop ;;
  restart) stop; start ;;
  status) status ;;
  health) health ;;
  *) echo "Usage: $0 {install-token|start|stop|restart|status|health}" >&2; exit 2 ;;
esac
