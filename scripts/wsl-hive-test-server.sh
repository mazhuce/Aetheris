#!/usr/bin/env bash
set -euo pipefail

HADOOP_HOME="${HADOOP_HOME:-/usr/local/hadoop}"
HIVE_HOME="${HIVE_HOME:-/usr/local/hive}"
JAVA_HOME="${JAVA_HOME:-/usr/lib/jvm/java-8-openjdk-amd64}"
SPARK_HOME="${SPARK_HOME:-/usr/local/spark-3.5.6-bin-hadoop3}"
ENGINE="${AETHERIS_HIVE_ENGINE:-spark}"
RUNTIME_DIR="${AETHERIS_HIVE_RUNTIME_DIR:-/tmp/aetheris-hive}"
HADOOP_CONF_DIR="$RUNTIME_DIR/hadoop-conf"
METASTORE_PID_FILE="$RUNTIME_DIR/metastore.pid"
HIVESERVER2_PID_FILE="$RUNTIME_DIR/hiveserver2.pid"
NAMENODE_PID_FILE="$RUNTIME_DIR/namenode.pid"
DATANODE_PID_FILE="$RUNTIME_DIR/datanode.pid"

prepare() {
  mkdir -p "$HADOOP_CONF_DIR"
  cp "$HADOOP_HOME"/etc/hadoop/*.xml "$HADOOP_CONF_DIR"/
  sed -i 's#hdfs://localhost:9000#file:///#g' "$HADOOP_CONF_DIR/core-site.xml"
  printf 'export JAVA_HOME=%s\n' "$JAVA_HOME" > "$HADOOP_CONF_DIR/hadoop-env.sh"
  chmod 600 "$HADOOP_CONF_DIR/hadoop-env.sh"
}

is_running() {
  local pid_file="$1"
  [[ -f "$pid_file" ]] && kill -0 "$(cat "$pid_file")" 2>/dev/null
}

wait_for_port() {
  local port="$1"
  local attempts=30
  while (( attempts > 0 )); do
    if (echo > "/dev/tcp/127.0.0.1/$port") 2>/dev/null; then
      return 0
    fi
    sleep 1
    attempts=$((attempts - 1))
  done
  return 1
}

start_service() {
  local name="$1"
  local pid_file="$2"
  local log_file="$3"
  shift 3
  if is_running "$pid_file"; then
    echo "$name already running (PID $(cat "$pid_file"))"
    return
  fi
  nohup env \
    JAVA_HOME="$JAVA_HOME" \
    HADOOP_HOME="$HADOOP_HOME" \
    HIVE_HOME="$HIVE_HOME" \
    HADOOP_CONF_DIR="$HADOOP_CONF_DIR" \
    PATH="$JAVA_HOME/bin:$HADOOP_HOME/bin:$HIVE_HOME/bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin" \
    "$@" > "$log_file" 2>&1 < /dev/null &
  echo $! > "$pid_file"
}

start_hdfs_service() {
  local name="$1"
  local pid_file="$2"
  local log_file="$3"
  local role="$4"
  if is_running "$pid_file"; then
    echo "$name already running (PID $(cat "$pid_file"))"
    return
  fi
  nohup env \
    JAVA_HOME=/usr/lib/jvm/java-11-openjdk-amd64 \
    HADOOP_HOME="$HADOOP_HOME" \
    HADOOP_CONF_DIR="$HADOOP_HOME/etc/hadoop" \
    PATH="/usr/lib/jvm/java-11-openjdk-amd64/bin:$HADOOP_HOME/bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin" \
    "$HADOOP_HOME/bin/hdfs" "$role" > "$log_file" 2>&1 < /dev/null &
  echo $! > "$pid_file"
}

start_hdfs() {
  if ! wait_for_port 9000; then
    start_hdfs_service "HDFS NameNode" "$NAMENODE_PID_FILE" "$RUNTIME_DIR/namenode.log" namenode
    wait_for_port 9000 || { tail -80 "$RUNTIME_DIR/namenode.log"; exit 1; }
  fi
  if ! wait_for_port 9864; then
    start_hdfs_service "HDFS DataNode" "$DATANODE_PID_FILE" "$RUNTIME_DIR/datanode.log" datanode
    wait_for_port 9864 || { tail -80 "$RUNTIME_DIR/datanode.log"; exit 1; }
  fi
}

start() {
  prepare
  start_hdfs
  start_service "Hive Metastore" "$METASTORE_PID_FILE" "$RUNTIME_DIR/metastore.log" \
    "$HIVE_HOME/bin/hive" --service metastore
  wait_for_port 9083 || { tail -80 "$RUNTIME_DIR/metastore.log"; exit 1; }
  if [[ "$ENGINE" == "spark" ]]; then
    env \
      JAVA_HOME=/usr/lib/jvm/java-11-openjdk-amd64 \
      HADOOP_CONF_DIR="$HADOOP_CONF_DIR" \
      HIVE_CONF_DIR="$HIVE_HOME/conf" \
      SPARK_PID_DIR="$RUNTIME_DIR" \
      SPARK_LOG_DIR="$RUNTIME_DIR" \
      SPARK_IDENT_STRING=aetheris \
      "$SPARK_HOME/sbin/start-thriftserver.sh" \
      --master 'local[2]' \
      --driver-memory 1g \
      --conf spark.sql.catalogImplementation=hive \
      --conf spark.sql.warehouse.dir=file:///usr/local/hive/warehouse \
      --hiveconf hive.server2.thrift.bind.host=0.0.0.0 \
      --hiveconf hive.server2.thrift.port=10000 \
      --hiveconf hive.metastore.uris=thrift://127.0.0.1:9083
  else
    start_service "HiveServer2" "$HIVESERVER2_PID_FILE" "$RUNTIME_DIR/hiveserver2.log" \
      "$HIVE_HOME/bin/hiveserver2" \
      --hiveconf hive.server2.thrift.bind.host=0.0.0.0 \
      --hiveconf hive.server2.thrift.port=10000 \
      --hiveconf fs.defaultFS=file:/// \
      --hiveconf hive.exec.scratchdir=/tmp/hive \
      --hiveconf hive.notification.event.poll.interval=0 \
      --hiveconf hive.server2.tez.initialize.default.sessions=false \
      --hiveconf hive.metastore.warehouse.dir=file:///usr/local/hive/warehouse
  fi
  if ! wait_for_port 10000; then
    echo "JDBC service did not open port 10000; recent logs:"
    find "$RUNTIME_DIR" -maxdepth 1 -type f \( -name '*.log' -o -name '*.out' \) | while read -r log_file; do
      echo "==> $log_file"
      tail -40 "$log_file"
    done
    exit 1
  fi
  echo "$ENGINE thrift service ready on 0.0.0.0:10000"
}

stop() {
  if [[ -x "$SPARK_HOME/sbin/stop-thriftserver.sh" ]]; then
    env \
      JAVA_HOME=/usr/lib/jvm/java-11-openjdk-amd64 \
      SPARK_PID_DIR="$RUNTIME_DIR" \
      SPARK_LOG_DIR="$RUNTIME_DIR" \
      SPARK_IDENT_STRING=aetheris \
      "$SPARK_HOME/sbin/stop-thriftserver.sh" >/dev/null 2>&1 || true
  fi
  for pid_file in "$HIVESERVER2_PID_FILE" "$METASTORE_PID_FILE" "$DATANODE_PID_FILE" "$NAMENODE_PID_FILE"; do
    if is_running "$pid_file"; then
      kill "$(cat "$pid_file")"
    fi
    rm -f "$pid_file"
  done
}

status() {
  ss -lntp 2>/dev/null | grep -E ':(9000|9083|9864|9870|10000)' || true
}

case "${1:-start}" in
  start) start ;;
  stop) stop ;;
  restart) stop; start ;;
  status) status ;;
  *) echo "Usage: $0 {start|stop|restart|status}"; exit 2 ;;
esac
