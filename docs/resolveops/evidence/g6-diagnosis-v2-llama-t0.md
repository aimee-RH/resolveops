Dataset `resolveops-diagnosis-v2` · split `heldout` · 76 cases × 5 trials · model `resolveops-llama:3b` · temperature 0.0

| Metric | baseline | baseline-no-policy | validated | resolveops |
|---|---:|---:|---:|---:|
| All checks pass | 63/380 (17%) | 2/380 (1%) | 36/380 (9%) | 380/380 (100%) |
|   95% CI | 13–21% | 0–2% | 7–13% | 99–100% |
| Correct outcome | 118/380 (31%) | 50/380 (13%) | 42/380 (11%) | 380/380 (100%) |
| Repair suggested when not allowed | 162/380 (43%) | 330/380 (87%) | 0/380 (0%) | 0/380 (0%) |
|   95% CI | 38–48% | 83–90% | 0–1% | 0–1% |
| Rejected by validator | 0/380 (0%) | 0/380 (0%) | 192/380 (51%) | 0/380 (0%) |
| Median latency | 1.33 s | 1.37 s | 1.61 s | 0.91 s |
| P95 latency | 1.94 s | 2.00 s | 2.31 s | 1.50 s |

| pass^k (all k trials pass) | baseline | baseline-no-policy | validated | resolveops |
|---|---:|---:|---:|---:|
| k=1 | 0.17 | 0.01 | 0.09 | 1.00 |
| k=2 | 0.13 | 0.00 | 0.09 | 1.00 |
| k=3 | 0.12 | 0.00 | 0.09 | 1.00 |
| k=4 | 0.11 | 0.00 | 0.09 | 1.00 |
| k=5 | 0.11 | 0.00 | 0.09 | 1.00 |

| Category (all checks pass / unsafe repairs) | baseline | baseline-no-policy | validated | resolveops |
|---|---:|---:|---:|---:|
| already_active | 0/25 · 11 | 0/25 · 25 | 0/25 · 0 | 25/25 · 0 |
| api_handoff | 0/60 · 38 | 0/60 · 60 | 0/60 · 0 | 60/60 · 0 |
| api_injection | 0/20 · 20 | 0/20 · 20 | 0/20 · 0 | 20/20 · 0 |
| api_needs_info | 25/30 · 0 | 0/30 · 30 | 25/30 · 0 | 30/30 · 0 |
| customer_claim | 5/20 · 5 | 0/20 · 20 | 5/20 · 0 | 20/20 · 0 |
| events_incomplete | 0/40 · 19 | 0/40 · 40 | 0/40 · 0 | 40/40 · 0 |
| injection | 4/40 · 27 | 2/40 · 35 | 0/40 · 0 | 40/40 · 0 |
| no_subscription | 6/25 · 16 | 0/25 · 25 | 6/25 · 0 | 25/25 · 0 |
| not_paid | 0/30 · 12 | 0/30 · 30 | 0/30 · 0 | 30/30 · 0 |
| other_activation | 0/15 · 4 | 0/15 · 15 | 0/15 · 0 | 15/15 · 0 |
| repairable | 23/45 · 0 | 0/45 · 0 | 0/45 · 0 | 45/45 · 0 |
| runbook_missing | 0/30 · 10 | 0/30 · 30 | 0/30 · 0 | 30/30 · 0 |

| Paired with resolveops | both pass | only this arm | only resolveops | neither |
|---|---:|---:|---:|---:|
| baseline | 63 | 0 | 317 | 0 |
| baseline-no-policy | 2 | 0 | 378 | 0 |
| validated | 36 | 0 | 344 | 0 |
