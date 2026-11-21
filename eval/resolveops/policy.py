"""Reference labels for synthetic diagnosis cases.

This module restates the written business policy (the rules given to the baseline prompt and
documented in docs/resolveops/EVALUATION.md) as plain Python. It is used only to label and check
evaluation data. It does not import or call the Java implementation, so the evaluation remains a
check of the backend against the written policy rather than against itself.
"""

REPAIR_EVENTS = {"PAYMENT_CONFIRMED", "ACTIVATION_FAILED"}
REPAIRABLE_ACTIVATION = {"PENDING", "FAILED"}


def label(case):
    """Return the gold {"outcome", "refs"} for a case definition."""
    if case["scenario"] == "SUBSCRIPTION_ACTIVATION":
        return _subscription(case)
    if case["scenario"] == "API_AUTHORIZATION":
        return _api(case)
    raise ValueError(f"unknown scenario: {case['scenario']}")


def _subscription(case):
    sub = case.get("subscription")
    events = case.get("events")
    if sub is not None and sub[1] == "ACTIVE":
        # Rule 1: an active subscription needs no action, whatever else was observed.
        return {"outcome": "NO_ACTION_NEEDED", "refs": ["SUBSCRIPTION"]}
    if sub is None:
        # Rule 2: no subscription evidence.
        return {"outcome": "NEEDS_INFORMATION", "refs": []}
    paid_pending = sub[0] == "PAID" and sub[1] in REPAIRABLE_ACTIVATION
    timeline = events is not None and not case.get("truncated", False) and REPAIR_EVENTS <= set(events)
    runbook_approved = case.get("runbook") == "registered"
    if paid_pending and timeline and runbook_approved:
        # Rule 3: every repair precondition is present in untruncated evidence.
        return {"outcome": "PROPOSE_REPAIR", "refs": ["SUBSCRIPTION", "EVENTS", "RUNBOOK"]}
    # Rule 4: otherwise hand off. Cite the subscription, and the event log when it has records.
    refs = ["SUBSCRIPTION"] + (["EVENTS"] if events else [])
    return {"outcome": "HANDOFF", "refs": refs}


def _api(case):
    has_integration = "integration" in case
    has_denials = "denials" in case
    refs = (["INTEGRATION"] if has_integration else []) + (["API_DENIALS"] if has_denials else [])
    if not has_integration or not case.get("denials"):
        return {"outcome": "NEEDS_INFORMATION", "refs": refs}
    return {"outcome": "HANDOFF", "refs": refs}
