#!/usr/bin/env bash
set -euo pipefail

HADOOP_HOME="${HADOOP_HOME:-/usr/local/hadoop}"
HIVE_HOME="${HIVE_HOME:-/usr/local/hive}"
JAVA_HOME="${JAVA_HOME:-/usr/lib/jvm/java-8-openjdk-amd64}"
SPARK_HOME="${SPARK_HOME:-/usr/local/spark-3.5.6-bin-hadoop3}"
ENGINE="${AETHERIS_HIVE_ENGINE:-spark}"
# Keep diagnostic evidence across WSL restarts. This is not an autostart policy.
RUNTIME_DIR="${AETHERIS_HIVE_RUNTIME_DIR:-${XDG_STATE_HOME:-$HOME/.local/state}/aetheris-hive}"

# Spark Thrift Server 资源配置。local[N] 模式下 driver 同时充当 executor，
# 造数（百万级订单 + 多分区 Parquet 写入）会把 1g 堆打爆，因此默认给 3g。
# WSL 默认内存约 8g，3g driver 加上 HDFS/Metastore 仍有余量。
SPARK_DRIVER_MEMORY="${AETHERIS_SPARK_DRIVER_MEMORY:-3g}"
SPARK_LOCAL_CORES="${AETHERIS_SPARK_LOCAL_CORES:-2}"
# 默认 200 个 shuffle 分区对单机造数来说过多，会产生大量小文件并抬高开销。
SPARK_SHUFFLE_PARTITIONS="${AETHERIS_SPARK_SHUFFLE_PARTITIONS:-16}"
THRIFT_BIND_HOST="${AETHERIS_THRIFT_BIND_HOST:-127.0.0.1}"
THRIFT_RELAY_HOST="${AETHERIS_THRIFT_RELAY_HOST:-$(hostname -I | awk '{print $1}')}"

HADOOP_CONF_DIR="$RUNTIME_DIR/hadoop-conf"
HDFS_CONF_DIR="$RUNTIME_DIR/hdfs-conf"
RUNTIME_HIVE_CONF_DIR="$RUNTIME_DIR/hive-conf"
METASTORE_PID_FILE="$RUNTIME_DIR/metastore.pid"
HIVESERVER2_PID_FILE="$RUNTIME_DIR/hiveserver2.pid"
NAMENODE_PID_FILE="$RUNTIME_DIR/namenode.pid"
DATANODE_PID_FILE="$RUNTIME_DIR/datanode.pid"
THRIFT_RELAY_PID_FILE="$RUNTIME_DIR/thrift-relay.pid"

# Recovery must never silently create a new Derby catalog or format HDFS.
# Parse the last occurrence, matching Hadoop Configuration's override semantics.
preflight() {
  command -v python3 >/dev/null || { echo "python3 is required for safe recovery checks" >&2; return 1; }
  python3 - "$HIVE_HOME/conf/hive-site.xml" "$HADOOP_HOME/etc/hadoop/hdfs-site.xml" <<'PY'
import pathlib
import sys
import xml.etree.ElementTree as ET
from urllib.parse import urlparse, unquote

def properties(path):
    return {p.findtext('name'): (p.findtext('value') or '').strip()
            for p in ET.parse(path).getroot().findall('property')}

hive, hdfs = map(properties, sys.argv[1:])
url = hive.get('javax.jdo.option.ConnectionURL', '')
if not url:
    sys.exit('Recovery refused: explicit Metastore ConnectionURL is required')
if url.startswith('jdbc:derby:'):
    location = next((s.split('=', 1)[1] for s in url.split(';')
                     if s.startswith('databaseName=')), url[len('jdbc:derby:'):].split(';')[0])
    path = pathlib.Path(location)
    if not path.is_absolute() or not (path / 'service.properties').is_file() or not (path / 'seg0').is_dir():
        sys.exit('Recovery refused: existing absolute Derby catalog not found; do not initialize a replacement')
for key in ('dfs.namenode.name.dir', 'dfs.datanode.data.dir'):
    entries = hdfs.get(key, '').split(',')
    if not entries or not entries[0]:
        sys.exit('Recovery refused: explicit HDFS storage directory is required')
    for entry in entries:
        parsed = urlparse(entry.strip())
        path = pathlib.Path(unquote(parsed.path))
        if parsed.scheme not in ('', 'file') or not path.is_absolute() or not (path / 'current' / 'VERSION').is_file():
            sys.exit('Recovery refused: existing HDFS VERSION missing; no formatting is performed')
print('Existing Metastore and HDFS storage verified; no initialization performed')
PY
}

