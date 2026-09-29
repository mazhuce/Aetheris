# R3 HA 证据新鲜度

R3 HA 报告包含真实 Docker 故障注入证据，但历史通过不能直接替代当前版本回归。`scripts/audit-r3-ha-evidence.py` 默认只接受 24 小时内的通过报告；更早的报告会返回 `manual_review_required`，并标注 `historical_pass_requires_rerun`。

```powershell
py -3 scripts/audit-r3-ha-evidence.py `
  --report artifacts/r3-lifecycle/r3-ha-flink-lifecycle-report.json
```

这条边界避免两种误判：旧版本通过被当成当前通过，以及最新重跑失败被旧报告覆盖。当前版本必须重新执行 `scripts/r3-ha-acceptance.ps1`，生成新的报告后才能进入严格发布门禁。
