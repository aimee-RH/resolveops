Dataset `resolveops-diagnosis-v2` · split `heldout` · 76 cases × 5 trials · model `resolveops-qwen3:4b` · temperature 0.0

| Metric | baseline | validated | resolveops |
|---|---:|---:|---:|
| All checks pass | 50/380 (13%) | 41/380 (11%) | 298/380 (78%) |
|   95% CI | 10–17% | 8–14% | 74–82% |
| Correct outcome | 193/380 (51%) | 133/380 (35%) | 359/380 (94%) |
| Repair suggested when not allowed | 145/380 (38%) | 0/380 (0%) | 0/380 (0%) |
|   95% CI | 33–43% | 0–1% | 0–1% |
| Rejected by validator | 0/380 (0%) | 164/380 (43%) | 21/380 (6%) |
| Median latency | 1.73 s | 1.90 s | 0.97 s |
| P95 latency | 2.35 s | 2.61 s | 1.69 s |

| pass^k (all k trials pass) | baseline | validated | resolveops |
|---|---:|---:|---:|
| k=1 | 0.13 | 0.11 | 0.78 |
| k=2 | 0.08 | 0.07 | 0.73 |
| k=3 | 0.06 | 0.06 | 0.70 |
| k=4 | 0.06 | 0.06 | 0.68 |
| k=5 | 0.05 | 0.05 | 0.67 |

| Category (all checks pass / unsafe repairs) | baseline | validated | resolveops |
|---|---:|---:|---:|
| already_active | 0/25 · 2 | 0/25 · 0 | 25/25 · 0 |
| api_handoff | 1/60 · 43 | 1/60 · 0 | 60/60 · 0 |
| api_injection | 0/20 · 19 | 0/20 · 0 | 20/20 · 0 |
| api_needs_info | 5/30 · 15 | 5/30 · 0 | 30/30 · 0 |
| customer_claim | 5/20 · 1 | 5/20 · 0 | 18/20 · 0 |
| events_incomplete | 6/40 · 15 | 6/40 · 0 | 16/40 · 0 |
| injection | 1/40 · 19 | 0/40 · 0 | 22/40 · 0 |
| no_subscription | 0/25 · 9 | 0/25 · 0 | 25/25 · 0 |
| not_paid | 5/30 · 7 | 5/30 · 0 | 27/30 · 0 |
| other_activation | 1/15 · 5 | 1/15 · 0 | 14/15 · 0 |
| repairable | 21/45 · 0 | 13/45 · 0 | 27/45 · 0 |
| runbook_missing | 5/30 · 10 | 5/30 · 0 | 14/30 · 0 |

| Paired with resolveops | both pass | only this arm | only resolveops | neither |
|---|---:|---:|---:|---:|
| baseline | 40 | 10 | 258 | 72 |
| validated | 33 | 8 | 265 | 74 |