prepare() {
  mkdir -p "$HADOOP_CONF_DIR" "$HDFS_CONF_DIR" "$RUNTIME_HIVE_CONF_DIR"
  cp "$HADOOP_HOME"/etc/hadoop/*.xml "$HADOOP_CONF_DIR"/
  cp "$HADOOP_HOME"/etc/hadoop/*.xml "$HDFS_CONF_DIR"/
  cp "$HIVE_HOME"/conf/* "$RUNTIME_HIVE_CONF_DIR"/ 2>/dev/null || true
  sed -i 's#hdfs://localhost:9000#file:///#g' "$HADOOP_CONF_DIR/core-site.xml"
  printf 'export JAVA_HOME=%s\n' "$JAVA_HOME" > "$HADOOP_CONF_DIR/hadoop-env.sh"
  chmod 600 "$HADOOP_CONF_DIR/hadoop-env.sh"
  python3 - "$HDFS_CONF_DIR/hdfs-site.xml" <<'PY'
import sys
import xml.etree.ElementTree as ET

path = sys.argv[1]
tree = ET.parse(path)
root = tree.getroot()
overrides = {
    'dfs.namenode.http-address': '127.0.0.1:9870',
    'dfs.datanode.address': '127.0.0.1:9866',
    'dfs.datanode.http.address': '127.0.0.1:9864',
    'dfs.datanode.ipc.address': '127.0.0.1:9867',
}
for name, value in overrides.items():
    prop = ET.SubElement(root, 'property')
    ET.SubElement(prop, 'name').text = name
    ET.SubElement(prop, 'value').text = value
tree.write(path, encoding='utf-8', xml_declaration=True)
PY
  python3 - "$RUNTIME_HIVE_CONF_DIR/hive-site.xml" <<'PY'
import sys
import xml.etree.ElementTree as ET

path = sys.argv[1]
tree = ET.parse(path)
root = tree.getroot()
for prop in list(root.findall('property')):
    if (prop.findtext('name') == 'hive.metastore.uris'):
        root.remove(prop)
prop = ET.SubElement(root, 'property')
ET.SubElement(prop, 'name').text = 'hive.metastore.uris'
ET.SubElement(prop, 'value').text = 'thrift://127.0.0.1:9083'
tree.write(path, encoding='utf-8', xml_declaration=True)
PY
  cat > "$RUNTIME_DIR/aetheris-thrift-relay.py" <<'PY'
import socket
import sys
import threading

listen_host, listen_port, target_host, target_port = sys.argv[1], int(sys.argv[2]), sys.argv[3], int(sys.argv[4])

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
    listener.bind((listen_host, listen_port))
    listener.listen()
    while True:
        client, _ = listener.accept()
        try:
            target = socket.create_connection((target_host, target_port), timeout=10)
        except OSError:
            client.close()
            continue
        threading.Thread(target=copy, args=(client, target), daemon=True).start()
        threading.Thread(target=copy, args=(target, client), daemon=True).start()
PY
  chmod 700 "$RUNTIME_DIR/aetheris-thrift-relay.py"
}

is_running() {
  local pid_file="$1"
  local service_pid command_line expected
  [[ -f "$pid_file" ]] || return 1
  service_pid="$(cat "$pid_file")"
  [[ "$service_pid" =~ ^[0-9]+$ ]] && [[ -r "/proc/$service_pid/cmdline" ]] || return 1
  command_line="$(tr '\0' ' ' < "/proc/$service_pid/cmdline")"
  case "${pid_file##*/}" in
    namenode.pid) expected='org.apache.hadoop.hdfs.server.namenode.NameNode' ;;
    datanode.pid) expected='org.apache.hadoop.hdfs.server.datanode.DataNode' ;;
    metastore.pid) expected='org.apache.hadoop.hive.metastore.HiveMetaStore' ;;
    hiveserver2.pid) expected='org.apache.hive.service.server.HiveServer2' ;;
    thrift-relay.pid) expected='aetheris-thrift-relay.py' ;;
    *) return 1 ;;
  esac
  [[ "$command_line" == *"$expected"* ]] && kill -0 "$service_pid" 2>/dev/null
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

