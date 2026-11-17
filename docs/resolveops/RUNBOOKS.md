# 本地手册检索与修复建议

ResolveOps 复用 Ragent 的 embedding、pgvector 和分块元数据查询。支持工具只查询显式允许的共享手册集合。检索命中后保存文档 ID、分块 ID、正文和正文 SHA-256，供调查回放。

调查 Agent 选择只读工具。终稿模型从已保存的证据撰写带引用的事实。订阅和事件证据存在时，终稿优先接收这两类记录。后端根据业务状态和手册登记决定结论，校验事实引用，再保存结构化诊断。

| 观测 | 后端结论 |
|---|---|
| 已读到开通状态为 `ACTIVE` | 无需修复 |
| 缺订阅状态记录 | 请求补充信息 |
| 已付款、待开通或开通失败、有未截断的付款确认及开通失败事件，且检索命中精确登记的手册内容 | 建议 `RECONCILE_ACTIVATION`，供人工复核 |
| 其余情况，包括已检索到手册但内容哈希未登记 | 转人工 |

建议保存于调查记录。工单工作台可以从建议直接打开手册证据。人工审批及状态修改通过 [G4 修复流程](REPAIR.md) 单独发起。

## 本机复现

在运行中的本机 Ollama 准备生成和 embedding 模型：

```bash
scripts/resolveops/local-model.sh setup-llama
scripts/resolveops/local-model.sh setup-embedding
```

准备专用 PostgreSQL 数据库并安装 pgvector。本轮使用 PostgreSQL 17.11 和 pgvector 0.8.2。用专用测试库的管理员提前在 `public` schema 执行 `CREATE EXTENSION IF NOT EXISTS vector;`。测试通过随机 schema 载入业务和知识库表，为测试向量列使用 768 维，并在结束时删除该 schema。请使用独立测试库。

```bash
RESOLVEOPS_INVESTIGATION_TEST=1 \
RESOLVEOPS_RUNBOOK_TEST=1 \
RESOLVEOPS_INVESTIGATION_MODEL=resolveops-llama:3b \
RESOLVEOPS_TEST_DB_URL=jdbc:postgresql://127.0.0.1:55434/postgres \
RESOLVEOPS_TEST_DB_USER=postgres \
scripts/resolveops/check.sh -pl agent -am \
  '-Dtest=SupportInvestigationIntegrationTest' -Dsurefire.failIfNoSpecifiedTests=false test
```

测试使用 Ragent 的入库组件向量化三条合成手册，调用真实的 pgvector 查询与元数据 mapper。它先确认正确手册排在允许集合首位，同时排除另一个集合中的高相似度文档。接着对同一个工单先测试未登记手册的转人工，再登记精确内容并验证修复建议及查询回放。订阅仍是 `PENDING`，操作表为空。

## 2026-11-10 验收记录

| 本机模型调查 | 结果 | 人工核对 |
|---|---|---|
| 已开通 | `NO_ACTION_NEEDED` | 付款及开通状态均来自订阅记录 |
| 向量手册未配置 | `HANDOFF` | 订阅和事件事实有对应引用，缺失项为已登记手册 |
| 检索命中但内容未登记 | `HANDOFF` | 检索结果保留为证据，动作字段为空 |
| 登记完全相同的内容后重跑 | `PROPOSE_REPAIR` | 付款和开通事实分别引用订阅及事件记录，建议引用了正确手册 |

四次运行的原始合成记录、模型 digest 和实现文件 SHA-256 都保存在[验收证据](evidence/investigation-v9.json)。另有一项使用模型流替身的取消故障测试。HTTP 验收使用测试登录上下文，完整浏览器登录和操作仍在 G7。以上样例用于开发验证，冻结评测集与基线对比在 G6。

最初把完整结论交给模型时，模型一边引用已有的订阅状态，一边把它标为缺失。后端接手结论后，较早的一次修复建议仍因模型引用或事实输出不符合校验要求而失败。另一轮通过了形式检查，但把事件误写成故障原因。终稿改为聚焦订阅和事件，并明确要求只描述观测。人工逐条核对了最终四次输出，任意自然语言事实的语义支持仍需在 G6 评测。

## 应用配置

对全新且使用 768 维向量空间的 Ragent 数据库，完成上游运行依赖后可同时启用 Spring profiles `local-model,local-runbooks`。该组合使用本机 [Nomic Embed v1.5](https://ollama.com/library/nomic-embed-text) 作为默认 embedding。上游初始 SQL 的向量列为 1536 维，初始化新的本地库时将该列改为 `vector(768)`。切换已有向量库时应重新入库和生成向量。

启动前设置 `RESOLVEOPS_LOCAL_MODEL=resolveops-llama:3b`、`RESOLVEOPS_INVESTIGATION_ENABLED=true`、`RESOLVEOPS_RUNBOOK_COLLECTIONS=<允许的集合名>`。将人工审核过的手册分块通过 Ragent 入库。使用专用数据库管理入口登记已审核的文档 ID、对应分块正文的 SHA-256 与策略码 `ACTIVATION_RECONCILE_V1`。正文修改后需重新审核并登记新哈希。
