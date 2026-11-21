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
KNOWN_ARMS = ("baseline", "baseline-no-policy", "validated", "resolveops")
OUTCOMES = {"NO_ACTION_NEEDED", "NEEDS_INFORMATION", "HANDOFF", "PROPOSE_REPAIR"}
FIELDS = {"outcome", "facts", "hypotheses", "missingInformation", "recommendedAction", "runbookEvidenceIds"}
REPAIR = {"type": "RECONCILE_ACTIVATION"}
REJECTED = "INVALID_DIAGNOSIS"


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


def wilson(successes, total, z=1.96):
    """95% Wilson score interval for a proportion, as [low, high]."""
    if total == 0:
        return [0.0, 0.0]
    p = successes / total
    denom = 1 + z * z / total
    centre = (p + z * z / (2 * total)) / denom
    half = z * math.sqrt(p * (1 - p) / total + z * z / (4 * total * total)) / denom
    return [round(max(0.0, centre - half), 4), round(min(1.0, centre + half), 4)]


def pass_hat_k(per_case, trials, ok):
    """pass^k: chance that k trials drawn from n all satisfy `ok`, averaged over cases.

    For a case with c satisfying trials out of n, the unbiased estimate is C(c, k) / C(n, k).
    pass^1 equals the plain rate; larger k rewards consistency rather than luck.
    """
    result = {}
    for k in range(1, trials + 1):
        values = [math.comb(sum(bool(ok(s)) for s in scores), k) / math.comb(trials, k)
                  for scores in per_case.values()]
        result[str(k)] = round(sum(values) / len(values), 4)
    return result


def summarize(rows, cases, trials, arms=ARMS):
    arms = tuple(arms)
    expected = {(case, trial, arm) for case in cases for trial in range(1, trials + 1) for arm in arms}
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
            first = by_key[case, trial, arms[0]]
            for arm in arms:
                row = by_key[case, trial, arm]
                if row["evidence"] != first["evidence"]:
                    raise ValueError(f"paired evidence mismatch: {case}/{trial}")
                scores[case, trial, arm] = grade(row, cases[case]["gold"])
    for arm in arms:
        arm_scores = [score for key, score in scores.items() if key[2] == arm]
        arm_rows = [row for row in rows if row["arm"] == arm]
        latencies = sorted(row["elapsedMs"] for row in arm_rows)
        runs = len(arm_scores)
        totals = {k: sum(s[k] for s in arm_scores) for k in arm_scores[0]}
        per_case = {case: [scores[case, t, arm] for t in range(1, trials + 1)] for case in cases}
        counts[arm] = {
            "runs": runs, **totals,
            "rejected": sum(row.get("error") == REJECTED for row in arm_rows),
            "errors": sum("error" in row for row in arm_rows),
            "successRate95": wilson(totals["success"], runs),
            "unsafeRepairRate95": wilson(totals["unsafe_repair"], runs),
            "passHatK": pass_hat_k(per_case, trials, lambda s: s["success"]),
            "safeHatK": pass_hat_k(per_case, trials, lambda s: not s["unsafe_repair"]),
            "medianMs": statistics.median(latencies),
            "p95Ms": latencies[math.ceil(0.95 * len(latencies)) - 1],
            "modelStages": sum(r["modelStages"] for r in arm_rows),
        }
        for field, group in (("byScenario", "scenario"), ("byCategory", "category"), ("byGoldOutcome", None)):
            strata = {}
            for case_id, case in cases.items():
                name = case["gold"]["outcome"] if group is None else case.get(group, "uncategorized")
                bucket = strata.setdefault(name, {"runs": 0, "success": 0, "decision": 0, "unsafe_repair": 0})
                for t in range(1, trials + 1):
                    s = scores[case_id, t, arm]
                    bucket["runs"] += 1
                    bucket["success"] += s["success"]
                    bucket["decision"] += s["decision"]
                    bucket["unsafe_repair"] += s["unsafe_repair"]
            counts[arm][field] = dict(sorted(strata.items()))
    reference = "resolveops" if "resolveops" in arms else arms[0]
    paired_vs = {}
    for arm in arms:
        if arm == reference:
            continue
        tally = dict(both=0, onlyOther=0, onlyReference=0, neither=0)
        for case in cases:
            for trial in range(1, trials + 1):
                pair = (scores[case, trial, arm]["success"], scores[case, trial, reference]["success"])
                tally[{(True, True): "both", (True, False): "onlyOther",
                       (False, True): "onlyReference", (False, False): "neither"}[pair]] += 1
        paired_vs[arm] = tally
    result = {"arms": list(arms), "counts": counts, "pairedReference": reference, "pairedVs": paired_vs}
    if "baseline" in arms and "resolveops" in arms:
        # Kept in the v1 report shape.
        t = paired_vs["baseline"]
        result["paired"] = dict(both=t["both"], onlyBaseline=t["onlyOther"],
                                onlyResolveOps=t["onlyReference"], neither=t["neither"])
    details = []
    for key in sorted(scores):
        row = by_key[key]
        details.append({k: row[k] for k in ("caseId", "trial", "arm", "elapsedMs", "modelStages")}
                       | {"scores": scores[key], "output": row.get("output"), "error": row.get("error")})
    result["details"] = details
    return result


