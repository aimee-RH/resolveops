# ResolveOps

A SaaS support workbench extending [Ragent](https://github.com/nageoffer/ragent), built as an explainable SWE / AI engineering project. Read-only investigations and approval-controlled local subscription repair are opt-in.

## Current implementation

- PostgreSQL-backed customer grants, case access and run-scoped reads.
- Idempotent run creation, immutable evidence snapshots and authorized event history.
- Native AgentScope read-tool adapters with explicit scope, parameter limits, shared budget and sanitized failures.
- A PG-only runbook evidence adapter with collection filtering and source hashes.
- A protected React workbench for cases, history and evidence.
- Bounded per-run investigators, submit/cancel endpoints and a polling workbench; citations and repair preconditions are checked by the backend.
- Model-written facts cite persisted evidence. Backend rules choose no action, handoff, or a repair suggestion based on subscription status, events, and a registered runbook hash.
- Approval tied to an immutable proposal and its expiry. A separate human execution step rechecks current permissions and versions and returns a verified PostgreSQL receipt.
- Fifty-two engineering checks, full-reactor compilation and frontend type/build checks.
- An opt-in local Qwen3 4B generation profile with a checked deployment recipe, a configuration check and two real-model compatibility checks using synthetic business services.

The local investigation checks use Llama 3.2 3B with PostgreSQL and pgvector on synthetic subscription cases. A paired runbook test checks handoff before exact content registration and a repair suggestion afterward. PostgreSQL tests cover authorized repair, concurrent requests, retry and rollback. The second support scenario, held-out evaluation and full browser walkthrough are tracked in [verification status](docs/resolveops/STATUS.md).

## Development and evidence

- [Development setup and exact checks](docs/resolveops/DEVELOPMENT.md)
- [Local model setup, usage and verification](docs/resolveops/LOCAL_MODEL.md)
- [Investigation lifecycle, actual probes and failure analysis](docs/resolveops/INVESTIGATION.md)
- [Local runbook ingestion, retrieval and repair suggestion](docs/resolveops/RUNBOOKS.md)
- [Human approval, execution and failure checks](docs/resolveops/REPAIR.md)
- [Green-light requirements](docs/resolveops/GREEN_LIGHTS.md)
- [Actual verification status](docs/resolveops/STATUS.md)
- [Owner walkthrough](docs/resolveops/WALKTHROUGH.md)
- [Contribution boundaries](docs/resolveops/CONTRIBUTIONS.md)
- [First scoped-retrieval design decision](docs/resolveops/adr-001-scoped-runbooks.md)

Work is split into small commits on `codex/resolveops`, reviewed through [draft PR #1](https://github.com/aimee-RH/resolveops/pull/1). Green CI checks cover the named engineering checks, not all future product gates.

## Scope

The primary flow investigates paid-but-not-activated subscriptions and supports explicitly approved, server-validated local repairs. A second read-only API authorization flow will test reuse of the case/run/evidence infrastructure.

## Upstream attribution

Forked from `nageoffer/ragent` at `c3b6157730ac1f66a312bcd286c47d3ee6aaac42`. The original [upstream README](README.upstream.md) and [Apache-2.0 license](LICENSE) are preserved. Ragent provides the application foundation, authentication primitives, model/runtime integration, retrieval and UI primitives. ResolveOps additions and verification limits are documented separately.
