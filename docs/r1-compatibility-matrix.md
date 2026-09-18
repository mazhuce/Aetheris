# R1 兼容矩阵与验收记录

R1 的可审查配置位于 `configs/lakehouse/r1-bom.toml`、`configs/lakehouse/connector-allowlist.toml` 和 `infrastructure/lakehouse/docker-compose.r1.yml`。该 profile 与默认 Aetheris Compose 隔离，使用独立的 MinIO 数据卷和宿主端口。

## 当前状态

| 项目 | 状态 | 证据 |
| --- | --- | --- |
| 组件版本固定 | 已实现 | R1 BOM，禁止 `latest`/`snapshot`/`dev` |
| connector 来源和许可证约束 | 已实现 | allowlist 只接受 Maven Central HTTPS 与 Apache-2.0 |
| connector SHA-256 | 待抓取 | 当前为 `PENDING_CAPTURE`，发布模式会拒绝 |
| 独立对象存储、Catalog、Flink、Trino profile | 已实现配置 | `docker-compose.r1.yml` |
| Flink 写入 Iceberg v2 | 待运行 | 需要容器镜像和 connector JAR 实际验证 |
| Spark/Trino 读取同一张表 | 待运行 | 需要端到端 fixture 与对账输出 |
| 清空后重建一致 | 待运行 | 需要隔离卷恢复演练 |

## 离线检查

```powershell
python scripts/validate-lakehouse-r1.py --mode draft
```

运行时预检默认不启动服务；需要显式提供已替换示例密钥的 `.env.r1`，并传 `-Start` 才会启动隔离项目：

```powershell
Copy-Item configs/lakehouse/.env.r1.example configs/lakehouse/.env.r1
powershell -File scripts/lakehouse-r1-preflight.ps1 -EnvFilePath configs/lakehouse/.env.r1 -Start -RequireRunning
```

该脚本只使用项目名 `aetheris-r1-lakehouse`，不执行停止、删除或卷清理。

发布前必须先补齐摘要并将 BOM/connector 状态改为 `approved`，再执行：

```powershell
python scripts/validate-lakehouse-r1.py --mode release
```

## 运行边界

profile 只允许使用随机的 `R1_MINIO_SECRET_KEY`，不能复用 Aetheris 默认内部令牌或数据库密码。启动前应通过 `--env-file configs/lakehouse/.env.r1` 显式提供变量；不要把该文件提交到仓库。R1 的最终验收仍然是同一张 Iceberg v2 表由 Flink 写入、Spark 与 Trino 读取，并能从空环境重建一致结果。
