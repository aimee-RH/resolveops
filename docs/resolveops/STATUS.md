# Verification status

This file records checks, not planned capabilities.

| Gate | Status | Evidence / next requirement |
|---|---|---|
| G0 | GREEN | Public fork and pinned base verified. Small commits pushed to `codex/resolveops`; draft [PR #1](https://github.com/aimee-RH/resolveops/pull/1) targets this fork's main. Existing Git credentials work; connector write is not needed. |
| G1 | GREEN (local) | 44 engineering checks pass; three opt-in integration checks are skipped by default. All nine Maven modules compile. Updated frontend passes strict TypeScript and production build. Inspect [remote checks](https://github.com/aimee-RH/resolveops/pull/1/checks) for the current pushed revision. |
| G2 | GREEN (read boundary) | Native PostgreSQL tests cover tenant grants, forged/revoked scope, request deduplication, immutable evidence, diagnosis citations and missing identity. HTTP checks supply the trusted upstream login context; full browser-to-SaToken login is not exercised. |
| G3 | AMBER (investigator implemented) | Opt-in submit/cancel, scoped model tool execution, evidence-only finalization and diagnosis validation implemented. Two real PostgreSQL + Llama 3.2 3B probes complete with NO_ACTION_NEEDED/HANDOFF; one additional database-backed cancellation check uses an explicit model-stream double. Live embedding/pgvector runbooks, the proposed-repair model path and human diagnosis-quality acceptance remain open. See [investigation evidence and limits](INVESTIGATION.md). |
| G4 | PLANNED | Tables reserved; proposal/approval/execution services and write endpoints are not implemented. |
| G5 | PLANNED | API integration/log tables reserved; its investigation tools and flow are not implemented. |
| G6 | PLANNED | No held-out model evaluation or Python runner yet. Engineering checks and two development model probes are not a task-success benchmark. |
| G7 | PLANNED | Owner walkthrough, live workbench acceptance, user feedback and actual outcome metrics pending. |

## Verification commands (2026-11-03)

```bash
scripts/resolveops/check.sh -pl agent -am '-Dtest=Support*Test,RunbookEvidenceSearchTest,LocalModelConfigurationTest,ReActAgentProviderTest' -Dsurefire.failIfNoSpecifiedTests=false test
scripts/resolveops/check.sh -DskipTests compile
cd frontend
npx tsc --noEmit -p tsconfig.app.json
npm run build
```

Passing checks: SupportSchemaTest (2), SupportAccessTest (4), SupportRunStoreTest (3), SupportHttpTest (3), SupportDiagnosisValidatorTest (4), RunbookEvidenceSearchTest (3), SupportReadToolsTest (6), SupportRunServiceTest (6), SupportAgentProviderTest (1), LocalModelConfigurationTest (1), upstream ReActAgentProviderTest (11): 44 total. Three SupportInvestigationIntegrationTest checks are skipped in this ordinary command. With the explicit local database/model flags, those three checks also passed: two real-model probes and one cancellation fault-injection check. No model-success percentage is claimed.

## First-iteration limitations

- The workbench can submit/cancel and poll opt-in investigations. The feature is disabled by default and requires existing application infrastructure; full logged-in browser acceptance is pending. There is no approval/repair action.
- The support investigator uses its own four read tools and per-run state. General Ragent chat behavior is unchanged. Collection and evidence-only structured finalization reuse AgentScope's runtime.
- The strict support runbook path currently supports the existing PG retriever only. General hybrid retrieval and fallback behavior are unchanged.
- Domain tests use real native PostgreSQL 17.11; runbook adapter unit checks use doubles. The new two model probes use real authorization, queries and persistence with synthetic business data; no live embedding/runbook index or complete browser login was tested.
- The optional Docker image pull stalled locally and was interrupted. The Compose file was not verified by a successful container start; native PostgreSQL tests passed independently.
- No production customer data, measured time saving, adoption, or real-model success rate is claimed.

## Local-model deployment increment (2026-11-02)

Generation endpoint is `http://127.0.0.1:11434`, model `resolveops-qwen3:4b`, with 8K context, a 1024-token output cap, single-service concurrency and cloud features disabled. The `local-model` Spring profile is opt-in and overrides generation only. No Docker or paid-model API was used. G3 remains AMBER and G6 remains PLANNED: two real-model compatibility checks are not an investigation benchmark. Exact setup, failure observations and raw-evidence locations are in [LOCAL_MODEL.md](LOCAL_MODEL.md).

## Investigator increment (2026-11-03)

The original Qwen configuration remains the chat default; an optional local Llama 3.2 3B alias passed the two development investigation probes after harness changes. This is not a controlled model comparison. Use `RESOLVEOPS_LOCAL_MODEL=resolveops-llama:3b`, `RESOLVEOPS_REASONING_EFFORT=none` and explicitly enable `RESOLVEOPS_INVESTIGATION_ENABLED` only after configuring the rest of Ragent. See [INVESTIGATION.md](INVESTIGATION.md) for source boundaries, exact opt-in checks, failure analysis and remaining quality issues. No repair operations, production customer data or paid API calls were used. G3 is deliberately not promoted to GREEN.
