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
	@echo "TODO: 安装 Java/Node/Python 依赖"

dev:
	docker compose up -d

test:
	@echo "TODO: 运行测试"

build:
	docker compose build

up:
	docker compose up -d

down:
	docker compose down

logs:
	docker compose logs -f

clean:
	@echo "TODO: 清理构建产物"
