# Changelog

本文件记录项目的重要变更，建议遵循 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.0.0/) 与语义化版本。

## [Unreleased]

### Added

- 初始化 monorepo 目录结构与基础文件。
- 添加 `.gitignore`、`.editorconfig`、`Makefile`、`docker-compose.yml` 与 CI 占位工作流。
- 端到端最小闭环：`system` 服务 + API 网关 + 前端控制台 + PostgreSQL。
- 前端重构为 React 18 + Ant Design 5 企业级控制台。
- 元数据服务（数据源管理）与前端数据源管理页面。
- Docker 构建切换国内镜像源，打通一键构建。
- 每服务独立 schema 隔离、网关 CORS、前端按路由分包。