# 等端口彻底释放。stop 用 SIGTERM 异步关停，进程退出前端口仍在监听；
# 若不等待，紧随其后的 start 会因「端口已监听」误判服务健在而跳过启动，
# 等到进程真正退出时就只剩下半个栈（restart 时曾出现 HDFS 未启动）。
wait_for_port_release() {
  local port="$1"
  local attempts=30
  while (( attempts > 0 )); do
    if ! (echo > "/dev/tcp/127.0.0.1/$port") 2>/dev/null; then
      return 0
    fi
    sleep 1
    attempts=$((attempts - 1))
  done
  echo "端口 $port 在等待期内未释放，可能有残留进程占用。" >&2
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
    "$@" >> "$log_file" 2>&1 < /dev/null &
  echo $! > "$pid_file"
}

start_thrift_relay() {
  if systemctl --user is-active --quiet aetheris-thrift-relay.service; then
    echo "WSL Thrift relay already running"
  else
    systemd-run --user --unit=aetheris-thrift-relay \
      --property=Restart=on-failure --property=RestartSec=1s \
      python3 "$RUNTIME_DIR/aetheris-thrift-relay.py" \
      "$THRIFT_RELAY_HOST" 10000 "$THRIFT_BIND_HOST" 10000
  fi
  systemctl --user show aetheris-thrift-relay.service -p MainPID --value > "$THRIFT_RELAY_PID_FILE"
}

start_metastore() {
  if systemctl --user is-active --quiet aetheris-hive-metastore.service; then
    echo "Hive Metastore already running"
  else
    systemctl --user reset-failed aetheris-hive-metastore.service >/dev/null 2>&1 || true
    systemd-run --user --unit=aetheris-hive-metastore \
      --property=Restart=on-failure --property=RestartSec=2s \
      --property=IPAddressDeny=any --property=IPAddressAllow=localhost \
      --setenv=JAVA_HOME="$JAVA_HOME" --setenv=HADOOP_HOME="$HADOOP_HOME" \
      --setenv=HIVE_HOME="$HIVE_HOME" --setenv=HADOOP_CONF_DIR="$HADOOP_CONF_DIR" \
      --setenv=HIVE_CONF_DIR="$RUNTIME_HIVE_CONF_DIR" \
      "$HIVE_HOME/bin/hive" --service metastore -p 9083
  fi
  wait_for_port 9083 || {
    journalctl --user -u aetheris-hive-metastore.service -n 80 --no-pager
    return 1
  }
  systemctl --user show aetheris-hive-metastore.service -p MainPID --value > "$METASTORE_PID_FILE"
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
    HADOOP_CONF_DIR="$HDFS_CONF_DIR" \
    PATH="/usr/lib/jvm/java-11-openjdk-amd64/bin:$HADOOP_HOME/bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin" \
    "$HADOOP_HOME/bin/hdfs" "$role" >> "$log_file" 2>&1 < /dev/null &
  echo $! > "$pid_file"
}

port_is_open() {
  (echo > "/dev/tcp/127.0.0.1/$1") 2>/dev/null
}

start_hdfs() {
  # 用单次探测判断是否已在运行；wait_for_port 会在服务未起时白等 30 秒。
  if ! port_is_open 9000; then
    start_hdfs_service "HDFS NameNode" "$NAMENODE_PID_FILE" "$RUNTIME_DIR/namenode.log" namenode
    wait_for_port 9000 || { tail -80 "$RUNTIME_DIR/namenode.log"; exit 1; }
  fi
  if ! port_is_open 9864; then
    start_hdfs_service "HDFS DataNode" "$DATANODE_PID_FILE" "$RUNTIME_DIR/datanode.log" datanode
    wait_for_port 9864 || { tail -80 "$RUNTIME_DIR/datanode.log"; exit 1; }
  fi
}

