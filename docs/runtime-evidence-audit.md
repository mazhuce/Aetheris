# R1/R2 运行证据审计

仅检查 JSON 的 `status=passed` 不足以证明硬门槛。`scripts/validate-runtime-evidence.py` 还会检查：

- R1 是否真的 fresh rebuild、重置 source fixture；
- Flink 提交操作是否全部 `FINISHED` 并有 Job ID；
- Spark 与 Trino 是否与期望 checksum 完全一致；
- BOM、connector allowlist、Compose、table contract 和 fixture 等证据是否都有 SHA-256；
- R2 PostgreSQL/MySQL 是否都有快照、变更、checkpoint、重启恢复、去重和 expected/actual 行一致性。

执行：

```powershell
py -3 scripts/validate-runtime-evidence.py `
  --r1 artifacts/r1-empty-fixture/acceptance-manifest.json `
  --r2 artifacts/r2-cdc/r2-cdc-acceptance-report.json
```
