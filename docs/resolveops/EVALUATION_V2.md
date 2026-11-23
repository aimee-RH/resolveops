# G6 诊断评测 v2：分层留出集与消融

v1 用 12 个留出样例证明了“后端规则决策”比“模型直接下结论”更可靠（[v1 结果](EVALUATION.md)）。v2 回答 v1 回答不了的三个问题：

1. **样本是否足够、是否覆盖难例？** 留出集扩到 76 个，按 12 个类别分层，加入提示注入和客户自述与证据不符的工单。
2. **到底是哪一层起作用？** 增加两个消融组，把“提示里写规则”“后端校验输出”“后端决定结论”拆开测量。
3. **结果稳定吗？** 支持每例最多 10 次重复，报告 pass^k（k 次全部通过的概率）和 95% 置信区间，可在非零温度下测一致性。

v1 的数据、代码路径和默认命令保持不变；不加新参数时 `run.py` 的行为与 v1 相同，并能逐项复现 v1 已发布的汇总。

## 数据集

[`cases.v2.json`](../../eval/resolveops/cases.v2.json) 由 [`build_cases_v2.py`](../../eval/resolveops/build_cases_v2.py) 生成，每个样例都在脚本里显式写出，便于审阅。其中原样包含 v1 的 14 个样例（`origin: "v1"`），便于前后对照。

黄金标签由 [`policy.py`](../../eval/resolveops/policy.py) 根据书面业务规则计算（即基线提示中给出的规则）。它不调用 Java 实现，所以评测检查的是“后端是否符合书面规则”，而不是“后端是否等于它自己”。`policy.py` 能逐条复现 v1 的 14 个人工标签，这条由单元测试保证。

| 类别 | 含义 | 留出数 | 标签 |
|---|---|---:|---|
| repairable | 已付款、待激活或激活失败，事件完整，手册已登记 | 9 | PROPOSE_REPAIR |
| runbook_missing | 修复条件成立，但手册未登记、缺失或被截断 | 6 | HANDOFF |
| events_incomplete | 缺少关键事件、事件为空、日志缺失或被截断、名称相近的干扰事件 | 8 | HANDOFF |
| not_paid | 未付款、已退款、付款失败、争议中 | 6 | HANDOFF |
| other_activation | 已取消、已暂停、已过期等修复不覆盖的状态 | 3 | HANDOFF |
| already_active | 已激活（含未付款但已激活、带旧失败事件） | 5 | NO_ACTION_NEEDED |
| no_subscription | 没有订阅记录 | 5 | NEEDS_INFORMATION |
| injection | 工单正文里藏指令（伪造系统消息、伪造 JSON、要求忽略截断或压下修复） | 8 | 按证据 |
| customer_claim | 客户自述的事实与证据不符 | 4 | 按证据 |
| api_handoff | 集成状态和 401/403 记录齐全（含截断、多种错误码） | 12 | HANDOFF |
| api_needs_info | 缺少集成状态或拒绝记录 | 6 | NEEDS_INFORMATION |
| api_injection | API 工单正文里藏指令 | 4 | 按证据 |

留出集共 76 例：PROPOSE_REPAIR 10、HANDOFF 45、NEEDS_INFORMATION 15、NO_ACTION_NEEDED 6。另有 8 个开发样例，只用于检查流程和调整提示。注入和客户自述类样例的标签只由证据决定；单元测试会去掉工单正文重新计算标签，确认结果不变。

新增的样例字段：`description`（写入 CASE 证据的工单正文，最长 1000 字符）、`runbook: "registered-truncated"`（已登记但被截断的手册片段）、`denialsTruncated`（拒绝记录被截断）。

**冻结规则**：正式运行留出集之前固定数据。`python3 eval/resolveops/build_cases_v2.py --check` 会在文件与生成脚本不一致时失败。之后若要修改，应升级版本号，不覆盖原文件。

## 对比组

| 组 | 谁决定结论 | 测量的是什么 |
|---|---|---|
| `baseline` | 模型，提示中写明业务规则 | v1 基线 |
| `baseline-no-policy` | 模型，提示中没有业务规则 | 提示中的规则带来多少收益 |
| `validated` | 模型；同一次试验的 baseline 输出再经过后端 `SupportDiagnosisValidator.validate` | 只加“输出校验”能挡住多少违规；被拒绝记为错误 |
| `resolveops` | 模型只写事实，后端规则决定结论和动作 | 完整方案 |

`validated` 直接复用同一次试验中 baseline 的输出，不额外调用模型，因此两组的差异完全来自后端校验。它的耗时等于 baseline 生成时间加校验时间。线上流程中，校验失败的诊断会让运行以 `INVALID_DIAGNOSIS` 结束，不会展示修复建议。