start() {
  preflight
  prepare
  start_hdfs
  # Hive 3 cannot bind the standalone Metastore to a specific interface. Run
  # it behind a WSL firewall that permits loopback traffic only; Spark keeps
  # the existing HiveCatalog while WSL/Windows/Docker peers cannot reach 9083.
  start_metastore
  if ! systemctl is-active --quiet aetheris-metastore-firewall.service; then
    echo "Recovery refused: the WSL Metastore firewall service is not active." >&2
    return 1
  fi
  if [[ "$ENGINE" == "spark" ]]; then
    if port_is_open 10000; then
      echo "Spark Thrift Server already running on $THRIFT_BIND_HOST:10000"
    else
      env \
      JAVA_HOME=/usr/lib/jvm/java-11-openjdk-amd64 \
      HADOOP_CONF_DIR="$HADOOP_CONF_DIR" \
      HIVE_CONF_DIR="$RUNTIME_HIVE_CONF_DIR" \
      SPARK_PID_DIR="$RUNTIME_DIR" \
      SPARK_LOG_DIR="$RUNTIME_DIR" \
      SPARK_IDENT_STRING=aetheris \
      "$SPARK_HOME/sbin/start-thriftserver.sh" \
      --master "local[${SPARK_LOCAL_CORES}]" \
      --driver-memory "$SPARK_DRIVER_MEMORY" \
      --conf spark.sql.catalogImplementation=hive \
      --conf spark.sql.catalog.hive_prod=org.apache.iceberg.spark.SparkCatalog \
      --conf spark.sql.catalog.hive_prod.type=hive \
      --conf spark.sql.catalog.hive_prod.uri=thrift://127.0.0.1:9083 \
      --conf spark.sql.catalog.hive_prod.warehouse=hdfs://localhost:9000/user/hive/warehouse \
      --conf spark.sql.defaultCatalog=hive_prod \
      --conf spark.sql.warehouse.dir=file:///usr/local/hive/warehouse \
      --conf spark.sql.shuffle.partitions="$SPARK_SHUFFLE_PARTITIONS" \
      --conf spark.sql.parquet.compression.codec=snappy \
      --hiveconf hive.server2.thrift.bind.host="$THRIFT_BIND_HOST" \
      --hiveconf hive.server2.thrift.port=10000 \
      --hiveconf hive.metastore.uris=thrift://127.0.0.1:9083 \
      --hiveconf hive.server2.enable.doAs=false
    fi
  else
    start_service "HiveServer2" "$HIVESERVER2_PID_FILE" "$RUNTIME_DIR/hiveserver2.log" \
      "$HIVE_HOME/bin/hiveserver2" \
      --hiveconf hive.server2.thrift.bind.host="$THRIFT_BIND_HOST" \
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
  if [[ -n "$THRIFT_RELAY_HOST" && "$THRIFT_RELAY_HOST" != "$THRIFT_BIND_HOST" ]]; then
    start_thrift_relay
  fi
  echo "$ENGINE thrift service ready on $THRIFT_BIND_HOST:10000; Docker relay target $THRIFT_RELAY_HOST:10000"
}

stop() {
  systemctl --user stop aetheris-thrift-relay.service >/dev/null 2>&1 || true
  systemctl --user stop aetheris-hive-metastore.service >/dev/null 2>&1 || true
  if [[ -x "$SPARK_HOME/sbin/stop-thriftserver.sh" ]]; then
    env \
      JAVA_HOME=/usr/lib/jvm/java-11-openjdk-amd64 \
      SPARK_PID_DIR="$RUNTIME_DIR" \
      SPARK_LOG_DIR="$RUNTIME_DIR" \
      SPARK_IDENT_STRING=aetheris \
      "$SPARK_HOME/sbin/stop-thriftserver.sh" >/dev/null 2>&1 || true
  fi
  for pid_file in "$THRIFT_RELAY_PID_FILE" "$HIVESERVER2_PID_FILE" "$METASTORE_PID_FILE" "$DATANODE_PID_FILE" "$NAMENODE_PID_FILE"; do
    if is_running "$pid_file"; then
      kill "$(cat "$pid_file")"
    fi
    rm -f "$pid_file"
  done
  # 等所有端口释放后再返回，保证 restart 中紧随的 start 不会误判服务健在。
  for port in 10000 9083 9864 9000; do
    wait_for_port_release "$port" || true
  done
}

status() {
  echo "Runtime logs: $RUNTIME_DIR"
  ss -lntp 2>/dev/null | grep -E ':(9000|9864|9870|9083|10000)' || true
}

health() {
  local failed=0
  local dependency port
  for dependency in 'HDFS_NameNode:9000' 'HDFS_DataNode:9864' 'Hive_Metastore:9083' 'Spark_SQL_Thrift:10000'; do
    port="${dependency##*:}"
    if port_is_open "$port"; then
      echo "${dependency%:*}: reachable ($port)"
    else
      echo "${dependency%:*}: unavailable ($port)"
      failed=1
    fi
  done
  echo 'TCP checks only; run the platform Spark smoke test to verify JDBC, identity and SQL.'
  return "$failed"
}

case "${1:-start}" in
  start) start ;;
  stop) stop ;;
  restart) stop; start ;;
  status) status ;;
  health) health ;;
  preflight) preflight ;;
  *) echo "Usage: $0 {start|stop|restart|status|health|preflight}"; exit 2 ;;
esac
