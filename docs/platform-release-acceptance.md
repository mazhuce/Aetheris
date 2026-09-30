# 平台一体化发布验收

使用 `scripts/platform-release-acceptance.ps1` 汇总 R1–R5、数据源和 HA 证据：

```powershell
pwsh -NoProfile -ExecutionPolicy Bypass -File scripts/platform-release-acceptance.ps1
```

报告写入 `artifacts/platform-release/platform-release-acceptance-report.json`。它同时运行 R1 release validator、R3 HA Compose 契约和脚本回归，并读取 R1、R2、R3、R4、R5、九类本地真实数据源和 R3 HA 报告；Hive2、SQL Server、Oracle 外部环境样例作为 supplementary evidence 单独记录。

状态含义：

- `passed`：离线契约、必需运行报告、R3 HA、九类本地真实数据源和 Docker 均有通过证据；
- `manual_review_required`：离线与必需运行报告没有失败，但 R3 HA/九类本地运行证据缺失，或 Docker 不可用；外部样例缺失不会伪装成通过，而是保留在 `supplementaryGates`。
- `failed`：离线检查或已有运行报告明确失败。

CI/无 Docker 环境可使用 `-OfflineOnly`，但该模式不会把平台标记为完整通过，也不会绕过真实运行时门槛。
