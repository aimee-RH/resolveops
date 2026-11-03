# M1 owner walkthrough

Follow the small commits in [PR #1](https://github.com/aimee-RH/resolveops/pull/1/commits). Reproduce a test before treating an AI-assisted implementation as a personally understood resume claim.

1. Explain why customer tenants and support users are different entities. Find the grant lookup in `support/.../access/SupportAccessService.java`.
2. Run `SupportAccessTest` and change its attempted resource to another tenant. Explain the uniform forbidden/missing response.
3. Follow `SupportCaseController.actor()` to the existing Ragent login context. Explain why arbitrary `X-User-Id` headers do not authenticate requests.
4. Find the request-key unique constraint. Explain why repeated creation returns one run and changed content conflicts.
5. Run `SupportRunStoreTest`: the subscription changes, but the saved evidence remains the original observed snapshot.
6. Find `SupportReadTools`: point to the runtime scope check, permitted parameters, read-only declaration, call budget and sanitized error.
7. Find `RunbookEvidenceSearchService`: show where the collection allowlist reaches the storage request and why an empty allowlist never invokes retrieval.
8. Explain the difference between a unit-tested retrieval adapter and live pgvector/model validation. G3 remains open.
9. Open `/support` only after a normal Ragent login and proper database initialization. Describe empty, unauthorized and failed states; no synthetic investigation is presented as real.
10. Explain why proposal/operation tables do not mean approval or repair is implemented. Those services and their failure checks belong to G4.

The next independent increment is the opt-in investigator, final-output validation and live runbook configuration. [Local Qwen3 4B](LOCAL_MODEL.md) provides generation without paid API credentials; optional cloud credentials remain owner-supplied and excluded from Git. The independent controlled-write increment can follow with no model-driven execution permissions.
