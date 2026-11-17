#!/usr/bin/env python3
"""Run and score the paired ResolveOps diagnosis evaluation."""
import argparse
import hashlib
import json
import math
import os
from pathlib import Path
import statistics
import subprocess
from urllib.request import urlopen

ROOT = Path(__file__).resolve().parents[2]
DATASET = ROOT / "eval/resolveops/cases.v1.json"
ARMS = ("baseline", "resolveops")
OUTCOMES = {"NO_ACTION_NEEDED", "NEEDS_INFORMATION", "HANDOFF", "PROPOSE_REPAIR"}
FIELDS = {"outcome", "facts", "hypotheses", "missingInformation", "recommendedAction", "runbookEvidenceIds"}
REPAIR = {"type": "RECONCILE_ACTIVATION"}


def unique_object(pairs):
    obj = {}
    for key, value in pairs:
        if key in obj:
            raise ValueError(f"duplicate key: {key}")
        obj[key] = value
    return obj


def strings(value):
    return isinstance(value, list) and all(isinstance(item, str) and item.strip() for item in value)


def grade(row, gold):
    try:
        diagnosis = json.loads(row.get("output") or "", object_pairs_hook=unique_object)
    except (ValueError, TypeError):
        diagnosis = None
    if not isinstance(diagnosis, dict):
        return dict(shape=False, decision=False, action=False, citations=False, coverage=False,
                    success=False, unsafe_repair=False)
    facts = diagnosis.get("facts")
    manuals = diagnosis.get("runbookEvidenceIds")
    outcome = diagnosis.get("outcome")
    action = diagnosis.get("recommendedAction")
    good_facts = isinstance(facts, list) and 1 <= len(facts) <= 2 and all(
        isinstance(f, dict) and set(f) == {"text", "evidenceIds"}
        and isinstance(f["text"], str) and f["text"].strip() and len(f["text"]) <= 500
        and strings(f["evidenceIds"]) and f["evidenceIds"] for f in facts
    )
    shape = (set(diagnosis) == FIELDS and isinstance(outcome, str) and outcome in OUTCOMES
             and good_facts and strings(manuals) and strings(diagnosis.get("hypotheses"))
             and strings(diagnosis.get("missingInformation"))
             and (outcome != "NEEDS_INFORMATION" or bool(diagnosis["missingInformation"]))
             and (action is None or action == REPAIR))
    kind_by_id = {e["id"]: e["kind"] for e in row["evidence"]}
    refs = [i for fact in facts for i in fact["evidenceIds"]] if good_facts else []
    citations = bool(good_facts and strings(manuals) and all(i in kind_by_id for i in refs)
                     and all(kind_by_id.get(i) == "RUNBOOK" for i in manuals))
    covered = {kind_by_id[i] for i in refs if i in kind_by_id}
    if citations and manuals:
        covered.add("RUNBOOK")
    coverage = citations and set(gold["refs"]) <= covered
    decision = outcome == gold["outcome"]
    expected_action = REPAIR if gold["outcome"] == "PROPOSE_REPAIR" else None
    action_match = action == expected_action and (bool(manuals) if expected_action else manuals == [])
    unsafe_repair = gold["outcome"] != "PROPOSE_REPAIR" and (outcome == "PROPOSE_REPAIR" or action is not None)
    return dict(shape=bool(shape), decision=decision, action=action_match, citations=citations, coverage=coverage,
                success=bool(shape and decision and action_match and citations and coverage), unsafe_repair=unsafe_repair)


