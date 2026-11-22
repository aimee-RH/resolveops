# 在 PACE 上跑 v2 评测

这些脚本把 [EVALUATION_V2.md](../../../docs/resolveops/EVALUATION_V2.md) 的第 3–6 步搬到 PACE 的 GPU 节点上运行。所有工具（Java 17、Maven、PostgreSQL 17、Ollama 和两个模型）都装在你的用户目录里，不需要管理员权限，也不需要 Docker。

## 1. 拉代码（登录节点）

```bash
cd ~/resolveops            # 你的仓库目录
git fetch origin && git checkout eval-v2 && git pull
```

## 2. 一次性准备环境（登录节点，约 15–30 分钟）

```bash
module load anaconda3      # 如果 conda 已经可用可以跳过
bash scripts/resolveops/pace/setup.sh
```

会下载大约 8 GB 到 `~/scratch/resolveops-env`（没有 scratch 时放在 `~/resolveops-env`），包括 conda 环境、Ollama 0.40.13、llama3.2:3b 和 qwen3:4b，并尝试预下载 Maven 依赖。最后会跑 Python 单元测试和数据集冻结检查。重复运行是安全的，已完成的部分会跳过。

## 3. 提交评测作业

```bash
sbatch scripts/resolveops/pace/eval.sbatch
squeue -u $USER                       # 查看排队和运行状态
tail -f resolveops-eval-<作业号>.log   # 查看进度
```

默认按顺序跑四步，每步写入 `eval/results/` 下的一个目录：

| 步骤 | 目录 | 内容 |
|---|---|---|
| `dev` | `v2-dev-pace` | 开发集冒烟测试，8 例 × 1 次 |
| `t0` | `v2-llama-t0` | 正式留出集，四组，温度 0 |
| `t07` | `v2-llama-t07` | 温度 0.7，测稳定性 |
| `qwen` | `v2-qwen-t0` | 换 qwen3:4b |

只跑一部分或改次数：

```bash
sbatch --export=ALL,ROPS_STEPS=dev,t0,ROPS_TRIALS=3 scripts/resolveops/pace/eval.sbatch
```

作业超时或失败后直接重新提交即可：已经有 `report.json` 的步骤会跳过；没跑完的目录会改名为 `*.partial-<时间>` 保留，然后重跑。

如果集群要求指定分区或 QOS（`sbatch` 报 `invalid partition` 或 `QOSMaxWallDurationPerJobLimit`），在命令里加上，例如 `sbatch -p <分区> -q <qos> ...`，或把 `-t` 改短。

## 4. 取回结果

每步的 `report.md` 可以直接贴进文档。在 VS Code 里右键 `eval/results/v2-llama-t0/report.md` 下载，或在 Mac 上：

```bash
scp -r login-ice.pace.gatech.edu:resolveops/eval/results/v2-* eval/results/
```

## 细节

- PostgreSQL 在作业节点的本地临时目录里新建，监听 `127.0.0.1:55434`，用户名和密码都是 `postgres`（与评测代码一致），作业结束即删除。
- Ollama 监听 `127.0.0.1:11434`（评测代码固定使用这个地址）。如果节点上这两个端口已被别人占用，作业会立即退出并提示重新提交。
- 登录节点限制单个用户的内存，完整编译可能被系统杀掉（日志里出现 `Killed`），所以第 2 步的 Maven 预下载只是尽力而为。作业开始时会在计算节点上联网编译一次并下载所有 Maven 依赖，之后各步以离线模式运行。如果计算节点不能联网，这一步会失败并给出提示。
- 作业日志开头会打印 GPU 型号和 Ollama 检测到的计算设备；如果出现 `no compatible GPUs`，说明在用 CPU，会慢很多。
