# Verification status

This file records checks, not planned capabilities.

| Gate | Status | Evidence / next requirement |
|---|---|---|
| G0 | GREEN | Public fork and pinned base verified. Small commits pushed to `codex/resolveops`; draft [PR #1](https://github.com/aimee-RH/resolveops/pull/1) targets this fork's main. Existing Git credentials work; connector write is not needed. |
| G1 | GREEN (local) | 44 engineering checks pass. The four opt-in investigation checks are skipped by default. All nine Maven modules compile. The updated frontend passes strict TypeScript and production build. [Remote checks](https://github.com/aimee-RH/resolveops/pull/1/checks) |
| G2 | GREEN (read boundary) | Native PostgreSQL tests cover tenant grants, forged/revoked scope, request deduplication, immutable evidence, diagnosis citations and missing identity. HTTP checks supply the trusted upstream login context; full browser-to-SaToken login is not exercised. |
| G3 | GREEN (local development probes) | Local Llama 3.2 3B calls the scoped tools. Real Nomic embeddings and pgvector retrieve indexed runbooks. The active, unavailable, unregistered and registered cases have recorded outcomes and manually checked fact citations. The backend selects the outcome and any suggestion. [Runbook setup and probes](RUNBOOKS.md) · [Raw records](evidence/investigation-v9.json) |
| G4 | PLANNED | Tables reserved; proposal/approval/execution services and write endpoints are not implemented. |
| G5 | PLANNED | API integration/log tables reserved; its investigation tools and flow are not implemented. |
| G6 | PLANNED | Versioned held-out tasks, an evaluation runner and comparative metrics remain pending. |
| G7 | PLANNED | Owner walkthrough, live workbench acceptance, user feedback and actual outcome metrics pending. |

## Verification commands

```bash
scripts/resolveops/check.sh -pl agent -am '-Dtest=Support*Test,RunbookEvidenceSearchTest,LocalModelConfigurationTest,ReActAgentProviderTest' -Dsurefire.failIfNoSpecifiedTests=false test
scripts/resolveops/check.sh -DskipTests compile
cd frontend
npx tsc --noEmit -p tsconfig.app.json
npm run build
```

Passing checks: SupportSchemaTest (2), SupportAccessTest (4), SupportRunStoreTest (3), SupportHttpTest (3), SupportDiagnosisValidatorTest (4), RunbookEvidenceSearchTest (3), SupportReadToolsTest (6), SupportRunServiceTest (6), SupportAgentProviderTest (1), LocalModelConfigurationTest (1), upstream ReActAgentProviderTest (11): 44 total. The four opt-in investigation checks also passed with the [runbook test flags](RUNBOOKS.md). Three of those checks cover four real-model investigation runs, and one uses a deliberate cancellation fault.

## Current limits

- The workbench can submit/cancel and poll opt-in investigations. The feature is disabled by default and requires existing application infrastructure; full logged-in browser acceptance is pending. There is no approval/repair action.
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

The original G3 requirements are met on the four recorded local development runs. The accepted version uses model-written facts and a server-selected outcome. The paired indexed probe keeps the same case and retrieved handbook and changes the policy registration. The result changes from `HANDOFF` to `PROPOSE_REPAIR`, with the subscription still pending and the operation table empty. G4 approval and execution, G6 held-out evaluation and G7 full workbench acceptance remain open. [Implementation, failures and replay](RUNBOOKS.md)
