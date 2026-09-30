# R4/R5 湖仓控制面运行验收

这项验收把 R4 的质量与 schema 控制面、R5 的 Iceberg 表/快照/ref 登记从“代码和迁移存在”推进到真实 `metadata-service` 调用。脚本使用容器内签名身份，不把 token 写入报告；每次运行创建带时间戳的临时表，结束时按受限外键顺序清理。

```powershell
pwsh -NoProfile -ExecutionPolicy Bypass -File scripts/r4-r5-control-plane-acceptance.ps1
```

验收内容包括：

- 创建 Iceberg v2 Gold 表，登记连续快照 `1001 -> 1002`，确认当前快照指针推进；
- 创建 `main` branch 与受保护 `release_1` tag；
- 提交 `add_column` schema change，确认计划为 `auto_apply` 且生成单条 `ALTER TABLE`；
- 提交 `rename_column`，确认 `approval_required -> approved` 后才生成可执行计划；
- 提交 `drop_column` 并拒绝审批，确认拒绝后计划接口 fail-closed；
- 提交 `narrow_type` 并隔离，确认进入 `isolated` 且不会生成执行计划；
- 对同一质量规则写入 passed/failed 同时间戳观测，确认 Gold 门禁 fail-closed，再写入新 passed 观测确认恢复；
- 删除 schema-change、maintenance request 后清理临时表，清理失败会让整项验收失败，不会输出假阳性；
- 写出机器可读报告 `artifacts/r4-r5-control-plane/runtime-acceptance-report.json`。
- 同步无敏感摘要 `apps/web/public/acceptance/r4-r5-control-plane.json`，验收页缺失时保持未知，不把旧通过状态当作当前成功。

这项验收证明的是 metadata 控制面契约和状态边界，不等同于真实 Iceberg REST Catalog 提交、Spark maintenance Runner 执行或 Flink 质量事件回写已经在线完成；这些仍需对应 Runner/引擎环境的独立运行验收。

## R5 Spark maintenance Runner 运行门禁

真实 Runner 验收入口为 `scripts/r5-maintenance-runner-acceptance.sh`，当前报告
`artifacts/r5-maintenance-runner/r5-maintenance-runtime-report.json` 为 `passed`。本轮以
Spark 3.5.6、Iceberg 1.7.1、REST Catalog 执行 10 个受控 job：setup、storage estimate、time travel、
rewrite data files、expire snapshots、remove orphan files、rollback、schema change、verify 和 cleanup；
每个 job 都要求 `succeeded`，并记录 artifact SHA-256，报告不包含 secrets。

在 Windows + WSL 环境，如果 R1 端口默认只绑定 Windows loopback，先为本地验收显式设置
`R1_BIND_HOST` 到 WSL 虚拟接口，并把相同地址通过 `AETHERIS_R5_CATALOG_HOST`、
`AETHERIS_R5_S3_HOST` 传给 Runner。R1 Compose 默认值仍为 `127.0.0.1`；验收结束后应恢复默认绑定。