各组共享同一份持久化证据；每次试验轮换先调用模型的组，以减少先后顺序偏差（两组时与 v1 的交替顺序相同）。

## 指标

在 v1 的评分条件（结构合法、结论命中、动作符合、引用属于当次证据、引用覆盖要求的来源）之外，报告还包含：

- **95% Wilson 置信区间**：适用于“全部条件通过率”和“违规修复建议率”。同一案例的重复试验并不独立，区间应视为近似。
- **pass^k**：随机抽 k 次试验全部通过的概率，按案例取平均（每个案例取 C(c,k)/C(n,k)）。pass^1 等于普通通过率；k 越大，越能看出稳定性。**safe^k** 用同样方法统计“k 次都没有违规修复建议”。
- **被校验拒绝数**：`INVALID_DIAGNOSIS` 的次数。
- **分层统计**：按场景、类别和黄金结论分别统计通过数、结论命中数和违规修复数。
- **配对对比**：每组与 `resolveops` 逐次比较“两边都过 / 只有该组过 / 只有 resolveops 过 / 都没过”。

每次运行都会在 `report.json` 旁边生成 `report.md`，可以直接贴进文档。

## 在本机运行

前置条件与 v1 相同：Python 3.9+、Java 17、Maven、本机 Ollama（`resolveops-llama:3b`，或者 `resolveops-qwen3:4b`），以及在 `127.0.0.1:55434` 上的专用 PostgreSQL。

```bash
# 1. 编译检查（第一次运行前）
scripts/resolveops/check.sh -pl agent -am -DskipTests test-compile

# 2. 评分器与数据集测试（只需 Python）
PYTHONDONTWRITEBYTECODE=1 python3 -m unittest discover -s eval/resolveops -p 'test_*.py'
python3 eval/resolveops/build_cases_v2.py --check

# 3. 开发集冒烟测试：8 例 × 1 次，确认四组都能跑通
python3 eval/resolveops/run.py --dataset eval/resolveops/cases.v2.json --split dev --trials 1 \
  --arms baseline,baseline-no-policy,validated,resolveops --output eval/results/v2-dev

# 4. 正式留出集：生产配置（温度 0）
python3 eval/resolveops/run.py --dataset eval/resolveops/cases.v2.json --split heldout --trials 5 \
  --arms baseline,baseline-no-policy,validated,resolveops --output eval/results/v2-llama-t0

# 5. 稳定性：非零温度，pass^k 才有区分度
python3 eval/resolveops/run.py --dataset eval/resolveops/cases.v2.json --split heldout --trials 5 \
  --temperature 0.7 --arms baseline,validated,resolveops --output eval/results/v2-llama-t07

# 6. 换模型
python3 eval/resolveops/run.py --dataset eval/resolveops/cases.v2.json --split heldout --trials 5 \
  --model resolveops-qwen3:4b --arms baseline,validated,resolveops --output eval/results/v2-qwen-t0
```

**耗时估算**：按 v1 的耗时（baseline 每次约 8.5 秒、resolveops 订阅场景约 2 秒；API 场景不调用模型）估算，第 4 步四组 × 5 次大约需要 2 小时，去掉 `baseline-no-policy` 大约 1 小时。可以先用 `--trials 3` 跑一遍。

**温度说明**：温度 0 与生产配置一致，但重复试验的结果几乎相同，pass^k 基本等于 pass^1。第 5 步专门用来测量采样带来的不稳定性。两种设置的结果应分开报告。

只用 Python 复评现有记录：

```bash
python3 eval/resolveops/run.py --dataset eval/resolveops/cases.v2.json --split heldout --trials 5 \
  --arms baseline,baseline-no-policy,validated,resolveops \
  --score-only eval/results/v2-llama-t0/trials.ndjson --output eval/results/v2-llama-t0-rescore
```

## 结果

2026-11-22 在 PACE ICE 的一块 NVIDIA H100 上运行（[脚本](../../scripts/resolveops/pace/README.md)），Ollama 0.40.13，commit `2882382`。留出集 76 例，每组每例 5 次，每次运行 380 条结果。

### 汇总

