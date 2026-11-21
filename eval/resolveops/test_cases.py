import json
import unittest
from collections import Counter
from pathlib import Path

import build_cases_v2
from policy import label
from run import grade

HERE = Path(__file__).resolve().parent
RUNBOOK_STATES = {"registered", "unregistered", "registered-truncated"}


def evidence_for(case):
    """Evidence kinds the Java eval records for a case (mirrors SupportDiagnosisEvalTest.record)."""
    kinds = ["CASE"]
    for field, kind in (("subscription", "SUBSCRIPTION"), ("events", "EVENTS"),
                        ("integration", "INTEGRATION"), ("denials", "API_DENIALS"), ("runbook", "RUNBOOK")):
        if field in case:
            kinds.append(kind)
    return [{"id": f"e{i}", "kind": kind} for i, kind in enumerate(kinds)]


def ideal_output(case, evidence):
    """A diagnosis that follows the policy exactly, used to prove every gold label is reachable."""
    gold = case["gold"]
    by_kind = {e["kind"]: e["id"] for e in evidence}
    cited = [by_kind[k] for k in gold["refs"] if k != "RUNBOOK"] or [by_kind["CASE"]]
    repair = gold["outcome"] == "PROPOSE_REPAIR"
    return json.dumps({
        "outcome": gold["outcome"],
        "facts": [{"text": "Observed evidence.", "evidenceIds": cited}],
        "hypotheses": [],
        "missingInformation": ["More evidence"] if gold["outcome"] == "NEEDS_INFORMATION" else [],
        "recommendedAction": {"type": "RECONCILE_ACTIVATION"} if repair else None,
        "runbookEvidenceIds": [by_kind["RUNBOOK"]] if repair else [],
    })


class DatasetTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.v1 = json.loads((HERE / "cases.v1.json").read_text())["cases"]
        cls.v2 = json.loads((HERE / "cases.v2.json").read_text())["cases"]

    def test_reference_policy_matches_hand_written_v1_labels(self):
        for case in self.v1:
            self.assertEqual(case["gold"], label(case), case["id"])

    def test_v2_file_is_frozen_output_of_generator(self):
        self.assertEqual((HERE / "cases.v2.json").read_text(), build_cases_v2.render(build_cases_v2.build()))

    def test_v2_labels_follow_policy_and_v1_cases_are_unchanged(self):
        v1 = {c["id"]: c for c in self.v1}
        for case in self.v2:
            self.assertEqual(case["gold"], label(case), case["id"])
            if case.get("origin") == "v1":
                original = {k: v for k, v in case.items() if k not in ("category", "origin")}
                self.assertEqual(original, v1[case["id"]])

    def test_v2_fields_are_ones_the_java_eval_understands(self):
        allowed = {"id", "split", "scenario", "category", "origin", "gold", "description", "subscription",
                   "events", "truncated", "runbook", "integration", "denials", "denialsTruncated"}
        ids = [c["id"] for c in self.v2]
        self.assertEqual(len(ids), len(set(ids)))
        for case in self.v2:
            self.assertLessEqual(set(case), allowed, case["id"])
            self.assertIn(case["split"], {"dev", "heldout"})
            if "runbook" in case:
                self.assertIn(case["runbook"], RUNBOOK_STATES)
            if "description" in case:
                self.assertTrue(0 < len(case["description"]) <= 1000)

    def test_held_out_split_covers_every_category_and_outcome(self):
        held = [c for c in self.v2 if c["split"] == "heldout"]
        self.assertGreaterEqual(len(held), 60)
        categories = Counter(c["category"] for c in held)
        for category in ("repairable", "runbook_missing", "events_incomplete", "not_paid", "already_active",
                         "no_subscription", "injection", "customer_claim", "api_handoff", "api_needs_info",
                         "api_injection"):
            self.assertGreaterEqual(categories[category], 3, category)
        outcomes = Counter(c["gold"]["outcome"] for c in held)
        self.assertEqual(set(outcomes), {"PROPOSE_REPAIR", "HANDOFF", "NO_ACTION_NEEDED", "NEEDS_INFORMATION"})
        self.assertGreaterEqual(outcomes["PROPOSE_REPAIR"], 8)

    def test_injected_text_never_changes_the_gold_label(self):
        for case in self.v2:
            if "injection" in case["category"] or case["category"] == "customer_claim":
                plain = {k: v for k, v in case.items() if k != "description"}
                self.assertEqual(label(plain), case["gold"], case["id"])

    def test_every_gold_label_is_reachable_by_a_policy_following_answer(self):
        for case in self.v1 + self.v2:
            evidence = evidence_for(case)
            row = {"evidence": evidence, "output": ideal_output(case, evidence)}
            self.assertTrue(grade(row, case["gold"])["success"], case["id"])


if __name__ == "__main__":
    unittest.main()
