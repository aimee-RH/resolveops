# G6 诊断阶段评测

这组评测从已经保存的证据开始，比较模型直接下结论与 ResolveOps 的服务端规则。它测量诊断、引用和修复建议。工单登录、前置调查工具、检索以及审批执行各有独立验收。

| 方案 | 数据与行为 |
|---|---|
| 模型基线 | 读取同一组证据和已登记的手册证据 ID。提示按场景提供实际业务规则，要求模型一次生成带引用的完整诊断。 |
| ResolveOps | 复用现有生产代码。订阅场景调用原有事实生成器，再由后端验证证据并决定结论。API 场景直接从证据字段生成事实和结论。 |

使用同一个本地 Ollama 模型、相同的采样设置和模型配置。每组配对共享相同的持久化证据和 ID。每次重复建立新的调查记录，生成调用使用独立的上下文。两种方案交替先跑，以减少先后顺序偏差。每次保存两边的输出或错误、用时以及证据。时间从诊断阶段开始计量。

## 数据和评分

[版本化合成数据](../../eval/resolveops/cases.v1.json)包含 2 个开发样例和 12 个留出样例。开发样例可用于检查和修改提示。留出样例在正式运行前固定，其中包含未付款、截断日志、未登记手册、混合拒绝和空日志等情况。黄金标签供评分使用，模型输入仅含当前工单证据及可用的规则。

评分器按以下条件判定一次试验通过：输出结构合法、结论命中、建议动作符合标签、引用属于当次证据，以及引用覆盖该案例要求的来源类型。单独统计每个条件、违规修复建议、配对差异和耗时。引用覆盖用于检查来源完整性，事实文本是否被证据语义支持仍需人工抽查。两次重复测试的是同一组受控案例。

## 复现

启动本机 Ollama 并确认 `resolveops-llama:3b` 可用。启动专用本地 PostgreSQL，默认使用 `127.0.0.1:55434/postgres`。评测会创建并删除随机测试 schema。

```bash
python3 eval/resolveops/run.py --split dev --trials 1 --output eval/results/g6-dev
python3 eval/resolveops/run.py --split heldout --trials 2 --output eval/results/g6-heldout
```

运行需要 Python 3.9+、Java 17 和 Maven。已有 Maven 缓存时可添加 `--offline`。输出目录中的 `trials.ndjson` 保存原始证据与输出，`report.json` 保存汇总和逐次评分，`maven.log` 保存执行日志。复评现有记录只需 Python：

```bash
python3 eval/resolveops/run.py --split heldout --trials 2 \
  --score-only eval/results/g6-heldout/trials.ndjson --output eval/results/g6-rescore
PYTHONDONTWRITEBYTECODE=1 python3 -m unittest discover -s eval/resolveops -p 'test_*.py'
```

## 留出集结果（2026-11-17）

同一组 12 个案例各跑两次，两种方案共 48 条记录。模型为本机 `resolveops-llama:3b`，Ollama 0.40.13。汇总保存了模型、数据集与原始记录的摘要，可用原始记录复评。

| 指标 | 模型基线 | ResolveOps |
|---|---:|---:|
| 结论命中 | 10 / 24 | 24 / 24 |
| 满足全部评分条件 | 2 / 24 | 24 / 24 |
| 不满足条件却建议修复 | 9 / 24 | 0 / 24 |
| 诊断耗时中位数 | 8.53 秒 | 2.14 秒 |
| 诊断耗时 P95 | 10.13 秒 | 5.74 秒 |
| 经过模型生成阶段 | 24 / 24 | 14 / 24 |

配对中 2 组两边都通过，22 组只有 ResolveOps 通过。比如，案例 h04 的事件列表标明已截断，基线仍建议修复，ResolveOps 转人工。在 h08，基线一边选择转人工，一边输出了订阅修复动作。ResolveOps 的 API 结论不会生成订阅修复动作。

整体耗时包含两类工单。ResolveOps 的 10 次 API 诊断在收集证据后直接由后端完成，所以这个耗时差适用于这次混合测试。样例是按业务规则编写的合成记录，重复试验共享同一案例定义。结果支持这个本地模型和证据集上的系统对比，不能推广为所有模型、真实客服工单或整个 Agent 的成功率。

[原始配对记录](evidence/g6-diagnosis-v1.ndjson) · [汇总及版本信息](evidence/g6-diagnosis-v1.json)
