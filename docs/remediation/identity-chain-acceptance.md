# Gateway / auth / system 身份链分段验收

2026-10-18 UTC 11:32:34–11:33:29，run `07eeb0e3ecb94d32ae7c4626d126f302`，**34/34真实 HTTP/PG 检查通过**。这是三个实际应用的分段验证，S08仍未关闭。

报告：`output/audit-identity-chain/run-20261018-v2/report.json`，SHA256 `22b5c59732210aac28f2d821f9f3893df6ffe40d1dafb3dee7abeb3ec7efd117`。303项源码/POM/迁移/辅助工具指纹末尾一致；报告保留三个新构建JAR的摘要和体积、实际Spring PID。先归档125份既有unit日志/报告，再执行真正的clean package，未使用旧target class充当新构建。

## 实际环境与结果

- 新建、仅本机可达的PostgreSQL16和Redis7.4容器，按缓存image ID执行且禁止pull；拒绝远端Docker context与endpoint覆盖。没有连接现有数据库。
- 实际执行105份迁移，九schema应用运行角色隔离校验通过；auth/system使用各自runtime账号启动，运行中禁用Flyway。system V7将permission_event.request_id从64扩到128，实际保存128字符完整拒绝事件1条。
- 新生成临时10个WORKLOAD和Gateway独立USER keypair，只给各应用配置自己的私钥路径，Gateway持USER私钥。公钥表SHA `45423c6ae04ccdb09d06b0d753cb4237b3c53517f8867cd59a3421a0f26c8b12`；未安装、替换或轮换真实密钥。同一Windows账户启动的测试JVM不证明部署文件系统的私钥隔离，实际部署仍需单独只读挂载。
- Java21明确可执行文件直接启动三个实际JAR，禁止HTTP重试参数从JVM启动生效；每个应用256MiB堆、两个可用CPU、DB池上限4，所有监听只绑定127.0.0.1。健康检查及Spring日志PID均匹配实际启动PID。
- 通过真实Gateway完成HttpOnly Cookie登录、admin管理与创建viewer。viewer可读本人profile、不能管理auth/system；伪造旧身份/USER/workload/response头不能提权或替换本人。
- 直连无proof、裸旧头、旧internal token、错误audience均拒绝。对同一真实有效auth USER证明，禁用前本人接口200、禁用后403；重启用后旧AV仍403，重新签当前AV后200。旧Cookie在禁用后401，不把原本无管理权限的403冒充禁用证据。
- 实际持久nonce：auth接收gateway18、system12；system接收gateway5、auth5。system记录5条gateway审计和5条auth权限事件，两个方向均使用已签响应。

本轮普通模块回归先完成Gateway45、auth142、system46；共享protocol160、servlet60单测。隔离工具20项安全测试通过，所有工具测试禁止真实Popen/run，不将其当作应用验收。此前共享库152例Temurin及56单测+2真实Tomcat/PG IT为历史分项，不能宣称新增代码已在那些旧run中执行。

## 清理与证据限制

最终process/container/迁移凭据退休/private keys/无volume五项全部true。UTC11:33:50独立读取Docker/CIM：专属容器0、owned JVM0、私密run目录不存在；证据为同目录`cleanup-independent.json`。临时JAR及提取的四个probe依赖已删除，仅保留脱敏日志与摘要。

本脚本有固定34必需case、5项必需清理、精确6类非空DB计数；源码漂移、空/缺/重复检查、任何失败或清理缺项均不能通过。私密run父目录以原子mkdir认领，只清本次确实创建的已知文件；未知文件和预存同名目录保留并导致不通过。Maven、迁移runner和key helper都走已核实JDK、清理过的环境与自有进程期限。

本结果不证明浏览器多标签页、metadata/serving/AI等其他七服务、API Key新资产链、cron/pipeline/retry固定执行、execution receipt或多副本副作用去重已经完成。没有覆盖容器内应用运行、正式签名release、生产安装/升级、配置指纹绑定的最终八类producer；105份源的新装通过也不替代仍需更新的完整部署gate。旧运行服务未替换。
