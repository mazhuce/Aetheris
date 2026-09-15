.DEFAULT_GOAL := help

.PHONY: help init dev test smoke observability-smoke backend-build frontend-build build up down logs clean backup restore

MAVEN := mvn -f pom.xml -s scripts/maven-mirror-settings.xml

help:
	@echo "make init    安装依赖"
	@echo "make dev     启动本地开发环境"
	@echo "make test    运行全部后端与前端测试和类型检查"
	@echo "make smoke   对已启动环境运行跨服务冒烟测试"
	@echo "make backend-build  构建全部后端模块"
	@echo "make frontend-build 构建前端"
	@echo "make build   构建镜像"
	@echo "make up      启动服务"
	@echo "make down    停止服务"
	@echo "make logs    查看日志"
	@echo "make clean   清理构建产物"
	@echo "make backup  导出 PostgreSQL 备份（BACKUP_DIR 可覆盖）"
	@echo "make restore BACKUP_FILE=... 恢复 PostgreSQL 备份"
	@echo "make observability-smoke 检查各服务 Prometheus 指标端点"

init:
	docker compose up --build -d

dev:
	docker compose -f docker-compose.yml -f docker-compose.dev.yml up -d --build --no-deps web

test:
	$(MAVEN) test
	cd apps/web && npm test
	cd apps/web && npm run type-check

smoke:
	bash scripts/smoke-test.sh

observability-smoke:
	pwsh -NoProfile -File scripts/observability-smoke.ps1

backend-build:
	$(MAVEN) package

frontend-build:
	cd apps/web && npm run build

build:
	docker compose build

up:
	docker compose up -d

down:
	docker compose down

logs:
	docker compose logs -f

clean:
	$(MAVEN) clean

backup:
	pwsh -NoProfile -File scripts/backup-postgres.ps1 -OutputDir "$${BACKUP_DIR:-./backups}"

restore:
	@test -n "$(BACKUP_FILE)" || (echo "BACKUP_FILE is required"; exit 2)
	pwsh -NoProfile -File scripts/restore-postgres.ps1 -BackupFile "$(BACKUP_FILE)"
