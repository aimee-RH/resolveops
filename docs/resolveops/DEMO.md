# G7 本地工作台演示

演示给负责 SaaS 工单的支持工程师。先用一张订阅故障说明如何从业务证据走到人工审批，再用一张 API 授权故障说明同一工作台如何处理只读诊断。

## 复现准备

需要 Java 17、Maven、Node、Python 3、PostgreSQL 客户端和以下仅绑定本机的依赖。本次演示使用原生进程。

| 服务 | 本机设置 |
|---|---|
| PostgreSQL 17.11 + pgvector 0.8.2 | `127.0.0.1:55434`，测试用户 `postgres`，新建的演示数据库。脚本不会写入现有数据库。 |
| Redis | `127.0.0.1:56379`，独立实例。把该实例的密码放进 `SPRING_DATA_REDIS_PASSWORD`。 |
| Ollama | `scripts/resolveops/local-model.sh serve`。准备 Llama、Qwen 和 Nomic 三个现有别名，见[模型设置](LOCAL_MODEL.md)。 |
| RocketMQ 5.2.0 | 本地 NameServer，配置 `bindAddress=127.0.0.1`、`listenPort=59876`。这两条工单流程未使用 broker。 |
| S3 测试模拟器 | [Moto 5.1.15](https://pypi.org/project/moto/5.1.15/) 可用 `moto_server -H 127.0.0.1 -p 59000` 启动，供上游应用初始化桶。手册搜索走本地 pgvector。 |

从项目根目录执行以下命令。需要创建新一轮演示时，把两个命令中的数据库名同时替换为新名称，例如 `resolveops_demo_replay`。API 日志样本以入库时刻为准，请在 24 小时内运行 403 演示。

```bash
mkdir -p .resolveops
(umask 077 && scripts/resolveops/seed-demo.sh resolveops_demo > .resolveops/demo-credentials.txt)
scripts/resolveops/check.sh -pl bootstrap -am -DskipTests package

export SPRING_DATASOURCE_URL=jdbc:postgresql://127.0.0.1:55434/resolveops_demo
export SPRING_DATA_REDIS_PORT=56379
export RAG_STORAGE_S3_ENDPOINT=http://127.0.0.1:59000
export RAG_STORAGE_S3_ACCESS_KEY=demo-local RAG_STORAGE_S3_SECRET_KEY=demo-local-only
export RESOLVEOPS_LOCAL_MODEL=resolveops-llama:3b RESOLVEOPS_REASONING_EFFORT=none
export RESOLVEOPS_INVESTIGATION_ENABLED=true RESOLVEOPS_REPAIR_ENABLED=true
export RESOLVEOPS_RUNBOOK_COLLECTIONS=support-sops
# Set SPRING_DATA_REDIS_PASSWORD in this shell to the demo Redis password.
java -jar bootstrap/target/bootstrap-0.0.1-SNAPSHOT.jar \
  --spring.profiles.active=local-model,local-runbooks --server.address=127.0.0.1 \
  --rocketmq.name-server=127.0.0.1:59876 --ragent.eval.enabled=false
```

在另一个终端运行 `cd frontend && npm ci && VITE_API_BASE_URL=/api/ragent npm run dev -- --strictPort`。打开 [本地工作台](http://127.0.0.1:5173/support)，使用 `.resolveops/demo-credentials.txt` 里的合成账号。脚本生成 768 维 Nomic 向量并把文档、分块和策略哈希写入数据库，使新演示可以重复检索同一份手册。停机时在各演示进程自己的终端退出。

## 从浏览器走一遍

1. 用 `demo-operator` 登录，从聊天侧栏的「Support workbench」进入，选择 `demo-activation`。点击「Start investigation」。展开诊断下方的手册引用，并在「Run evidence」里查看订阅状态和付款、失败事件。
2. 点击「Submit repair proposal」。操作员只能看到「Awaiting approval」。点「Back to chat」，从用户菜单退出，使用 `demo-approver` 登录并点击「Approve proposal」。
3. 切回 `demo-operator`，点击「Execute repair」并确认。查看「Repaired and verified」、开通版本和操作回执。订阅完成修复，工单保留 `OPEN`。
4. 打开 `demo-api-403`，开始调查。查看 `HTTP 403 (MISSING_SCOPE)`、转人工结论和 API 日志证据。
5. 用操作员直接打开 [第二个客户的工单](http://127.0.0.1:5173/support/demo-private)。页面应显示 404，列表仍只显示已授权客户的两张工单。

修复后再次调查，最新记录应显示「No action needed」。原始工单描述保留客户最初的故障报告，历史记录保留当次观察到的证据。用「Select investigation」查看较早的修复建议与回执。工作台支持纵向滚动，窄窗口里的证据位于调查记录下方。

## 2026-11-18 浏览器验收记录

| 检查 | 实际结果 |
|---|---|
| 订阅调查 | `COMPLETED / PROPOSE_REPAIR`，四类落库证据 `CASE`、`EVENTS`、`RUNBOOK`、`SUBSCRIPTION`。打开手册引用能看到具体分块及内容标识。 |
| 审批和修复 | 操作员和审批员通过各自的 Ragent 登录会话完成。订阅 `PENDING v1 → ACTIVE v2`，数据库中恰好一条 `VERIFIED` 操作，审计分别记录提交、审批、核验各一次。 |
| API 授权调查 | `COMPLETED / HANDOFF`，有一条 403 `MISSING_SCOPE`，保留 `CASE`、`INTEGRATION`、`API_DENIALS` 证据。集成仍启用，权限快照和版本均未变更。 |
| 跨租户访问 | 已登录的操作员打开未授权工单时收到 404，列表没有该工单。 |

这些是本机合成数据的端到端结果。浏览器演示只覆盖表中的样例，独立的 [G6 冻结留出评测](EVALUATION.md) 覆盖 12 个任务、每组每任务两次。配对实验中，同一模型基线通过 2/24，ResolveOps 通过 24/24，违反策略的修复建议为 9 比 0。数据范围为诊断阶段，实际用户反馈和节省的处理时间尚未测量。

## 面试可以讲什么

- **执行路径**。浏览器通过 Ragent / Sa-Token 登录。[工单控制器](../../support/src/main/java/com/nageoffer/ai/ragent/support/web/SupportCaseController.java)取得调用身份，[授权服务](../../support/src/main/java/com/nageoffer/ai/ragent/support/access/SupportAccessService.java)核验租户权限，[调查服务](../../agent/src/main/java/com/nageoffer/ai/ragent/agent/support/SupportRunService.java)启动带范围和预算的[只读工具](../../agent/src/main/java/com/nageoffer/ai/ragent/agent/support/SupportReadTools.java)。模型为事实提供证据引用，后端校验引用并决定结果。获批的写入由独立的[修复服务](../../support/src/main/java/com/nageoffer/ai/ragent/support/repair/SupportRepairService.java)完成。
- **贡献边界**。上游提供认证、模型适配、AgentScope 运行时和 pgvector 检索。扩展实现工单和租户授权、调查工具与证据、业务判定、审批事务和离线评测。见[贡献记录](CONTRIBUTIONS.md)。
- **作者自验**。从一张新的订阅工单开始，自己完成两次身份切换。用 90 秒讲清为什么用户关心开通结果、怎样从手册链接找到证据、何时系统转人工，以及 G6 的数字衡量了什么。

| 失败案例 | 看到的问题与处理方式 |
|---|---|
| 模型输出没有实查支撑 | 早期开发中模型从资源名猜出订阅已开通。后端要求事实有同一次运行里的状态证据，开发数据改用无法从 ID 猜结论的名称。见[调查记录](INVESTIGATION.md#真实失败与下一步)。 |
| 已检索到手册，规则仍不成立 | 开发探针中，合法索引能召回手册，但未经登记仍转人工。登记键绑定文档 ID 和精确的内容哈希，编辑手册后要重新确认。见[手册探针](RUNBOOKS.md#2026-11-10-验收记录)。 |
| 审批后资源已经变化 | PostgreSQL 测试在审批后改变版本，执行时拒绝过期证据所授权的动作。版本检查、幂等回执和事务审计保护写入路径。见[修复验收](REPAIR.md#postgresql-验收)。 |
