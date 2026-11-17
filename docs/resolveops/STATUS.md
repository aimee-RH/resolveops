# Verification status

This file records checks, not planned capabilities.

| Gate | Status | Evidence / next requirement |
|---|---|---|
| G0 | GREEN | Public fork and pinned base verified. Small commits pushed to `codex/resolveops`; draft [PR #1](https://github.com/aimee-RH/resolveops/pull/1) targets this fork's main. Existing Git credentials work; connector write is not needed. |
| G1 | GREEN (local) | 56 engineering checks and four Python scorer checks pass. The five local investigation checks and the paired evaluation runner are opt-in. All nine Maven modules compile. The frontend passes strict TypeScript and production build. [Remote checks](https://github.com/aimee-RH/resolveops/pull/1/checks) |
| G2 | GREEN (read boundary) | Native PostgreSQL tests cover tenant grants, forged/revoked scope, request deduplication, immutable evidence, diagnosis citations and missing identity. HTTP checks supply the trusted upstream login context; full browser-to-SaToken login is not exercised. |
| G3 | GREEN (local development probes) | Local Llama 3.2 3B calls the scoped tools. Real Nomic embeddings and pgvector retrieve indexed runbooks. The active, unavailable, unregistered and registered cases have recorded outcomes and manually checked fact citations. The backend selects the outcome and any suggestion. [Runbook setup and probes](RUNBOOKS.md) · [Raw records](evidence/investigation-v9.json) |
| G4 | GREEN (local PostgreSQL and HTTP) | Immutable proposal, separate approver, expiry, scoped rechecks, one verified repair receipt and atomic audit. Eight tests cover HTTP permissions, stale and revoked approvals, concurrency, lost responses and rollback. [Repair setup and checks](REPAIR.md) |
| G5 | GREEN (local PostgreSQL and model probes) | Read-only API authorization investigation reuses the workbench, runs and evidence. PostgreSQL tests cover tenant and resource scope, safe projection, and incomplete data. Three local model probes cover 401, 403 and an empty window. [API investigation checks](API_INVESTIGATION.md) |
| G6 | GREEN (local diagnosis evaluation) | Frozen synthetic split with 12 held-out cases, two trials per case and two paired arms. Baseline passed 2/24, ResolveOps passed 24/24, and disallowed repair suggestions were 9 vs 0. [Method, raw records and limitations](EVALUATION.md) |
| G7 | PLANNED | Owner walkthrough, live workbench acceptance, user feedback and actual outcome metrics pending. |

## Verification commands

```bash
scripts/resolveops/check.sh -pl agent -am '-Dtest=Support*Test,RunbookEvidenceSearchTest,LocalModelConfigurationTest,ReActAgentProviderTest' -Dsurefire.failIfNoSpecifiedTests=false test
scripts/resolveops/check.sh -DskipTests compile
cd frontend
npx tsc --noEmit -p tsconfig.app.json
npm run build
```

56 passing engineering checks include eight PostgreSQL repair checks and four API authorization checks. The five opt-in integration checks cover four subscription investigation runs, three API investigation runs and a cancellation fault with a substituted model stream.

## Current limits

- The workbench supports opt-in investigations and opt-in approval and repair. Full logged-in browser acceptance is pending.
- The support investigator uses its own four read tools and per-run state. General Ragent chat behavior is unchanged. Collection and evidence-only structured finalization reuse AgentScope's runtime.
- The strict support runbook path currently supports the existing PG retriever only. General hybrid retrieval and fallback behavior are unchanged.
- Domain and investigation tests use synthetic data and real PostgreSQL 17.11. The indexed probes additionally use real embeddings and pgvector 0.8.2. HTTP tests provide a trusted test login context. Full application login and workbench acceptance belong to G7.
- The optional Docker image pull stalled locally and was interrupted. The Compose file was not verified by a successful container start; native PostgreSQL tests passed independently.
- No production customer data, measured time saving, adoption, or real-model success rate is claimed.

## Local-model deployment increment (2026-11-02)

Generation endpoint is `http://127.0.0.1:11434`, model `resolveops-qwen3:4b`, with 8K context, a 1024-token output cap, single-service concurrency and cloud features disabled. The `local-model` Spring profile is opt-in and overrides generation only. No Docker or paid-model API was used. G3 remains AMBER and G6 remains PLANNED: two real-model compatibility checks are not an investigation benchmark. Exact setup, failure observations and raw-evidence locations are in [LOCAL_MODEL.md](LOCAL_MODEL.md).

## Investigator increment (2026-11-03)

The original Qwen configuration remains the chat default; an optional local Llama 3.2 3B alias passed the two development investigation probes after harness changes. This is not a controlled model comparison. Use `RESOLVEOPS_LOCAL_MODEL=resolveops-llama:3b`, `RESOLVEOPS_REASONING_EFFORT=none` and explicitly enable `RESOLVEOPS_INVESTIGATION_ENABLED` only after configuring the rest of Ragent. See [INVESTIGATION.md](INVESTIGATION.md) for source boundaries, exact opt-in checks, failure analysis and remaining quality issues. No repair operations, production customer data or paid API calls were used. G3 is deliberately not promoted to GREEN.

## Runbook and policy increment (2026-11-10)

The original G3 requirements are met on the four recorded local development runs. The accepted version uses model-written facts and a server-selected outcome. The paired indexed probe keeps the same case and retrieved handbook and changes the policy registration. The result changes from `HANDOFF` to `PROPOSE_REPAIR`, with the subscription still pending and the operation table empty. [Implementation, failures and replay](RUNBOOKS.md)

## Controlled repair increment (2026-11-17)

An Operator creates a versioned proposal from a completed investigation, a second user with the Approver grant makes the decision, and the Operator executes it. PostgreSQL tests validate version and permission checks, retries, a concurrent repair race and an injected audit failure after the subscription update. The write updates the local synthetic subscription and preserves the original investigation evidence. [Walkthrough and verification](REPAIR.md)

## API authorization increment (2026-11-17)

The API investigator reads safe evidence for the current integration. The backend formats cited observations and routes recognized denials to human review or asks for missing evidence. The tests exercise the second scenario against synthetic records through the existing HTTP investigation flow. [Implementation and verification](API_INVESTIGATION.md)

## Paired diagnosis evaluation (2026-11-17)

The Python scorer and opt-in Java runner completed 48 diagnosis trials on frozen, synthetic PostgreSQL evidence using local Llama 3.2 3B whenever a model was needed. The checks cover outcomes, action policy, citation provenance and coverage. G7 full browser acceptance remains open. [Evaluation results](EVALUATION.md)
