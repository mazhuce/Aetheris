# PostgreSQL 备份与恢复

业务数据位于 Compose 的 `pgdata` 卷。发布或迁移前必须先导出备份，并把文件复制到异机或对象存储。

## 备份

```powershell
pwsh -NoProfile -File scripts/backup-postgres.ps1 -OutputDir .\backups
```

也可以使用 `make backup BACKUP_DIR=./backups`。脚本使用 `pg_dump -Fc`，默认连接容器 `aetheris-postgres-1`；容器名、数据库名和用户可分别用 `AETHERIS_POSTGRES_CONTAINER`、`POSTGRES_DB`、`POSTGRES_USER` 覆盖。

## 恢复演练

恢复应在隔离的 Compose 项目或临时数据库中进行，确认迁移能够启动且关键表可读：

```powershell
pwsh -NoProfile -File scripts/restore-postgres.ps1 -BackupFile .\backups\aetheris-YYYYMMDD-HHMMSS.dump
```

`-Clean` 只适用于明确批准的空环境恢复，会先清理目标数据库对象。禁止对唯一生产卷直接执行 `docker compose down -v`。

## 最低验收

1. 备份文件大小大于 0 且可由 `pg_restore -l` 列出目录。
2. 在隔离数据库恢复成功，Flyway 启动无失败迁移。
3. `auth_user`、审计日志、任务实例和语义资产等关键表可查询。
4. 恢复文件与生产数据分离保存，并记录时间、操作者和校验结果。