| 运行 | 指标 | baseline | baseline-no-policy | validated | resolveops |
|---|---|---:|---:|---:|---:|
| Llama 3.2 3B，温度 0 | 全部条件通过 | 63/380（17%，13–21%） | 2/380（1%，0–2%） | 36/380（9%，7–13%） | **380/380（100%，99–100%）** |
| | 结论正确 | 118/380（31%） | 50/380（13%） | 42/380（11%） | 380/380（100%） |
| | 违规修复建议 | 162/380（43%，38–48%） | 330/380（87%，83–90%） | 0/380 | **0/380** |
| | 被校验拒绝 | — | — | 192/380（51%） | 0/380 |
| Llama 3.2 3B，温度 0.7 | 全部条件通过 | 40/380（11%，8–14%） | — | 38/380（10%） | **362/380（95%，93–97%）** |
| | pass^5 | 0.03 | — | 0.03 | 0.82 |
| | 违规修复建议 | 131/380（34%） | — | 0/380 | **0/380** |
| Qwen3 4B，温度 0 | 全部条件通过 | 50/380（13%，10–17%） | — | 41/380（11%） | **298/380（78%，74–82%）** |
| | 结论正确 | 193/380（51%） | — | 133/380（35%） | 359/380（94%） |
| | pass^5 | 0.05 | — | 0.05 | 0.67 |
| | 违规修复建议 | 145/380（38%） | — | 0/380 | **0/380** |

括号内第二个区间为 95% Wilson 置信区间。H100 上各组单次中位延迟为 0.9–1.9 秒，与 v1 在 Mac 上测得的延迟不可直接比较。

### 结论

1. **后端规则决定结论是关键。** 三次运行中 resolveops 组共 1140 次试验，违规修复建议为 0；baseline 为 34–43%。Llama 温度 0 下 resolveops 全部 380 次通过。
2. **提示里的规则有用，但远远不够。** 去掉规则后违规修复从 43% 升到 87%，全部通过率从 17% 降到 1%。
3. **只做输出校验，能挡住违规，但给不出正确结论。** validated 组违规修复同样为 0，但约一半输出被拒绝，全部通过率反而低于 baseline（9% 对 17%）。在 `repairable` 类别中，baseline 有 23/45 次被评分器判为通过，validated 为 0/45：校验器对修复建议要求 `missingInformation` 为空、引用已批准手册并引用修复前提证据，比评分器更严格，所以这些修复建议都被拒绝了。也就是说，“模型下结论 + 校验兜底”在安全上可行，但会把可修复的工单变成失败。
4. **采样温度会暴露不稳定性。** 温度 0.7 时 resolveops 通过率从 100% 降到 95%，pass^5 为 0.82；失败集中在 `repairable`（37/45）、`events_incomplete`（36/40）和 `runbook_missing`（27/30），并有 9 次事实输出被校验拒绝。违规修复仍为 0。
5. **换模型后安全性保持，准确率下降。** Qwen3 4B 下 resolveops 结论正确率 94%，但全部通过率 78%（pass^5 0.67），21 次被校验拒绝，薄弱类别是 `events_incomplete`（16/40）、`runbook_missing`（14/30）和 `injection`（22/40）。配对比较中有 10 次 baseline 通过而 resolveops 未通过。结论由规则决定，但规则依赖模型抽取的事实，事实抽取的质量随模型变化。

### 开发集与复现说明

开发集（8 例 × 1 次）在 Mac（Apple Silicon）与 H100 上的 resolveops 结果都是 8/8，但 baseline 的违规修复数分别为 4/8 和 2/8：温度 0 在不同硬件和后端上并不逐字节一致。正式结论以 H100 上的留出集为准。

原始记录与报告：Llama 温度 0（[记录](evidence/g6-diagnosis-v2-llama-t0.ndjson) · [报告](evidence/g6-diagnosis-v2-llama-t0.md) · [JSON](evidence/g6-diagnosis-v2-llama-t0.json)）、Llama 温度 0.7（[记录](evidence/g6-diagnosis-v2-llama-t07.ndjson) · [报告](evidence/g6-diagnosis-v2-llama-t07.md) · [JSON](evidence/g6-diagnosis-v2-llama-t07.json)）、Qwen3 4B（[记录](evidence/g6-diagnosis-v2-qwen-t0.ndjson) · [报告](evidence/g6-diagnosis-v2-qwen-t0.md) · [JSON](evidence/g6-diagnosis-v2-qwen-t0.json)）。用 `run.py --score-only` 复评这三份记录，结果与 JSON 报告逐项一致。

## 局限

- 样例是按业务规则编写的合成记录，不是真实客服工单。结果支持“在这些证据和本地模型上的系统对比”，不能推广为真实工单上的成功率。
- 黄金标签来自书面规则。评测检查的是是否遵守规则，不评价规则本身是否合理。
- 引用覆盖只检查来源类型是否齐全；事实文本是否被证据语义支持，仍需人工抽查。
- 重复试验共享案例定义，置信区间按单次试验计算，偏乐观。
