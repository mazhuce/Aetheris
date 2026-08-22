.DEFAULT_GOAL := help

.PHONY: help init dev test build up down logs clean

help:
	@echo "make init    安装依赖"
	@echo "make dev     启动本地开发环境"
	@echo "make test    运行测试"
	@echo "make build   构建镜像"
	@echo "make up      启动服务"
	@echo "make down    停止服务"
	@echo "make logs    查看日志"
	@echo "make clean   清理构建产物"

init:
	docker compose up --build -d

dev:
	docker compose up -d

test:
	mvn -f services/system/pom.xml -s scripts/maven-mirror-settings.xml test
	mvn -f services/metadata/pom.xml -s scripts/maven-mirror-settings.xml test
	mvn -f apps/gateway/pom.xml -s scripts/maven-mirror-settings.xml test

build:
	docker compose build

up:
	docker compose up -d

down:
	docker compose down

logs:
	docker compose logs -f

clean:
	mvn -f services/system/pom.xml -s scripts/maven-mirror-settings.xml clean
	mvn -f services/metadata/pom.xml -s scripts/maven-mirror-settings.xml clean
	mvn -f apps/gateway/pom.xml -s scripts/maven-mirror-settings.xml clean
