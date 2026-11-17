# M1 owner walkthrough

Follow the small commits in [PR #1](https://github.com/aimee-RH/resolveops/pull/1/commits). Reproduce a test before treating an AI-assisted implementation as a personally understood resume claim.

1. Explain why customer tenants and support users are different entities. Find the grant lookup in `support/.../access/SupportAccessService.java`.
2. Run `SupportAccessTest` and change its attempted resource to another tenant. Explain the uniform forbidden/missing response.
3. Follow `SupportCaseController.actor()` to the existing Ragent login context. Explain why arbitrary `X-User-Id` headers do not authenticate requests.
4. Find the request-key unique constraint. Explain why repeated creation returns one run and changed content conflicts.
5. Run `SupportRunStoreTest`: the subscription changes, but the saved evidence remains the original observed snapshot.
6. Find `SupportReadTools`: point to the runtime scope check, permitted parameters, read-only declaration, call budget and sanitized error.
7. Find `RunbookEvidenceSearchService`: show where the collection allowlist reaches the storage request and why an empty allowlist never invokes retrieval.
8. Run the indexed test in [the runbook guide](RUNBOOKS.md). Show why the same retrieved text produces handoff until the exact document and hash are registered. Explain which fields the model writes and which ones the backend decides.
9. Open `/support` only after a normal Ragent login and proper database initialization. Describe empty, unauthorized and failed states; no synthetic investigation is presented as real.
10. Reproduce the [repair checks](REPAIR.md). Explain why approval and execution are separate, which version prevents races, how a lost response is recovered, and why the audit failure rolls back the subscription update.
11. Reproduce the three [API investigation probes](API_INVESTIGATION.md). Show which tool and evidence kinds change, and explain why the API diagnosis states a recorded observation and asks for missing evidence when the window is empty.

Use the [investigation record](INVESTIGATION.md) to walk through tool calling, fact generation, citation validation and backend policy. Explain what the development probes establish and what a held-out evaluation would measure.
