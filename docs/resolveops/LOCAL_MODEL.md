# 本地模型部署

本配置只部署和验证生成模型。完整应用仍需数据库、登录、存储和独立的 embedding 配置；它不表示 G3 调查流程已完成。

## 已选择的配置

- Ollama CLI：`0.40.13`，Apple M4 / 16GB 内存，Metal GPU。
- 基础模型：`qwen3:4b`，GGUF `Q4_K_M`，约 2.5GB。
- 项目模型：`resolveops-qwen3:4b`，使用同一份权重，不重复占用 2.5GB。
- 上下文：8192 Token；每次输出最多 1024 Token；模型服务并行数为 1。
- Agent 配置：`temperature=0`、`reasoning_effort=low`。Qwen3 在 Ollama 中使用布尔思考开关，`low` 会开启思考，不代表独立的低预算档位；输出上限包含思考开销。
- 仅监听 `127.0.0.1:11434`，关闭 Ollama 云端功能，无需 API Key 或 Docker。
- `local-model` Spring profile 将 Agent 和三个聊天档位全部指向本地模型。Embedding、rerank 和视觉模型配置未在本次部署中替换，不应把整套 RAG 服务标记为离线可用。

## 启动与使用

在仓库根目录执行；`serve` 在前台运行，用 Ctrl+C 停止。不要重复启动已经运行的服务。

```bash
scripts/resolveops/local-model.sh serve
```

另一个终端中，首次下载并创建项目模型：

```bash
scripts/resolveops/local-model.sh setup
scripts/resolveops/local-model.sh status
```

`setup` 会联网下载模型。`status` 不执行推理。释放模型占用的内存、保留 HTTP 服务可用：

```bash
scripts/resolveops/local-model.sh unload
```

服务闲置 5 分钟后也会自动卸载模型。下次推理会重新载入。

直接体验本地对话：

```bash
scripts/resolveops/local-model.sh chat
```

这会打开模型的命令行对话，不经过 ResolveOps 的授权、工具和审计流程，不应当作工单调查界面。

应用在其余依赖配置完成后，以 `--spring.profiles.active=local-model` 启用此配置。模型客户端地址为 `http://127.0.0.1:11434`，聊天路径为 `/v1/chat/completions`，模型名为 `resolveops-qwen3:4b`。

## 安装位置与来源

本机 CLI 位于任务目录的 `work/ollama-0.40.13/ollama`，权重位于 `work/ollama-models`，服务日志位于 `work/ollama-runtime/server.log`；均在 Git 仓库外。Ollama 可能在用户的标准 `~/.ollama` 目录创建本机配置/密钥，这些也不能提交。

脚本优先使用本机任务目录的 CLI，否则查找 PATH 中的 Ollama。其他电脑可安装[官方 Ollama](https://docs.ollama.com/macos)，或通过 `RESOLVEOPS_OLLAMA_BIN` 指定 CLI、`RESOLVEOPS_OLLAMA_MODELS` 指定模型存储目录。本次没有配置开机自启。

部署包来自[官方 v0.40.13 release](https://github.com/ollama/ollama/releases/tag/v0.40.13)，`ollama-darwin.tgz` 的 SHA-256 已与 GitHub release 的 digest 核对：

```text
bae29252232b9e1358cf5801a7d65a07442af18fb5467cb68c7fc3711f59c511
```

该 CLI archive 的 macOS `codesign --verify` 未通过；本次使用官方包摘要验证来源，没有修改签名或关闭系统安全设置。若需要经过应用签名验证的安装路径，应另用官方 macOS 应用安装包。

基础模型 manifest digest：`359d7dd4bcdab3d86b87d73ac27966f4dbb9f5efdfcc75d34a8764a09474fae7`。
项目模型 manifest digest：`a7be9369c10a0c96a265fa30e91ba47c51483abffa0f526399cb26ed3b93456c`。
模型参数保存在 [`resources/ollama/Modelfile`](../../resources/ollama/Modelfile)。重做 `setup` 后应核对 digest，避免将可移动的标签当作固定版本。

## 验证

普通配置检查不需要下载模型或启动服务：

```bash
scripts/resolveops/check.sh -pl agent -am \
  '-Dtest=LocalModelConfigurationTest' -Dsurefire.failIfNoSpecifiedTests=false test
```

明确启用后，才执行本地模型检查：

```bash
RESOLVEOPS_LOCAL_MODEL_TEST=1 scripts/resolveops/check.sh -pl agent -am \
  '-Dtest=LocalModelConfigurationTest,LocalModelSmokeTest' \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

两项模型检查分别验证原生 JSON Schema 输出，以及实际 `AgentEngineConfiguration` 创建的流式 AgentScope 客户端调用 `get_subscription_status`、消费返回的 evidence ID。业务数据为合成数据，授权与持久化服务使用测试替身；没有验证真实数据库、向量检索或完整客服调查。

原始响应输出到 `agent/target/local-model-schema.json` 和 `agent/target/local-model-agent.json`，不提交到 Git。检查失败会使测试失败；不能把这两项兼容性检查解读为业务任务成功率。

初次检查发现：默认随机性下模型耗尽输出上限且未调用工具；显式设置 `temperature=0` 后工具往返通过。另发现该版本组合在 `reasoning_effort=none` 时仍可能把推理文字混入 `content`，因此当前保留思考并使用分离的推理字段，不靠截取字符串掩盖问题。完整业务评测仍应包含超限、重复工具调用和错误诊断案例。

## 本机验收记录（2026-11-02，America/New_York）

- 配置绑定检查通过：Agent 与三个聊天档位均只选择本地模型。
- 两项 opt-in 真实模型兼容性检查通过，无跳过：JSON Schema 输出 `{"status":"READY"}`；Java 流式 Agent 真正调用订阅读工具，最终答案包含 `deploy-evidence-1`、`PAID`、`PENDING`，不含推理标签。
- 最终配置的单次工具往返约 40.6 秒；这是一个合成案例的单次观察，不是延迟分位数或成功率。模型推理免费按本机算力运行，不产生 API Token 账单。
- 31 项工程回归检查通过：原有 19 项 ResolveOps 检查、11 项上游 AgentProvider 检查和 1 项新增本地配置检查。全部 9 个 Maven 模块编译通过。
- 未启动 Docker、未下载 Llama、未启动完整 Web 应用；真实数据库到模型、向量入库和 held-out 业务评测仍待完成。

官方参考：[工具调用](https://docs.ollama.com/capabilities/tool-calling)、[OpenAI 兼容接口与思考控制](https://docs.ollama.com/api/openai-compatibility)、[上下文与模型存储](https://docs.ollama.com/faq)。