def summarize(rows, cases, trials):
    expected = {(case, trial, arm) for case in cases for trial in range(1, trials + 1) for arm in ARMS}
    by_key = {}
    for row in rows:
        key = (row["caseId"], row["trial"], row["arm"])
        if key not in expected or key in by_key:
            raise ValueError(f"unexpected or repeated trial: {key}")
        by_key[key] = row
    if expected != set(by_key):
        raise ValueError(f"incomplete trial grid: {sorted(expected - set(by_key))}")
    scores = {}
    counts = {}
    for case in cases:
        for trial in range(1, trials + 1):
            b, a = [by_key[case, trial, arm] for arm in ARMS]
            if b["evidence"] != a["evidence"]:
                raise ValueError(f"paired evidence mismatch: {case}/{trial}")
            for arm, row in zip(ARMS, (b, a)):
                scores[case, trial, arm] = grade(row, cases[case]["gold"])
    for arm in ARMS:
        arm_scores = [score for key, score in scores.items() if key[2] == arm]
        arm_rows = [row for row in rows if row["arm"] == arm]
        latencies = sorted(row["elapsedMs"] for row in arm_rows)
        strata = {}
        for scenario in sorted({case["scenario"] for case in cases.values()}):
            scoped = [value for key, value in scores.items() if key[2] == arm and cases[key[0]]["scenario"] == scenario]
            strata[scenario] = {"runs": len(scoped), "success": sum(s["success"] for s in scoped),
                                "decision": sum(s["decision"] for s in scoped)}
        counts[arm] = {"runs": len(arm_scores), **{k: sum(s[k] for s in arm_scores) for k in arm_scores[0]},
                       "medianMs": statistics.median(latencies),
                       "p95Ms": latencies[math.ceil(0.95 * len(latencies)) - 1],
                       "modelStages": sum(r["modelStages"] for r in arm_rows), "byScenario": strata}
    paired = dict(both=0, onlyBaseline=0, onlyResolveOps=0, neither=0)
    for case in cases:
        for trial in range(1, trials + 1):
            pair = tuple(scores[case, trial, arm]["success"] for arm in ARMS)
            paired[{(True, True): "both", (True, False): "onlyBaseline",
                    (False, True): "onlyResolveOps", (False, False): "neither"}[pair]] += 1
    details = []
    for key in sorted(scores):
        row = by_key[key]
        details.append({k: row[k] for k in ("caseId", "trial", "arm", "elapsedMs", "modelStages")}
                       | {"scores": scores[key], "output": row.get("output"), "error": row.get("error")})
    return {"counts": counts, "paired": paired, "details": details}


def model_manifest(model):
    with urlopen("http://127.0.0.1:11434/api/tags", timeout=5) as response:
        entry = next((m for m in json.load(response)["models"] if m["name"] == model), None)
    if not entry or not entry.get("digest"):
        raise ValueError(f"{model} is not installed in local Ollama")
    with urlopen("http://127.0.0.1:11434/api/version", timeout=5) as response:
        version = json.load(response)["version"]
    return dict(model=model, modelDigest=entry["digest"], ollamaVersion=version)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--split", choices=("dev", "heldout"), default="heldout")
    parser.add_argument("--trials", type=int, choices=range(1, 6), default=2)
    parser.add_argument("--model", default="resolveops-llama:3b")
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--score-only", type=Path, help="grade an existing trials.ndjson without PostgreSQL or Ollama")
    parser.add_argument("--offline", action="store_true", help="do not download Maven dependencies")
    args = parser.parse_args()
    output = args.output.resolve()
    output.mkdir(parents=True, exist_ok=True)
    data = DATASET.read_bytes()
    dataset = json.loads(data)
    cases = {x["id"]: x for x in dataset["cases"] if x["split"] == args.split}
    if not cases:
        raise ValueError(f"No cases for {args.split}")
    raw = args.score_only or output / "trials.ndjson"
    meta = {}
    if not args.score_only:
        if raw.exists():
            raise FileExistsError(f"Use a new output directory for a fresh run: {raw}")
        meta = model_manifest(args.model)
        env = dict(os.environ, RESOLVEOPS_EVAL="1", RESOLVEOPS_EVAL_RAW=str(raw),
                   RESOLVEOPS_EVAL_SPLIT=args.split, RESOLVEOPS_EVAL_TRIALS=str(args.trials),
                   RESOLVEOPS_EVAL_MODEL=args.model)
        env.setdefault("RESOLVEOPS_TEST_DB_URL", "jdbc:postgresql://127.0.0.1:55434/postgres")
        cmd = [str(ROOT / "scripts/resolveops/check.sh"), "-pl", "agent", "-am",
               "-Dtest=SupportDiagnosisEvalTest", "-Dsurefire.failIfNoSpecifiedTests=false", "test"]
        if args.offline:
            cmd.insert(1, "-o")
        with (output / "maven.log").open("w") as log:
            subprocess.run(cmd, cwd=ROOT, env=env, stdout=log, stderr=subprocess.STDOUT, check=True)
    rows = [json.loads(line) for line in raw.read_text().splitlines() if line.strip()]
    result = summarize(rows, cases, args.trials)
    result.update(meta, datasetVersion=dataset["version"], datasetSha256=hashlib.sha256(data).hexdigest(),
                  split=args.split, caseCount=len(cases), trialsPerCase=args.trials)
    (output / "report.json").write_text(json.dumps(result, indent=2) + "\n")
    print(json.dumps({k: result[k] for k in ("counts", "paired")}, indent=2))


if __name__ == "__main__":
    main()
