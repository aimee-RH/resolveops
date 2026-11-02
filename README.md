# ResolveOps

A SaaS support workbench extending [Ragent](https://github.com/nageoffer/ragent), built as an explainable SWE / AI engineering project. **In development: M1 is a read-only foundation.**

## Current implementation

- PostgreSQL-backed customer grants, case access and run-scoped reads.
- Idempotent run creation, immutable evidence snapshots and authorized event history.
- Native AgentScope read-tool adapters with explicit scope, parameter limits, shared budget and sanitized failures.
- A PG-only runbook evidence adapter with collection filtering and source hashes.
- A protected React workbench for cases, history and evidence.
- Nineteen new engineering checks, full-reactor compilation and frontend type/build checks.

The tools are not yet connected to a live support investigator. Real-model orchestration, structured diagnosis validation, approval/repair execution, the second investigation flow and held-out evaluation remain future milestones. No production data, model-success rate or user-impact claim is made.

## Development and evidence

- [Development setup and exact checks](docs/resolveops/DEVELOPMENT.md)
- [Green-light requirements](docs/resolveops/GREEN_LIGHTS.md)
- [Actual verification status](docs/resolveops/STATUS.md)
- [Owner walkthrough](docs/resolveops/WALKTHROUGH.md)
- [Contribution boundaries](docs/resolveops/CONTRIBUTIONS.md)
- [First scoped-retrieval design decision](docs/resolveops/adr-001-scoped-runbooks.md)

Work is split into small commits on `codex/resolveops`, reviewed through [draft PR #1](https://github.com/aimee-RH/resolveops/pull/1). Green CI checks cover the named M1 engineering checks, not all future product gates.

## Scope

The planned primary flow investigates paid-but-not-activated subscriptions and executes only explicitly approved, server-validated local repairs. A second read-only API authorization flow will test reuse of the case/run/evidence infrastructure. Synthetic data and mechanism tests are kept separate from real-model evaluation.

## Upstream attribution

Forked from `nageoffer/ragent` at `c3b6157730ac1f66a312bcd286c47d3ee6aaac42`. The original [upstream README](README.upstream.md) and [Apache-2.0 license](LICENSE) are preserved. Ragent provides the application foundation, authentication primitives, model/runtime integration, retrieval and UI primitives. ResolveOps additions and verification limits are documented separately.
