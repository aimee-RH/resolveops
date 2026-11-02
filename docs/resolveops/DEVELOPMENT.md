# Local verification

Java 17 and Maven are required. `scripts/resolveops/check.sh` uses a workspace-local dependency cache; set `RESOLVEOPS_MAVEN_REPO` to reuse another cache. It never edits global Maven configuration. On macOS, an existing Homebrew Java 17 is selected only when JAVA_HOME is unset.

```bash
docker compose -p resolveops-dev -f resources/docker/resolveops.compose.yaml up -d --wait
scripts/resolveops/check.sh -pl support -am test
scripts/resolveops/check.sh -DskipTests compile
```

The database binds only to localhost port 55432. Its documented default password is for this isolated local development database. Set `RESOLVEOPS_DB_PASSWORD` to override. Do not use it for shared deployments.

Integration tests start a native PostgreSQL 17.11 process through the test-only [Zonky component](https://github.com/zonkyio/embedded-postgres), on a random port. This is real PostgreSQL, not H2. Set `RESOLVEOPS_TEST_DB_URL`, `RESOLVEOPS_TEST_DB_USER` and `RESOLVEOPS_TEST_DB_PASSWORD` to test an external database instead; an unavailable explicit database fails the tests. Each test creates/drops only its own random schema. Docker Compose remains available for the persistent development database. Real-model configuration is optional and remains deferred.

## Baseline probe

The pinned upstream reactor compiled all eight original modules using Java 17 on 2026-11-02. The initial shell selected a different JAVA_HOME and failed with `release version 17 not supported`; selecting the required JDK fixed it. No upstream dependency versions were changed.
