# scripts

开发与运维工具脚本（初始化、数据同步、部署辅助等）。

| 脚本 | 用途 |
| --- | --- |
| `wsl-hive-test-server.sh` | 在 WSL 中启停 HDFS + Hive Metastore + Spark Thrift Server 测试栈 |
| `windows-wsl-hive-relay.ps1` | 建立 Windows 到 WSL 的临时端口转发，供 Docker 内的服务访问 Hive |
| `gen-warehouse-data.sh` | 建立 ods/dwd/dws/ads/dim 五层库并生成电商域测试数据，详见 [warehouse-layers.md](../docs/warehouse-layers.md) |
| `warehouse/*.sql` | 上述脚本调用的分层 DDL 与加工 SQL，按序号执行 |
| `maven-mirror-settings.xml` | Maven 国内镜像源配置 |
