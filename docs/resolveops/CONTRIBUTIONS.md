# Contribution boundaries

## Upstream

- Repository: https://github.com/nageoffer/ragent
- Pinned base: `c3b6157730ac1f66a312bcd286c47d3ee6aaac42`
- License: Apache-2.0; original notices retained.
- Reused: modular application, authentication primitives, AgentScope ReAct integration, model adapters, retrieval, frontend primitives and tracing patterns.

## ResolveOps additions

Implemented: support business data, resource authorization, case/run scope, bounded read tools, structured evidence, opt-in investigation submit/cancel, evidence-only structured finalization, diagnosis validation and a polling support workbench. The investigation update also adds fixed tool query bounds, per-run citation enums and subscription/cancellation deadline protection. Planned: live indexed-runbook acceptance, immutable proposals, approval-controlled local repair, the second investigation scenario and held-out evaluations.

Only actual commits and verified behavior belong in completed resume claims. AI-assisted changes require the owner to reproduce and explain the critical path; commit authorship alone does not establish independent understanding.

The original local-model increment adds a Qwen3 4B Modelfile, an opt-in generation profile, configurable sampling/thinking defaults, a local service helper, a configuration regression check and two opt-in real-model compatibility checks. Those compatibility checks use mocked business services. The later investigation update adds a local Llama 3.2 3B option and two model probes using real PostgreSQL business services; a separate cancellation probe intentionally substitutes a never-ending model stream. All data is synthetic and test identities bypass the full login chain. Both increments reuse Ollama inference and upstream AgentScope streaming/tool execution; neither trains a model or establishes production task-success rates. See [the investigation record](INVESTIGATION.md).

## Earlier experiment

A separate Harness Lab and native Guard patch exist outside this fork. They are not imported into this clean branch. Any future reuse must be a separate reviewed commit with its source, dependency and measured limitation recorded. Its synthetic scenario scores are not ResolveOps model evaluation results.
