# 本次审查的证据附件

源码基线：`6adaa6e2961e6fdf9c84abc64e1ad2911ab77525`。附件不属于生产服务，不改变业务行为。

## 文件说明

| 文件 | 内容与边界 |
| --- | --- |
| `files.csv` | 全部 1,227 个基线文件的摘要、大小和 UTF-8 扫描标记；不是逐行人工签核 |
| `dependencies.csv` | 421 个测试 classpath 坐标＋1 个打包辅助坐标；区分测试模块与实际生产包模块；公告匹配不是利用证明 |
| `metrics.json` | 隔离 clean verify 的 Surefire 统计、实际包内全部 JAR 映射、路由扫描统计 |
| `maven-osv.json` | OSV 对原 421 个坐标查询的原始聚合结果；附加 jarmode 查询结果见 metrics/CSV |
| `npm-audit.json` | npm 原始 audit 报告；须区分浏览器/Node 和 dev/生产依赖 |
| `contracts.json` | 351 个 HTTP 映射、280 处前端调用及候选风险位置；正则扫描不验证运行语义 |
| `probes.txt` | 调用真实项目类的 SQL、权限、迁移、JDBC URL、同步隔离探针输出 |
| `final-probes.txt` | 真实 Flink health 控制器向自建回环 HTTP 目标请求；原发布选择函数选旧 passed 的输出 |
| `readonly-checks.txt` | 已有运行报告的内容/新鲜度验证、PS7 编码、smoke Base64 检查；不是本次全栈运行报告 |
| `clean-verify.txt` | 从提交导出的隔离源码，全模块 clean verify 原始日志 |
| `AetherisReviewProbe.java` | 使用进程内 H2 和 mock 的复现代码；仅 URL 验证的案例不执行连接副作用 |
| `AetherisWorkflowReviewProbe.java` | 使用自建 127.0.0.1 随机端口服务验证可控出站请求；没有访问真实内网资产 |
| `reproduce.py` | 从指定源码的 Surefire XML 获取 classpath，运行两个 Java 探针及隔离的报告选择函数 |

`probes.txt` 中 javac 中文警告可能有编码显示异常；核心英文断言保留。其他名为 `aetheris-audit-*` 的临时文件来源未核实，未纳入证据。

## 非破坏性复核

建议在单独导出的基线源码目录先构建，不要为了清理 target 停止来源不明的进程：

```powershell
mvn -f pom.xml -s scripts/maven-mirror-settings.xml clean verify
```

然后指定该源码目录（需保留其 `target/surefire-reports`），使用本附件中的探针：

```powershell
py -3 docs/reviews/2026-10-08-evidence/reproduce.py `
  --source-root 'C:\path\to\isolated-baseline-source' `
  --temp-root 'C:\Users\yafei\AppData\Local\Temp\opencode'
```

复核脚本仅在指定临时目录新建一个独立目录，不改项目配置/数据库。第一探针会在**自己的进程内 H2**创建、插入并删除临时表；权限/同步数据来自 mock；Flyway 只做 `info()`。第二探针只连接自己创建的回环 HTTP 服务。发布选择函数处理的是新建的两份 fixture JSON，不调用整体发布验收脚本。

这些探针用于证明基线行为，**不作为修复后应通过的回归测试**：修好问题后，输出应改变。真实 PostgreSQL、HTTP 完整身份链、Linux Runner 和灾备等测试仍需按主报告另外执行。

## 已执行的常规检查

```powershell
# 仓库根目录
mvn -f pom.xml -s scripts/maven-mirror-settings.xml test
py -3 -m unittest discover -s scripts/tests -p 'test_*.py'
py -3 -m unittest discover -s tests -p 'test_*.py'
docker compose config --quiet

# apps/web 目录
npm test
npm run type-check
npm run build
npm audit --json
```

结果见主报告第 3 节。没有把相同测试的重复执行相加；本地 Python 是 3.12.7，不声称已经运行 Python 3.11 的所有入口。

以下是只读的历史证据复核，不是重新执行真实数仓/HA：

```powershell
py -3 scripts/validate-runtime-evidence.py `
  --r1 artifacts/r1-empty-20261003/acceptance-manifest.json `
  --r2 artifacts/r2-cdc/r2-cdc-20261003-report.json
py -3 scripts/validate-platform-release-report.py `
  --report artifacts/platform-release/platform-release-acceptance-report.json
py -3 scripts/validate-r3-ha-report.py `
  --report artifacts/r3-ha/r3-ha-lifecycle-report.json
py -3 scripts/audit-r3-ha-evidence.py `
  --report artifacts/r3-ha/r3-ha-lifecycle-report.json
```

最后一条本次返回 exit 2（historical pass requires rerun），不能当作通过。
