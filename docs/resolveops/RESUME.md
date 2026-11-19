# ResolveOps 求职项目说明

ResolveOps 面向处理 SaaS 客户故障的支持工程师。它扩展 Ragent，读取订阅、付款和 API 拒绝日志，检索操作手册，把诊断与当次证据关联起来。订阅修复经过另一名审批人确认，再由后端核验权限、资源版本和业务条件后事务执行。

## 项目中的工作

| 实现 | 为什么需要 | 面试可以追到哪里 |
|---|---|---|
| 租户权限与工单范围 | 支持人员只能调查被授权客户的资源 | 身份从登录上下文取得，每次工具调用复核范围；跨租户访问返回相同的不可用响应 |
| 有预算的工具与证据存储 | 工具调用有范围、时限和调用次数，诊断引用能对应具体记录 | 工具参数、运行状态、不可变快照、来源和内容哈希 |
| 服务端业务判定与审批修复 | 付款记录、失败事件和登记手册共同决定是否可以提出修复 | 独立审批人、预期版本、幂等操作、事务更新、审计与核验回执 |
| 可复评的 Python harness | 模型和规则变更需要可比较的结果 | 固定案例、配对输入、逐次输出、评分条件、失败归因与耗时 |

Ragent 提供原有认证、模型客户端、AgentScope 运行时、知识检索和聊天界面。上述工单数据、调查范围、证据与业务规则、审批修复和评测是 ResolveOps 的扩展。实现由 AI 协助完成，项目所有者需要复现和解释关键路径。[贡献边界](CONTRIBUTIONS.md)

## 英文简历版本

**ResolveOps — SaaS Support Agent Harness**

Java · Spring Boot · React / TypeScript · PostgreSQL / pgvector · Python

- Extended Ragent with a tenant-scoped SaaS support agent for subscription activation and API authorization incidents, combining bounded business tools, indexed runbooks, and immutable evidence snapshots.
- Implemented approval-gated subscription repair with separate requester and reviewer roles, resource-version checks, idempotent receipts, and atomic PostgreSQL updates and audit events.
- Built a Python evaluation harness over 12 held-out synthetic cases with two trials per arm, reducing policy-violating repair recommendations from 9/24 to 0/24 against a direct-model baseline.

## 数字的范围

第三条来自诊断阶段的配对实验，两组都用同一本地 Llama 3.2 3B 和相同证据，12 个案例各重复两次。ResolveOps 的订阅事实生成经过模型和引用检查，API 诊断使用后端规则。实验合计 48 条结果。它证明这套证据与规则在固定合成案例上的表现，真实工单规模、用户节省时间和生产效果仍待测量。[方法与原始记录](EVALUATION.md)

当前面向 SaaS 的用例和接口使用合成数据。Salesforce / Snowflake 的真实连接器尚待实现。通用聊天走 Ragent 的知识问答流程，工单调查及审批在 Support workbench 中完成。聊天自然语言到工单执行的连接仍待实现。

## 90 秒项目讲述

我基于 Ragent 做了一个 SaaS 支持工单系统。支持工程师经常需要在客户报告、付款记录、订阅状态和操作手册之间来回核对。我实现了限定租户和工单范围的调查工具，把每次读取保留为证据，再让模型生成有来源的事实，由后端规则判断下一步。

例如客户已经付款，但订阅开通失败。系统先收集付款和失败事件，再核对登记过的手册。满足条件时，操作员可以提交修复提案，由另一名有权限的人员审批。执行时再次检查权限和版本，用幂等键与 PostgreSQL 事务保证修复、回执和审计一致。

我还实现了 Python 评测流程，把模型直接生成诊断与 ResolveOps 在同一组证据上配对比较。在 12 个固定合成案例、每组每案例两次的实验中，违规修复建议从 9/24 降到 0/24。这个结果衡量本地诊断阶段，真实客服收益还需要用户验证。
