# 数据源与计算引擎的出站契约

metadata 的真实 JDBC 连接、workflow 的 Flink SQL Gateway / JobManager 与 Spark Runner 在请求前检查最终主机。默认允许回环、RFC1918 与 IPv6 ULA；部署时应通过 `AETHERIS_OUTBOUND_ALLOWED_CIDRS` 收窄至实际数据网络。公网数据源必须通过该配置或精确的 `AETHERIS_OUTBOUND_ALLOWED_HOSTS` 明确授权，不支持通配符。

解析出的全部 IPv4/IPv6 地址必须通过检查；混合安全/危险结果整体拒绝。地址解析最多四个并发任务、每次等待两秒，不使用无限任务队列。未指定、link-local、multicast、IPv4 保留高段，以及云平台特殊地址 `100.100.100.200`、`168.63.129.16`、`fd00:ec2::254` 始终拒绝。云元数据常见的 `169.254.169.254` 属于 link-local，也不能被允许清单覆盖。拒绝 IPv4 缩写/八进制/整数形式、IPv6 zone、URL 凭据、查询参数和片段。

JDBC 校验最终 URL 而非展示用 host；SQL Server `serverName` 等能够替换已校验主机的连接参数被拒绝。客户端扩展类、代理、启动 SQL 等继续由参数允许清单拒绝。嵌入式源的部署开关另行控制。

HTTP 使用不跟随重定向的 JDK 客户端，并明确拒绝 3xx。Flink 每次 HTTP 请求重新检查目标；单次 REST 响应有超时，异步操作仍按原任务期限轮询。Spark Runner 除网络允许清单外，还要求来源为配置默认地址或 `AETHERIS_SPARK_RUNNER_ALLOWED_ORIGINS` 中的精确 origin，防止把全局 Runner token 发给任意内网服务。Runner 客户端缓存上限 64。

应用检查与 JDBC/HTTP 驱动实际建连可能各自解析 DNS。预检查不能消除 DNS rebinding、恶意 DNS 或业务服务器转接带来的全部风险。部署必须使用受控解析器及网络出站规则，限制容器到获批服务的 IP/端口；应用允许清单不能替代网络隔离。当前未修改现有主机防火墙，也未将这一残余边界标记为验收通过。

单元覆盖危险地址、混合解析、公网显式授权与 URL 参数旁路；实际回环 HTTP 测试证明 Flink 合法返回可用，Flink/Runner 重定向不会到达目标。所有测试使用临时服务器或解析替身，不请求真实云元数据。
