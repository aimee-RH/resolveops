# API 授权故障调查

第二类工单供支持人员调查被拒绝的 SaaS 集成请求。创建 `API_AUTHORIZATION` 工单时，`resource_id` 指向同一租户的 `support_integration.id`。在工作台选中工单，使用现有的“开始只读调查”。查看运行记录、带引用的事实和建议核查事项。

| 样例 | 结论 | 给支持人员的信息 |
|---|---|---|
| 当前集成有一条 `401 / TOKEN_EXPIRED` | 转人工 | 记录报告凭据过期，可核对集成配置中的到期信息 |
| 当前集成有一条 `403 / MISSING_SCOPE` | 转人工 | 记录报告权限范围不足，可对照需求和既有授权 |
| 没有当前集成的近期 401/403 | 需要补充信息 | 请求提供近期拒绝记录 |
| 状态码和错误码不匹配，或错误码未识别 | 转人工 | 现有日志不能指明原因 |

“转人工”是调查结论，供当前支持人员继续核查。API 模式沿用原有登录、权限、执行预算、工作台、证据和引用校验。它提供三个只读工具：工单信息、集成启用状态及已记录权限条数、近期拒绝记录。集成与日志查询都使用服务端工单范围，模型不能指定资源或时间段。测试使用合成数据。

日志快照只保留时间、状态码和允许的错误类别。查询返回最近 24 小时内的最多 10 条，超出时标记截断。原始日志详情、请求路径、权限名称及凭据留在数据源。模型负责调用工具，后端根据持久化证据生成事实、引用和结论。订阅修复入口继续限定在订阅工单。

第一版让模型重述日志时，实际输出曾把 HTTP 401 解释为「401 条记录」。最终版分别读取日志数组长度和整数状态码，呈现「1 条记录，HTTP 401」，保留原始证据引用。

## 本地验收

```bash
scripts/resolveops/check.sh -pl support -am '-Dtest=SupportApiInvestigationTest' \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

专用本地 PostgreSQL 和 Ollama 启动后，可以用真实 Llama 跑三个样例：

```bash
RESOLVEOPS_INVESTIGATION_TEST=1 \
RESOLVEOPS_INVESTIGATION_MODEL=resolveops-llama:3b \
RESOLVEOPS_TEST_DB_URL=jdbc:postgresql://127.0.0.1:55434/postgres \
scripts/resolveops/check.sh -pl agent -am \
  '-Dtest=SupportInvestigationIntegrationTest#realApiInvestigationDistinguishes401And403AndRequestsMissingRecords' \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

测试在专用 PostgreSQL 的随机 schema 中填充合成记录，通过现有 HTTP 调查入口调用真实本地模型。测试登录上下文与完整网页登录分属两项验收。全部样例都保留集成状态，不产生修复操作。运行记录输出到 `agent/target/investigation-api-*.json`。三次开发验证的[实际运行记录](evidence/api-investigation-v2.json)包含证据快照、引用和工具调用事件。
