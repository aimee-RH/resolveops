# Contribution boundaries

## Upstream

- Repository: https://github.com/nageoffer/ragent
- Pinned base: `c3b6157730ac1f66a312bcd286c47d3ee6aaac42`
- License: Apache-2.0; original notices retained.
- Reused: modular application, authentication primitives, AgentScope ReAct integration, model adapters, retrieval, frontend primitives and tracing patterns.

## ResolveOps additions

Implemented: support business data, resource authorization, case/run scope, bounded read tools, structured evidence, opt-in investigation submit/cancel, evidence-only fact writing, backend outcome rules and a polling support workbench. ResolveOps registers runbook policies by exact content hash and reuses Ragent's embedding and pgvector pipeline for live retrieval. Planned: immutable proposals, approval-controlled local repair, the second investigation scenario and held-out evaluations.

Only actual commits and verified behavior belong in completed resume claims. AI-assisted changes require the owner to reproduce and explain the critical path; commit authorship alone does not establish independent understanding.

The original Qwen3 compatibility checks use mocked business services. The investigation probes use local Llama 3.2 3B with real PostgreSQL business services and local Nomic embeddings for the indexed runbook cases. A separate cancellation check substitutes a never-ending model stream. The probes use synthetic data and a test login context. [Investigation record](INVESTIGATION.md)

## Earlier experiment

A separate Harness Lab and native Guard patch exist outside this fork. They are not imported into this clean branch. Any future reuse must be a separate reviewed commit with its source, dependency and measured limitation recorded. Its synthetic scenario scores are not ResolveOps model evaluation results.
