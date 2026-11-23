Dataset `resolveops-diagnosis-v2` · split `heldout` · 76 cases × 5 trials · model `resolveops-llama:3b` · temperature 0.7

| Metric | baseline | validated | resolveops |
|---|---:|---:|---:|
| All checks pass | 40/380 (11%) | 38/380 (10%) | 362/380 (95%) |
|   95% CI | 8–14% | 7–13% | 93–97% |
| Correct outcome | 85/380 (22%) | 40/380 (11%) | 371/380 (98%) |
| Repair suggested when not allowed | 131/380 (34%) | 0/380 (0%) | 0/380 (0%) |
|   95% CI | 30–39% | 0–1% | 0–1% |
| Rejected by validator | 0/380 (0%) | 167/380 (44%) | 9/380 (2%) |
| Median latency | 1.31 s | 1.57 s | 0.86 s |
| P95 latency | 2.02 s | 2.42 s | 1.58 s |

| pass^k (all k trials pass) | baseline | validated | resolveops |
|---|---:|---:|---:|
| k=1 | 0.11 | 0.10 | 0.95 |
| k=2 | 0.06 | 0.06 | 0.91 |
| k=3 | 0.04 | 0.04 | 0.87 |
| k=4 | 0.03 | 0.03 | 0.84 |
| k=5 | 0.03 | 0.03 | 0.82 |

| Category (all checks pass / unsafe repairs) | baseline | validated | resolveops |
|---|---:|---:|---:|
| already_active | 0/25 · 13 | 0/25 · 0 | 25/25 · 0 |
| api_handoff | 0/60 · 30 | 0/60 · 0 | 60/60 · 0 |
| api_injection | 2/20 · 13 | 2/20 · 0 | 20/20 · 0 |
| api_needs_info | 19/30 · 10 | 19/30 · 0 | 30/30 · 0 |
| customer_claim | 3/20 · 6 | 3/20 · 0 | 20/20 · 0 |
| events_incomplete | 0/40 · 14 | 0/40 · 0 | 36/40 · 0 |
| injection | 2/40 · 14 | 2/40 · 0 | 39/40 · 0 |
| no_subscription | 12/25 · 7 | 12/25 · 0 | 25/25 · 0 |
| not_paid | 0/30 · 10 | 0/30 · 0 | 30/30 · 0 |
| other_activation | 0/15 · 2 | 0/15 · 0 | 13/15 · 0 |
| repairable | 2/45 · 0 | 0/45 · 0 | 37/45 · 0 |
| runbook_missing | 0/30 · 12 | 0/30 · 0 | 27/30 · 0 |

| Paired with resolveops | both pass | only this arm | only resolveops | neither |
|---|---:|---:|---:|---:|
| baseline | 40 | 0 | 322 | 18 |
| validated | 38 | 0 | 324 | 18 |
