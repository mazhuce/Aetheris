.DEFAULT_GOAL := help

.PHONY: help init dev test backend-build frontend-build build up down logs clean

MAVEN := mvn -f pom.xml -s scripts/maven-mirror-settings.xml

help:
	@echo "make init    安装依赖"
	@echo "make dev     启动本地开发环境"
	@echo "make test    运行全部后端测试和前端类型检查"
	@echo "make backend-build  构建全部后端模块"
	@echo "make frontend-build 构建前端"
	@echo "make build   构建镜像"
	@echo "make up      启动服务"
	@echo "make down    停止服务"
	@echo "make logs    查看日志"
	@echo "make clean   清理构建产物"

init:
	docker compose up --build -d

dev:
	docker compose -f docker-compose.yml -f docker-compose.dev.yml up -d --build --no-deps web

test:
	$(MAVEN) test
	cd apps/web && npm run type-check

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
