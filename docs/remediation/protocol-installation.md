# S08 密钥准备与协调切换

状态：新密钥准备工具和文件加载器已完成本地验证；应用还未启用v2，不存在“只运行生成命令就已修复”的结论。此流程不操作现有数据库、部署或数据源加密主密钥。

## 准备新的独立密钥

使用明确的JDK21路径；工具不调用Windows Oracle javapath启动器。目标必须是`configs/security/private`内一个尚不存在的子目录，父目录应已存在。命令不会覆盖已有文件，也不会自动安装或轮换：

```powershell
$env:JAVA_HOME='C:\Program Files\Java\jdk-21'
python -B scripts/security/installation.py generate --directory configs/security/private/reviewed-installation-20261018
```

JDK原生Ed25519生成10个独立WORKLOAD keypair及Gateway独立USER keypair。目录共13个文件：10份`<service>.workload.pem`、`gateway.user.pem`、`public-registry.json`、`installation.json`。公钥注册表包含11个key，安装清单仅记录kid、文件名、时间及公钥注册表SHA-256，不包含私钥内容。状态固定`generated-not-installed`，保留`requiresPermissionsReview=true`。

生成失败可能留下本次新建目录供检查；不会为了清理失败而删除操作者指定目录。修复原因后使用另一个新目录，不把半成品当作可安装结果。测试在临时新目录实际生成，核对11个公钥互异、USER仅归Gateway、13个文件、重复调用全部原字节不变；临时测试密钥已清理。

POSIX新目录0700、私钥0600；Windows使用父目录继承ACL。安装前必须核对实际账户、容器挂载和备份访问权限；`requiresPermissionsReview`不是ACL已经合格的证明。Git忽略整个`configs/security/private`，现有Docker规则排除`configs/**`；不得把私钥拷进源码、镜像层、报告或公共环境文件。

## 加载与分发边界

`ProtocolInstallation.load(service, registryFile, workloadKid, workloadPrivateFile, userKid, userPrivateFile, clock)`执行严格文件加载、公钥注册、key用途和实际公私钥匹配验证：

| 接收方 | 可分发的文件 | 不可分发的文件 |
|---|---|---|
| Gateway | 公钥注册表、自身WORKLOAD私钥、Gateway USER私钥 | 其他服务WORKLOAD私钥 |
| 单个业务服务 | 公钥注册表、自身WORKLOAD私钥 | Gateway USER私钥、其他服务WORKLOAD私钥 |
| 仅验签组件 | 公钥注册表 | 所有私钥 |

`userKid/userPrivateFile`仅Gateway允许且必需，其余服务必须传null。公钥注册表schemaVersion=1，只有`keys`数组；每项严格包含`kid,purpose,caller,publicKey`。未知/重复字段、未知caller、相同公钥跨用途或caller、坏PEM、缺文件、非regular file、超限内容均失败。文件内容不是远程配置，不能由请求选择路径或覆盖注册表。

每个运行容器仅只读挂载公钥注册表和自己的私钥，安装清单由操作人保管，不将整个私密目录挂进所有容器。现阶段尚未生成可直接启用的Compose安全override；应用bean和数据库nonce/receipt必须完成后再统一接线，避免环境变量看似存在但实际未被代码消费。

`SignedHttpClient`要求JVM启动时同时设置：

```text
-Djdk.httpclient.disableRetryConnect=true
-Djdk.httpclient.redirects.retrylimit=1
```

禁止`jdk.httpclient.enableAllMethodRetry`为true或空值。库检查实际JVM启动参数和当前属性，不能在请求开始后临时setProperty。10个Java应用Dockerfile已声明两个参数，仍需核对部署覆盖值与目标JDK的实际wire测试。

## 统一切换的先决条件

1. 部署方审阅当前公钥表、每服务私钥范围、文件权限和实际镜像，不用公开固定测试向量。
2. 服务端filter、server-fixed route/caller ACL、USER实时会话、持久化nonce、固定定义批准/revision/invocation、执行回查及receipt完成隔离验收。
3. API Key资产窄入口、AI转发和全部内部客户端切到同一协议。用户证明摘要纳入操作签名，不能将两份各自有效的证明自由组合。
4. 审阅存量定义批准清单；停止旧worker和调度，保留unknown对账状态后协调切换。关闭旧HMAC/共享token入口，无混合回退。
5. 新源码应用按新角色启动，重跑普通用户固定执行、撤权、替换字段、两副本并发、响应丢失、API Key、cron/pipeline/retry及清理。

这些是后续实施与验收条件，不表示当前已满足。真实切换按目标约束另行获得具体环境授权。现有数据源主密钥使用AES-GCM保存密码，不能用此Ed25519生成工具替换；主密钥轮换必须另行完成重加密、备份恢复及回滚验证。
