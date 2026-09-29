# R3 Flink HA 验收边界

R3 HA 使用独立 Compose project `aetheris-r3-ha` 和 `docker-compose.r3-ha.yml`，不会复用或重置普通 R1 project 的容器与卷。HA overlay 提供 ZooKeeper、主/备 JobManager、共享 S3 HA 存储和隔离的 loopback 端口：

| 组件 | 端口 |
| --- | ---: |
| 主 JobManager REST | 18184 |
| 备用 JobManager REST | 18185 |
| SQL Gateway | 18186 |
| ZooKeeper | 12181 |

## 运行入口

Docker Desktop Linux engine 可用时，从仓库根目录执行：

```powershell
pwsh -NoProfile -ExecutionPolicy Bypass -File scripts/r3-ha-acceptance.ps1 `
  -ResetSource -Confirm:$false
```

入口会复用已有的 R3 streaming lifecycle runner，但固定使用 HA overlay 和主/备 REST endpoint，并在结束后调用 `scripts/validate-r3-ha-report.py` 做 fail-closed 校验。报告默认写入：

`artifacts/r3-ha/r3-ha-lifecycle-report.json`

## 通过条件

报告必须同时证明：

1. streaming submit、checkpoint、pause/savepoint、resume、replacement SQL upgrade 均成功；
2. TaskManager 网络中断已注入并恢复，且恢复后产生新的 checkpoint；
3. JobManager 故障探测得到远端不可达，`unknownWasNotSuccess=true`；
4. `reconciliation.mode` 明确为 `auto_reconciled` 或 `manual_review_required`，不允许空值或隐式成功；
5. cleanup 完成。

Docker engine 不可用时，入口不得把旧报告当作本次成功证据；此时只能运行离线契约检查：

```powershell
py -3 scripts/validate-r3-ha.py
py -3 scripts/validate-r3-ha-report.py --report artifacts/r3-lifecycle/r3-managed-stream-report.json
```

后一个命令仅用于已有报告的 schema/边界验证；它不会把单实例 R3 报告伪装成 HA 通过，真正 HA 报告必须来自 `scripts/r3-ha-acceptance.ps1`。
