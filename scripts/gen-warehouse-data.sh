#!/usr/bin/env bash
#
# Aetheris 数仓分层建库与造数。
#
# 在 WSL 的 Spark Thrift Server（或 HiveServer2）上创建 ods/dwd/dws/ads/dim 五层库，
# 生成带分区、带脏数据、带中文注释的电商域测试数据，并逐层加工到 ADS。
#
# 用法：
#   bash scripts/gen-warehouse-data.sh                  # 默认 wsl 档
#   bash scripts/gen-warehouse-data.sh --scale cluster  # VMware 四节点压测档
#   bash scripts/gen-warehouse-data.sh --steps 3,4,5    # 只重跑指定步骤
#   bash scripts/gen-warehouse-data.sh --dry-run        # 只渲染 SQL，不执行
#
# 规模档位（决定 ODS 行数，DWD/DWS/ADS 由上游派生）：
#   wsl      用户 10 万、商品 1 万、订单约 100 万、明细约 250 万（单机可跑）
#   cluster  用户 100 万、商品 10 万、订单约 1000 万、明细约 2500 万
#   tiny     用户 2 千、商品 500、订单约 1 万（改脚本时快速验证语法用）
#
# 前置条件：先启动 Hive 栈，见 docs/datasource-connectivity.md
#   bash scripts/wsl-hive-test-server.sh start

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SQL_DIR="$SCRIPT_DIR/warehouse"
BEELINE="${AETHERIS_BEELINE:-/usr/local/spark-3.5.6-bin-hadoop3/bin/beeline}"
JDBC_URL="${AETHERIS_JDBC_URL:-jdbc:hive2://127.0.0.1:10000/default}"
# 造数需要向 HDFS 仓库目录写入，因此用 HDFS 仓库的属主身份连接（默认为当前用户）。
# Aetheris 平台侧的数据源仍用 aetheris 账号只读访问 —— 仓库目录对其他用户开放了
# r-x，SELECT 不受影响。写权限与读权限分离是有意为之，与后续只读账号的设计一致。
JDBC_USER="${AETHERIS_JDBC_USER:-$(whoami)}"
RENDER_DIR="${AETHERIS_RENDER_DIR:-/tmp/aetheris-warehouse}"

SCALE="wsl"
STEPS="1,2,3,4,5,6"
DRY_RUN="false"

while [[ $# -gt 0 ]]; do
  case "$1" in
    --scale)   SCALE="$2"; shift 2 ;;
    --steps)   STEPS="$2"; shift 2 ;;
    --dry-run) DRY_RUN="true"; shift ;;
    -h|--help) sed -n '2,30p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'; exit 0 ;;
    *) echo "未知参数：$1（用 --help 查看用法）" >&2; exit 2 ;;
  esac
done

# 各档位的行数与日期范围。订单日均量 × 天数 ≈ 订单总量。
case "$SCALE" in
  tiny)
    USER_ROWS=2000;    PRODUCT_ROWS=500;   ORDER_DAYS=30;  ORDERS_PER_DAY=300 ;;
  wsl)
    USER_ROWS=100000;  PRODUCT_ROWS=10000; ORDER_DAYS=180; ORDERS_PER_DAY=5500 ;;
  cluster)
    USER_ROWS=1000000; PRODUCT_ROWS=100000; ORDER_DAYS=365; ORDERS_PER_DAY=27000 ;;
  *)
    echo "未知规模档位：$SCALE（可选 tiny / wsl / cluster）" >&2; exit 2 ;;
esac

# 订单起始日期：从今天倒推 ORDER_DAYS 天，使数据始终「贴近当前」，
# 这样「上个月」「近 7 天」这类自然语言问题才有对应分区。
ORDER_START_DATE="$(date -d "-${ORDER_DAYS} days" +%Y-%m-%d)"
DIM_DATE_START="$(date -d "-$((ORDER_DAYS + 30)) days" +%Y-%m-%d)"
DIM_DATE_DAYS=$((ORDER_DAYS + 60))

echo "==> 规模档位：$SCALE"
echo "    用户 $USER_ROWS 行，商品 $PRODUCT_ROWS 行"
echo "    订单区间 $ORDER_START_DATE 起 $ORDER_DAYS 天，日均约 $ORDERS_PER_DAY 单"
echo "    JDBC $JDBC_URL"

if [[ ! -x "$BEELINE" ]]; then
  echo "找不到 beeline：$BEELINE" >&2
  echo "可用 AETHERIS_BEELINE 指定路径。" >&2
  exit 1
fi

mkdir -p "$RENDER_DIR"

# 把 SQL 模板里的 @占位符@ 替换为本次运行的实际值。
# 不使用 ${hiveconf:} —— Hive 与 Spark SQL 的变量替换语法不一致，
# 在 shell 侧渲染可以让两种引擎共用同一份模板。
render() {
  local src="$1" dst="$2"
  sed -e "s/@USER_ROWS@/$USER_ROWS/g" \
      -e "s/@PRODUCT_ROWS@/$PRODUCT_ROWS/g" \
      -e "s/@ORDER_START_DATE@/$ORDER_START_DATE/g" \
      -e "s/@ORDER_DAYS@/$ORDER_DAYS/g" \
      -e "s/@ORDERS_PER_DAY@/$ORDERS_PER_DAY/g" \
      -e "s/@DIM_DATE_START@/$DIM_DATE_START/g" \
      -e "s/@DIM_DATE_DAYS@/$DIM_DATE_DAYS/g" \
      "$src" > "$dst"
}

run_step() {
  local no="$1" name="$2" file="$3"
  if [[ ",$STEPS," != *",$no,"* ]]; then
    echo "==> 跳过步骤 $no（$name）"
    return
  fi
  local src="$SQL_DIR/$file"
  local rendered="$RENDER_DIR/$file"
  if [[ ! -f "$src" ]]; then
    echo "缺少 SQL 文件：$src" >&2
    exit 1
  fi
  render "$src" "$rendered"

  if [[ "$DRY_RUN" == "true" ]]; then
    echo "==> [dry-run] 已渲染 $rendered"
    return
  fi

  echo "==> 步骤 $no：$name"
  local started=$SECONDS
  # --silent=true 抑制逐条 SQL 的回显；hiveconf 保证动态分区可用。
  "$BEELINE" -u "$JDBC_URL" -n "$JDBC_USER" \
    --silent=true --showWarnings=false \
    --hiveconf hive.exec.dynamic.partition=true \
    --hiveconf hive.exec.dynamic.partition.mode=nonstrict \
    -f "$rendered"
  echo "    完成，用时 $((SECONDS - started))s"
}

run_step 1 "创建分层库与维度表" 01_layers.sql
run_step 2 "生成 ODS 贴源数据"   02_ods.sql
run_step 3 "加工 DWD 明细层"     03_dwd.sql
run_step 4 "加工 DWS 汇总层"     04_dws.sql
run_step 5 "加工 ADS 应用层"     05_ads.sql
run_step 6 "校验数据质量"        06_validate.sql

echo
echo "==> 全部完成。"
echo "    在 Aetheris 中用 'WSL · Spark Thrift' 预设新建数据源后，"
echo "    执行元数据采集即可看到 ods/dwd/dws/ads/dim 五层库表。"
