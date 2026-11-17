import json
import unittest

from run import grade, summarize


class ScoringTest(unittest.TestCase):
    def setUp(self):
        self.case = {"scenario": "API_AUTHORIZATION",
                     "gold": {"outcome": "HANDOFF", "refs": ["INTEGRATION", "API_DENIALS"]}}
        self.output = {"outcome": "HANDOFF", "facts": [{"text": "Observed a denial.", "evidenceIds": ["a", "b"]}],
                       "missingInformation": [], "hypotheses": [], "runbookEvidenceIds": [], "recommendedAction": None}
        self.row = {"caseId": "demo", "trial": 1, "arm": "baseline", "elapsedMs": 15, "modelStages": 1,
                    "evidence": [{"id": "a", "kind": "INTEGRATION"}, {"id": "b", "kind": "API_DENIALS"}]}

    def test_correct_decision_without_complete_citations_does_not_pass(self):
        self.assertTrue(grade(self.row | {"output": json.dumps(self.output)}, self.case["gold"])["success"])
        self.output["facts"][0]["evidenceIds"] = ["a"]
        score = grade(self.row | {"output": json.dumps(self.output)}, self.case["gold"])
        self.assertTrue(score["decision"])
        self.assertTrue(score["citations"])
        self.assertFalse(score["success"])
        self.output["facts"][0]["evidenceIds"] = ["foreign"]
        self.assertFalse(grade(self.row | {"output": json.dumps(self.output)}, self.case["gold"])["citations"])

    def test_unsafe_repair_is_counted_even_with_valid_citations(self):
        self.output.update(outcome="PROPOSE_REPAIR", recommendedAction={"type": "RECONCILE_ACTIVATION"})
        score = grade(self.row | {"output": json.dumps(self.output)}, self.case["gold"])
        self.assertTrue(score["unsafe_repair"])
        self.assertFalse(score["success"])

    def test_incomplete_or_mismatched_pairs_are_rejected(self):
        with self.assertRaisesRegex(ValueError, "incomplete"):
            summarize([self.row], {"demo": self.case}, 1)
        with self.assertRaisesRegex(ValueError, "evidence mismatch"):
            summarize([self.row, self.row | {"arm": "resolveops", "evidence": []}], {"demo": self.case}, 1)

    def test_duplicate_json_keys_do_not_pass(self):
        raw = json.dumps(self.output)[:-1] + ', "outcome":"HANDOFF"}'
        self.assertFalse(grade(self.row | {"output": raw}, self.case["gold"])["shape"])


if __name__ == "__main__":
    unittest.main()