def _pct(n, d):
    return f"{n}/{d} ({100 * n / d:.0f}%)" if d else "0/0"


def _seconds(ms):
    """Milliseconds as seconds, rounded half up so tables match hand-written docs (2135 ms -> 2.14 s)."""
    return f"{math.floor(ms / 10 + 0.5) / 100:.2f} s"


def _ci(pair):
    return f"{100 * pair[0]:.0f}–{100 * pair[1]:.0f}%"


def markdown(report):
    """Render the main tables of a report as Markdown for docs and the README."""
    arms = report["arms"]
    c = report["counts"]
    lines = [f"Dataset `{report['datasetVersion']}` · split `{report['split']}` · {report['caseCount']} cases × "
             f"{report['trialsPerCase']} trials · model `{report.get('model', 'n/a')}` · "
             f"temperature {report.get('temperature', 0.0)}", ""]
    lines += ["| Metric | " + " | ".join(arms) + " |", "|---|" + "---:|" * len(arms)]
    rows = [
        ("All checks pass", lambda a: _pct(c[a]["success"], c[a]["runs"])),
        ("  95% CI", lambda a: _ci(c[a]["successRate95"])),
        ("Correct outcome", lambda a: _pct(c[a]["decision"], c[a]["runs"])),
        ("Repair suggested when not allowed", lambda a: _pct(c[a]["unsafe_repair"], c[a]["runs"])),
        ("  95% CI", lambda a: _ci(c[a]["unsafeRepairRate95"])),
        ("Rejected by validator", lambda a: _pct(c[a]["rejected"], c[a]["runs"])),
        ("Median latency", lambda a: _seconds(c[a]["medianMs"])),
        ("P95 latency", lambda a: _seconds(c[a]["p95Ms"])),
    ]
    for name, cell in rows:
        lines.append(f"| {name} | " + " | ".join(cell(a) for a in arms) + " |")
    ks = list(c[arms[0]]["passHatK"])
    lines += ["", "| pass^k (all k trials pass) | " + " | ".join(arms) + " |", "|---|" + "---:|" * len(arms)]
    for k in ks:
        lines.append(f"| k={k} | " + " | ".join(f"{c[a]['passHatK'][k]:.2f}" for a in arms) + " |")
    categories = list(c[arms[0]]["byCategory"])
    lines += ["", "| Category (all checks pass / unsafe repairs) | " + " | ".join(arms) + " |",
              "|---|" + "---:|" * len(arms)]
    for cat in categories:
        cells = []
        for a in arms:
            b = c[a]["byCategory"][cat]
            cells.append(f"{b['success']}/{b['runs']} · {b['unsafe_repair']}")
        lines.append(f"| {cat} | " + " | ".join(cells) + " |")
    if report.get("pairedVs"):
        ref = report["pairedReference"]
        lines += ["", f"| Paired with {ref} | both pass | only this arm | only {ref} | neither |", "|---|---:|---:|---:|---:|"]
        for arm, t in report["pairedVs"].items():
            lines.append(f"| {arm} | {t['both']} | {t['onlyOther']} | {t['onlyReference']} | {t['neither']} |")
    return "\n".join(lines) + "\n"


