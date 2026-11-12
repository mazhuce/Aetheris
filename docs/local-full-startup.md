# 本地完整启动说明（全栈）

状态：2026-11-13 00:50 已启动并验证通过。
适用：单机演示 / 联调，不作为生产部署方案。

## 1. 当前访问入口

| 入口 | 地址 | 说明 |
| --- | --- | --- |
| Web 控制台 | http://127.0.0.1:5173 | Nginx 静态站点，`/api` 反代网关 |
| 学习中心 | http://127.0.0.1:5173/learning | 路径、18 个任务（L1-L6）、3 个项目 |
| 内容运营 | http://127.0.0.1:5173/learning/admin | 草稿、审核、发布、MCP 审批、运营分析 |
| MCP 调试台 | http://127.0.0.1:5173/learning/mcp | 11 个工具与长任务调试 |
| 网关 | http://127.0.0.1:18080 | 8080 被本机其他应用占用，改用 18080 |
| PostgreSQL | 127.0.0.1:5433 | 库 `aetheris`，用户 `aetheris` |
| Prometheus | http://127.0.0.1:9090 | 指标采集 |

## 1.1 登录账号

- 用户名：`admin`
- 密码：本机 `.env` 中的 `INITIAL_ADMIN_PASSWORD`（.env 已被 gitignore，不会提交）

> 本地演示密码仅在 `.env` 中生成并保存，本仓库任何已提交文件都不包含明文密码。启动前先复制 `.env.example` 为 `.env` 并设置 `INITIAL_ADMIN_PASSWORD`。

## 2. 本次启动方式

标准 compose 依赖两类外部条件：

1. Docker Hub 的 digest 基础镜像（`eclipse-temurin@sha256:...`、`postgres@sha256:...`）在当前网络不可拉取；
2. 所有业务服务要求 `aetheris.security.*` 的 v2 密钥安装，仓库 compose 未挂载。

因此采用“本地镜像 + 覆盖编排”的方式：

- 用本地 `eclipse-temurin:21-jre` 与已构建 jar 打包 12 个服务镜像（含 HEALTHCHECK 与 `JAVA_TOOL_OPTIONS`）；
- 用本地 `nginx:1.27-alpine` + `apps/web/dist` 打包 web 镜像；
- 用 `scripts/security/GenerateKeys.java` 生成 12 个服务的密钥到 `.secrets.local/`；
- `docker-compose.full-local.yml` 为每个服务挂载密钥并注入 kid；同时把 PG/Redis/Prometheus 指到本地已有 tag。该文件与本机密钥绑定，已加入 `.gitignore`，换环境需重新生成。

启动命令：

```bash
docker compose -f docker-compose.yml -f docker-compose.full-local.yml -p aetheris-full up -d --no-build
```

> 首次启动建议按依赖顺序分批：postgres/redis → auth/system → metadata 等业务服务 → learning/mcp → gateway → web。compose 的 `depends_on` 健康检查会在某个依赖尚未 healthy 时中止整批启动。

停止 / 清理：

```bash
docker compose -f docker-compose.yml -f docker-compose.full-local.yml -p aetheris-full down
# 连同数据卷清理（会删除本地演示数据）：
# docker compose ... down -v
```

## 3. 已验证

- 15 个容器全部运行，除 web 外均有健康检查且为 healthy；
- `admin` 登录成功，`GET /api/learning/paths` 返回 L1-L2 路径；
- `POST /api/learning/labs/l1-valid-orders/start` 创建 `sb_u1` 沙箱；
- 提交标准 SQL 得到 **100 分 / passed**；
- `POST /api/mcp` 的 `initialize`、`tools/list`（8 个工具）、`get_learning_path` 均成功；
- `search_tables` 通过 MCP → 签名工作负载 → metadata 链路返回 `[]`（新库暂无元数据，属预期）。

## 4. 本地化改动与限制

| 项 | 说明 |
| --- | --- |
| 基础镜像 | 未修改各服务 Dockerfile；本地用 jar + 本地 base image 打包，避免 digest 拉取 |
| 密钥 | `.secrets.local/` 由本机生成，仅本地使用；生产必须按运维流程重新生成并挂载 |
| ai-agent | 使用占位 `DEEPSEEK_API_KEY`，服务可启动，AI 对话调用会失败；学习中心的 AI 导师走独立的 `AETHERIS_AI_API_KEY`，未配置时回退规则导师 |
| 数据 | 全新数据卷，仅含迁移种子与教学数据集；业务元数据为空 |
| 网关端口 | 18080，因为本机 8080 被其他应用占用 |
| Web | Nginx 以 UID 101 运行；`read_only` + tmpfs 保持原有限制 |
| 重建 learning/mcp 后 | 容器 IP 会变化，网关连接池可能仍持有旧连接并返回 503；重建后重启 gateway 即可恢复 |
| 重建 gateway 后 | Nginx 启动时解析并缓存 gateway 的 IP；重建 gateway 后需一并重启 web，否则 `/api` 返回 502 |

## 5. 启动时补齐的三处服务白名单

新增 `learning` / `mcp` 后，安全协议库里有三处硬编码清单需要同步，否则会在启动或调用时被拒绝：

1. `ProtocolInstallation.SERVICES`：允许加载/注册的服务名；
2. `PostgresNonceStore.SCHEMAS`：允许的 nonce schema；
3. `AuthV2SecurityConfiguration.CALLERS`：允许调用 `auth.session.check` 的服务。

后续再新增服务时，这三处必须一起改，否则会表现为 “Unknown service installation” 或 “User authentication rejected”。
