# Changelog

本文件记录项目的重要变更，建议遵循 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.0.0/) 与语义化版本。

## [Unreleased]

### First iteration

- 新增根 Maven 聚合工程，统一构建全部八个后端模块。
- CI 改为构建并测试全部后端模块。
- 新增网关、认证与 AI 会话隔离单元测试。
- AI 会话增加所属用户字段及 Flyway 迁移，只允许所属用户访问。
- 网关覆盖客户端伪造的 `X-User-Name` 请求头。
- 初始管理员改为环境变量配置，首次启动要求至少 12 位密码。
- JWT 密钥不足 32 字节时拒绝启动并给出明确错误。
- 修复登录用户昵称为空时的服务端异常。

### Second iteration

- `auth_user` 增加角色、邮箱和手机号，现有账号兼容迁移为管理员。
- 新增登录账号管理接口，密码仅保存 BCrypt 哈希。
- JWT 增加角色声明，网关实现 `admin` / `viewer` 最小 RBAC。
- 覆盖客户端伪造的用户角色请求头，普通用户的管理写请求返回 403。
- 管理员不能删除、禁用或降级当前账号，系统始终保留至少一个启用管理员。
- 前端用户权限页切换到 Auth 服务，并根据角色保护路由和菜单。

### Added

- 初始化 monorepo 目录结构与基础文件。
- 添加 `.gitignore`、`.editorconfig`、`Makefile`、`docker-compose.yml` 与 CI 占位工作流。
- 端到端最小闭环：`system` 服务 + API 网关 + 前端控制台 + PostgreSQL。
- 前端重构为 React 18 + Ant Design 5 企业级控制台。
- 元数据服务（数据源管理）与前端数据源管理页面。
- Docker 构建切换国内镜像源，打通一键构建。
- 每服务独立 schema 隔离、网关 CORS、前端按路由分包。
