# 数据源连接说明

## WSL Hive 测试环境

Aetheris 的 `metadata-service` 运行在 Docker 中。WSL 动态 IP 是否可达取决于本机网络模式，Windows 回环地址监听的 `localhost:10000` 也不能直接当成容器内的同一地址。应从 metadata 容器验证实际路径。本机临时 relay 链路如下：

```text
metadata-service 容器
  -> 容器可达的 Windows 虚拟网卡地址:10000（临时转发）
  -> WSL 动态 IP:10000
  -> Spark Thrift Server
  -> Hive Metastore（表位置决定数据使用本地文件还是 HDFS）
```

恢复前先检查现有仓库；仅启动未运行服务即可，无须无条件重启：

```powershell
wsl -d Ubuntu -- bash /mnt/c/Users/yafei/RA1NL0VE/Aetheris/scripts/wsl-hive-test-server.sh preflight
wsl -d Ubuntu -- bash /mnt/c/Users/yafei/RA1NL0VE/Aetheris/scripts/wsl-hive-test-server.sh start
pwsh ./scripts/windows-wsl-hive-relay.ps1 start
```

状态与停止命令：

```powershell
wsl -d Ubuntu -- bash /mnt/c/Users/yafei/RA1NL0VE/Aetheris/scripts/wsl-hive-test-server.sh status
pwsh ./scripts/windows-wsl-hive-relay.ps1 status
wsl -d Ubuntu -- bash /mnt/c/Users/yafei/RA1NL0VE/Aetheris/scripts/wsl-hive-test-server.sh health
pwsh ./scripts/windows-wsl-hive-relay.ps1 health
pwsh ./scripts/windows-wsl-hive-relay.ps1 stop
```

Compose 默认使用 `172.31.0.0/16` 网段，避开 Windows 常见的 WSL/Hyper-V 网段冲突；如通过 `AETHERIS_DOCKER_SUBNET` 覆盖，必须选择与宿主机和 WSL 均不重叠的私有网段。

新增数据源时可使用页面中的 `WSL · Spark Thrift` 预设，当前参数为：

- 类型：`Spark Thrift Server`
- 主机：填写 relay 启动输出的监听地址
- 端口：`10000`
- 数据库：`default`
- 用户名：按用途填写平台数据源账号；当前本地写入任务使用 `yafei`

临时转发不写入 Windows 持久网络规则。Windows 或 WSL 重启后应重新执行启动命令；如果虚拟网卡地址变化，以脚本启动输出的监听地址为准，并同步修改数据源主机。

脚本优先选择 Windows WSL 虚拟网卡，找不到时回退 Default Switch；这不代表 Docker 一定能连接它。若容器探测不通，先核对虚拟网卡路由，可用 `-ListenAddress` 显式指定已经从容器验证可达的本机地址；不要为了测试开放全部网卡或关闭防火墙。`health` 会分别报告 relay 监听和 WSL 目标端口，并检查地址是否已变化；指定了自定义监听地址时，health 也传入同一参数。

恢复脚本在启动前验证已配置的 Derby Metastore 和 HDFS `current/VERSION`。检查失败应追查真实数据目录，不能通过新建空库或格式化来绕过。新默认日志/PID 目录为 `${XDG_STATE_HOME:-$HOME/.local/state}/aetheris-hive`，保留重启后的诊断证据；原环境若还运行在 `/tmp/aetheris-hive`，先用 `AETHERIS_HIVE_RUNTIME_DIR=/tmp/aetheris-hive` 管理它，避免混用 PID 目录。可以显式设置 `AETHERIS_HIVE_RUNTIME_DIR`。日志需要由运维按磁盘容量归档；本轮未配置无人值守日志轮转或系统自启。

WSL 的 `health` 是完整本机测试栈的 TCP 依赖检查，并不证明元数据、SQL 或权限可用。Spark 的默认 warehouse 是 `file:///usr/local/hive/warehouse`；表可以有不同 LOCATION，HDFS 端口可达不能证明所有表都在 HDFS。最终验收使用[平台 Spark 专项脚本](spark-sql-smoke.md)。

当前本地 WSL 测试栈未配置 Kerberos。Spark Thrift 启动脚本显式设置 `hive.server2.enable.doAs=false`，统一以启动服务的 `yafei` 身份访问 HDFS，避免无认证模式下缓存的 Iceberg catalog 在不同 JDBC 用户之间混用文件系统身份。平台层面的读写范围仍由 Aetheris 数据源配置和任务授权控制；生产集群应使用 Kerberos 与受控代理用户配置。

Windows 上直接使用 Beeline 或其他 JDBC 客户端时，可以使用 `localhost:10000`；该地址不适用于 Docker 中的 `metadata-service`。

## VMware 四节点集群

当前 VMware NAT 网段为 `192.168.30.0/24`：

- Windows 宿主机在 VMware NAT 网段的地址：`192.168.30.1`
- 集群主节点：`192.168.30.201`
- 其他节点：`192.168.30.202`、`192.168.30.203`、`192.168.30.204`

Windows 和 Aetheris 访问集群时，应填写实际运行 HiveServer2 的虚拟机地址，当前主节点应为 `192.168.30.201`，不能填写 `192.168.30.1`。`192.168.30.1` 是虚拟机访问 Windows 宿主机时使用的地址。

当前四台节点的 SSH `22` 均可达，主节点的 HDFS、YARN 和 Hive Metastore 端口可达，但四台节点的 HiveServer2 `10000` 均未监听。需要在主节点启动 HiveServer2，并确认它监听 `0.0.0.0:10000` 后，才能使用页面中的 `VMware · HiveServer2` 预设执行 JDBC 测试。
