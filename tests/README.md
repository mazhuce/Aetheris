# tests

单元测试跟随所属模块放置在 `src/test/`。跨模块真实链路由
`scripts/smoke-test.sh` 覆盖，验证认证、权限、数据接入、工作流写入、质量检查与分析查询。

统一执行入口：

```bash
mvn -f pom.xml -s scripts/maven-mirror-settings.xml test
cd apps/web && npm test
```

完整环境启动后执行：

```bash
INITIAL_ADMIN_PASSWORD=... POSTGRES_PASSWORD=... make smoke
```
