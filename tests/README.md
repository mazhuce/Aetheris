# tests

本目录用于后续跨模块集成测试与端到端测试。

当前单元测试跟随所属模块放置在 `src/test/java/`，统一执行入口：

```bash
mvn -f pom.xml -s scripts/maven-mirror-settings.xml test
```
