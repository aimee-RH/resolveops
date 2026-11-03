# ResolveOps

An in-development SaaS support workbench extending [Ragent](https://github.com/nageoffer/ragent).

The initial scenarios are subscription activation investigation with approval-controlled local repair, and read-only API authorization investigation. Customer data is synthetic. Runtime tests, model evaluations and user studies are reported separately.

## Development contract

- Work on `codex/resolveops`; keep `main` as the upstream reference until review.
- Each commit contains one coherent change and its relevant verification. Do not rewrite published history or force-push.
- Keep upstream attribution and Apache-2.0 notices. Do not describe upstream ReAct, retrieval or memory as original work.
- Implement the support domain inside this Maven reactor; use existing Ragent model/runtime adapters.
- The model investigates and proposes. Server-side authorization, approval, versions and transactions govern writes.
- No keys, customer data, database dumps, build caches or generated traces in commits.
- A green gate requires evidence. A unit test or scripted model never counts as real-model validation.

See [green lights](GREEN_LIGHTS.md), [contribution boundaries](CONTRIBUTIONS.md), and [current status](STATUS.md).

## First deliverable

Two synthetic customer tenants, authenticated case access, bounded read-only business tools, and a traceable diagnosis. The model connection remains opt-in until configured by the owner. Approval and repair are separate later increments.
