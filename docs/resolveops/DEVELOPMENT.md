# Local verification

Java 17 and Maven are required. `scripts/resolveops/check.sh` uses a workspace-local dependency cache; set `RESOLVEOPS_MAVEN_REPO` to reuse another cache. It never edits global Maven configuration. On macOS, an existing Homebrew Java 17 is selected only when JAVA_HOME is unset.

```bash
scripts/resolveops/check.sh -pl support -am test
scripts/resolveops/check.sh -DskipTests compile
```

The optional Compose database binds only to localhost port 55432. Its documented default password is for this isolated local development database. Set `RESOLVEOPS_DB_PASSWORD` to override. Do not use it for shared deployments. Docker is not required for the tests and is not started by the local-model workflow.

Integration tests start a native PostgreSQL 17.11 process through the test-only [Zonky component](https://github.com/zonkyio/embedded-postgres), on a random port. This is real PostgreSQL, not H2. Set `RESOLVEOPS_TEST_DB_URL`, `RESOLVEOPS_TEST_DB_USER` and `RESOLVEOPS_TEST_DB_PASSWORD` to test an external database instead; an unavailable explicit database fails the tests. Each test creates/drops only its own random schema. Docker Compose remains available for an optional persistent development database.

Local generation now uses Ollama + Qwen3 4B with an opt-in `local-model` profile. See [local model deployment and checks](LOCAL_MODEL.md). This does not configure live embeddings or complete the support investigator.

## Baseline probe

The pinned upstream reactor compiled all eight original modules using Java 17 on 2026-11-02. The initial shell selected a different JAVA_HOME and failed with `release version 17 not supported`; selecting the required JDK fixed it. No upstream dependency versions were changed.

## Workbench integration

The page is at `/support`, with a link in the Agent sidebar. Use the existing Ragent login; do not introduce user/tenant headers or a fixed demo identity. Apply the support SQL upgrade to an initialized upstream database (new full initialization includes it). Create synthetic customer/case rows and grant only the intended tenant/role to an actual authenticated user ID. Tests create their own data and grant rows without changing real users.

The optional Compose file is a business-domain database, not the complete upstream runtime. Live Ragent operation still needs its documented authentication/cache/storage configuration and pgvector for the support runbook adapter. This milestone verified module compilation, service/HTTP boundaries and frontend build, not a complete deployed login-to-model flow.

Investigation submit/cancel endpoints are now opt-in through `support.investigation.enabled`; the default remains disabled. See [the investigation setup and exact probes](INVESTIGATION.md). There are no business repair endpoints. Histories and evidence come from persisted records; the UI does not inject fixtures or simulate a model answer. Complete application login, storage and live vector ingestion are separate prerequisites.
