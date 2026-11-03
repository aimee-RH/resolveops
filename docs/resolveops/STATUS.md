# Verification status

This file records checks, not planned capabilities.

| Gate | Status | Evidence / next requirement |
|---|---|---|
| G0 | GREEN | Public fork and pinned base verified. Small commits pushed to `codex/resolveops`; draft [PR #1](https://github.com/aimee-RH/resolveops/pull/1) targets this fork's main. Existing Git credentials work; connector write is not needed. |
| G1 | GREEN (local) | All nine Maven modules compile; the original 19 ResolveOps checks, one local-model configuration check and 11 upstream AgentProvider checks pass. Frontend strict TypeScript check/build passed in M1; frontend unchanged in this increment. Inspect [remote checks](https://github.com/aimee-RH/resolveops/pull/1/checks) for the current pushed revision. |
| G2 | GREEN (read boundary) | Eleven PostgreSQL-backed domain/HTTP tests cover tenant grants, forged/revoked scope, request deduplication, evidence snapshots and missing identity. HTTP tests supply the trusted upstream login context; full browser-to-SaToken login is not exercised. |
| G3 | AMBER | Local Ollama/Qwen3 4B deployed; real JSON and streaming AgentScope-to-support-tool compatibility checks pass using synthetic data and mocked business services. Five native-tool and three scoped-runbook adapter tests also pass. Full investigator orchestration, diagnosis validation, real authorization/persistence-to-model and live pgvector ingestion/embedding remain pending. See [model deployment evidence](LOCAL_MODEL.md). |
| G4 | PLANNED | Tables reserved; proposal/approval/execution services and write endpoints are not implemented. |
| G5 | PLANNED | API integration/log tables reserved; its investigation tools and flow are not implemented. |
| G6 | PLANNED | No held-out model evaluation or Python runner yet. Nineteen engineering tests are not a task-success benchmark. |
| G7 | PLANNED | Owner walkthrough, live workbench acceptance, user feedback and actual outcome metrics pending. |

## Verification commands (2026-11-02)

```bash
scripts/resolveops/check.sh -pl agent -am '-Dtest=Support*Test,RunbookEvidenceSearchTest' -Dsurefire.failIfNoSpecifiedTests=false test
scripts/resolveops/check.sh -DskipTests compile
cd frontend
npx tsc --noEmit -p tsconfig.app.json
npm run build
```

Test suites: SupportSchemaTest (2), SupportAccessTest (4), SupportRunStoreTest (2), SupportHttpTest (3), RunbookEvidenceSearchTest (3), SupportReadToolsTest (5). All have zero failures/errors/skips. The earlier upstream framework suite also passed during local development, but is not included in the 19 new-test count.

## First-iteration limitations

- The workbench displays authorized cases and persisted evidence only. There is no start-investigation or approval/repair action yet.
- Native read tools are prepared but not registered into a live support investigator. General Ragent chat behavior is unchanged.
- The strict support runbook path currently supports the existing PG retriever only. General hybrid retrieval and fallback behavior are unchanged.
- Domain tests use a real native PostgreSQL 17.11 process; runbook adapters use unit doubles. Local-model compatibility tests call the actual model but use mocked authorization/persistence and synthetic business data. No live embedding or full support investigation was tested.
- The optional Docker image pull stalled locally and was interrupted. The Compose file was not verified by a successful container start; native PostgreSQL tests passed independently.
- No production customer data, measured time saving, adoption, or real-model success rate is claimed.

## Local-model deployment increment (2026-11-02)

Generation endpoint is `http://127.0.0.1:11434`, model `resolveops-qwen3:4b`, with 8K context, a 1024-token output cap, single-service concurrency and cloud features disabled. The `local-model` Spring profile is opt-in and overrides generation only. No Docker or paid-model API was used. G3 remains AMBER and G6 remains PLANNED: two real-model compatibility checks are not an investigation benchmark. Exact setup, failure observations and raw-evidence locations are in [LOCAL_MODEL.md](LOCAL_MODEL.md).
