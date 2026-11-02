# Development green lights

`GREEN` means every requirement in the row has recorded evidence. `AMBER` means partial implementation or unavailable external prerequisites. `PLANNED` means not verified. Gates do not automatically grant business approval or imply production readiness.

| Gate | Conditions | Required evidence |
|---|---|---|
| G0 — Repository | GitHub fork verified; upstream SHA recorded; isolated branch; small commits; no secrets | Fork URL, commit log, contribution inventory |
| G1 — Build | Reproducible dependency resolution; support module and changed callers compile; appropriate tests pass | Exact commands, dependency versions and test summaries |
| G2 — Read boundary | Two tenants; authenticated access; bounded queries; forbidden/missing resources do not leak; explicit run scope | PostgreSQL-backed authorization and query tests |
| G3 — Real investigation | Actual configured model calls support tools and scoped runbook retrieval; valid evidence for repair/no-action/handoff | Redacted real-model runs, model/config versions; no scripted substitutions |
| G4 — Controlled repair | Immutable proposal; approval role and expiry; recheck access/version; one logical operation; transaction and verification | Concurrency, stale approval, revoked access, rollback and response-loss checks |
| G5 — Second scenario | API investigation shares run/evidence/UI without gaining write permission | Specific error and incomplete-data cases, tool allowlist tests, reuse inventory |
| G6 — Evaluation | Versioned development/held-out tasks; isolated trials; fair baseline; reproducible graders and raw outcomes | Evaluation report; system tests separated from model results |
| G7 — Explainability | Working demo; at least three labeled failure analyses; personally reproducible walkthrough | Source map, contribution list, actual metrics and owner explanation |

## Hard stops

- Cross-tenant disclosure or unapproved/stale repair blocks release regardless of aggregate score.
- Missing model credentials leave G3 amber. The owner explicitly deferred model configuration.
- Unavailable upstream dependencies or infrastructure leave G1/G2 amber; record the original error instead of silently changing the claimed stack.
- A draft PR may contain validated partial increments. Ready-to-review/product completion must say exactly which gates remain open.

## Checkpoints

1. Repository and reproducible build.
2. Domain/read tools with tenant isolation.
3. Controlled repair independently of model availability.
4. Second scenario and evaluation scaffolding.
5. Real-model and user validation after configuration.
