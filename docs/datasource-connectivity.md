# 数据源连接说明

## WSL Hive 测试环境

Aetheris 的 `metadata-service` 运行在 Docker 中，不能直接使用 WSL 的动态 IP，也不能使用只在 Windows 回环地址监听的 `localhost:10000`。本机测试链路如下：

```text
metadata-service 容器
  -> Hyper-V Default Switch 地址:10000（临时转发）
  -> WSL 动态 IP:10000
  -> Spark Thrift Server
  -> Hive Metastore
  -> HDFS
```

依次启动完整 WSL 测试栈与临时转发：

```powershell
wsl -d Ubuntu -- bash /mnt/c/Users/yafei/RA1NL0VE/Aetheris/scripts/wsl-hive-test-server.sh restart
pwsh ./scripts/windows-wsl-hive-relay.ps1 start
```

状态与停止命令：

```powershell
wsl -d Ubuntu -- bash /mnt/c/Users/yafei/RA1NL0VE/Aetheris/scripts/wsl-hive-test-server.sh status
pwsh ./scripts/windows-wsl-hive-relay.ps1 status
pwsh ./scripts/windows-wsl-hive-relay.ps1 stop
```

Compose 默认使用 `172.31.0.0/16` 网段，避开 Windows 常见的 WSL/Hyper-V 网段冲突；如通过 `AETHERIS_DOCKER_SUBNET` 覆盖，必须选择与宿主机和 WSL 均不重叠的私有网段。

新增数据源时可使用页面中的 `WSL · Spark Thrift` 预设，当前参数为：

- 类型：`Spark Thrift Server`
- 主机：填写 relay 启动输出的监听地址
- 端口：`10000`
- 数据库：`default`
- 用户名：`aetheris`

临时转发不写入 Windows 持久网络规则。Windows 或 WSL 重启后应重新执行启动命令；如果 Hyper-V Default Switch 地址变化，以脚本启动输出的监听地址为准，并同步修改数据源主机。

Windows 上直接使用 Beeline 或其他 JDBC 客户端时，可以使用 `localhost:10000`；该地址不适用于 Docker 中的 `metadata-service`。

## VMware 四节点集群

当前 VMware NAT 网段为 `192.168.30.0/24`：

- Windows 宿主机在 VMware NAT 网段的地址：`192.168.30.1`
- 集群主节点：`192.168.30.201`
- 其他节点：`192.168.30.202`、`192.168.30.203`、`192.168.30.204`

Windows 和 Aetheris 访问集群时，应填写实际运行 HiveServer2 的虚拟机地址，当前主节点应为 `192.168.30.201`，不能填写 `192.168.30.1`。`192.168.30.1` 是虚拟机访问 Windows 宿主机时使用的地址。

当前四台节点的 SSH `22` 均可达，主节点的 HDFS、YARN 和 Hive Metastore 端口可达，但四台节点的 HiveServer2 `10000` 均未监听。需要在主节点启动 HiveServer2，并确认它监听 `0.0.0.0:10000` 后，才能使用页面中的 `VMware · HiveServer2` 预设执行 JDBC 测试。
