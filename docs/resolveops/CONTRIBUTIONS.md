# Contribution boundaries

## Upstream

- Repository: https://github.com/nageoffer/ragent
- Pinned base: `c3b6157730ac1f66a312bcd286c47d3ee6aaac42`
- License: Apache-2.0; original notices retained.
- Reused: modular application, authentication primitives, AgentScope ReAct integration, model adapters, retrieval, frontend primitives and tracing patterns.

## ResolveOps additions

Planned: support business data, resource authorization, case/run scope, bounded tools, structured evidence, diagnosis validation, immutable proposals, approval-controlled local repair, a support workbench and reproducible evaluations.

Only actual commits and verified behavior belong in completed resume claims. AI-assisted changes require the owner to reproduce and explain the critical path; commit authorship alone does not establish independent understanding.

The local-model increment adds a Qwen3 4B Modelfile, an opt-in generation profile, configurable sampling/thinking defaults, a local service helper, a configuration regression check and two opt-in real-model compatibility checks. It reuses Ollama inference and upstream AgentScope streaming/tool execution; it does not train a model or implement the complete support investigator. Deployment checks use synthetic data and mocked business services, so they are not evidence of production authorization or task-success rates.

## Earlier experiment

A separate Harness Lab and native Guard patch exist outside this fork. They are not imported into this clean branch. Any future reuse must be a separate reviewed commit with its source, dependency and measured limitation recorded. Its synthetic scenario scores are not ResolveOps model evaluation results.
