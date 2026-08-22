# database

数据库相关脚本与全局参考。

- `migrations/`：跨服务共享的全局 SQL 参考与脚本。

每个微服务自己的版本化迁移放在服务内部，由 Flyway 在启动时自动执行，例如：

```text
services/system/src/main/resources/db/migration/
```