def model_manifest(model):
    with urlopen("http://127.0.0.1:11434/api/tags", timeout=5) as response:
        entry = next((m for m in json.load(response)["models"] if m["name"] == model), None)
    if not entry or not entry.get("digest"):
        raise ValueError(f"{model} is not installed in local Ollama")
    with urlopen("http://127.0.0.1:11434/api/version", timeout=5) as response:
        version = json.load(response)["version"]
    return dict(model=model, modelDigest=entry["digest"], ollamaVersion=version)


def parse_arms(value):
    arms = tuple(a.strip() for a in value.split(",") if a.strip())
    if not arms or len(set(arms)) != len(arms) or not set(arms) <= set(KNOWN_ARMS):
        raise argparse.ArgumentTypeError(f"arms must be distinct values from {', '.join(KNOWN_ARMS)}")
    if "validated" in arms and "baseline" not in arms:
        raise argparse.ArgumentTypeError("validated re-checks baseline output; include baseline")
    return arms


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--dataset", type=Path, default=DATASET, help="case file (default: cases.v1.json)")
    parser.add_argument("--split", choices=("dev", "heldout"), default="heldout")
    parser.add_argument("--trials", type=int, choices=range(1, 11), default=2)
    parser.add_argument("--arms", type=parse_arms, default=ARMS,
                        help="comma-separated arms: " + ", ".join(KNOWN_ARMS) + " (default: baseline,resolveops)")
    parser.add_argument("--temperature", type=float, default=0.0,
                        help="sampling temperature for every model call (default 0.0, as in production)")
    parser.add_argument("--model", default="resolveops-llama:3b")
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--score-only", type=Path, help="grade an existing trials.ndjson without PostgreSQL or Ollama")
    parser.add_argument("--offline", action="store_true", help="do not download Maven dependencies")
    args = parser.parse_args()
    if not 0.0 <= args.temperature <= 2.0:
        parser.error("--temperature must be between 0 and 2")
    output = args.output.resolve()
    output.mkdir(parents=True, exist_ok=True)
    dataset_path = args.dataset.resolve()
    data = dataset_path.read_bytes()
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
                   RESOLVEOPS_EVAL_MODEL=args.model, RESOLVEOPS_EVAL_DATASET=str(dataset_path),
                   RESOLVEOPS_EVAL_ARMS=",".join(args.arms), RESOLVEOPS_EVAL_TEMPERATURE=str(args.temperature))
        env.setdefault("RESOLVEOPS_TEST_DB_URL", "jdbc:postgresql://127.0.0.1:55434/postgres")
        cmd = [str(ROOT / "scripts/resolveops/check.sh"), "-pl", "agent", "-am",
               "-Dtest=SupportDiagnosisEvalTest", "-Dsurefire.failIfNoSpecifiedTests=false", "test"]
        if args.offline:
            cmd.insert(1, "-o")
        with (output / "maven.log").open("w") as log:
            subprocess.run(cmd, cwd=ROOT, env=env, stdout=log, stderr=subprocess.STDOUT, check=True)
    rows = [json.loads(line) for line in raw.read_text().splitlines() if line.strip()]
    result = summarize(rows, cases, args.trials, args.arms)
    result.update(meta, datasetVersion=dataset["version"], datasetSha256=hashlib.sha256(data).hexdigest(),
                  split=args.split, caseCount=len(cases), trialsPerCase=args.trials, temperature=args.temperature)
    (output / "report.json").write_text(json.dumps(result, indent=2) + "\n")
    (output / "report.md").write_text(markdown(result))
    keys = ("counts", "paired") if "paired" in result else ("counts",)
    print(json.dumps({k: {a: {m: v for m, v in result["counts"][a].items() if not m.startswith("by")}
                          for a in result["arms"]} if k == "counts" else result[k] for k in keys}, indent=2))


if __name__ == "__main__":
    main()
