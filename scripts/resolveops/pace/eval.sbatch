#!/usr/bin/env bash
# G6 diagnosis evaluation v2 on one PACE GPU node (EVALUATION_V2.md steps 3-6).
# Run setup.sh once on a login node first, then from the repository root:
#
#   sbatch scripts/resolveops/pace/eval.sbatch
#
# Options (environment variables, pass with sbatch --export=ALL,NAME=value):
#   ROPS_STEPS   comma list of dev,t0,t07,qwen   (default: all four, in that order)
#   ROPS_TRIALS  trials per held-out case        (default: 5)
#
# Each step writes eval/results/v2-<step>/. A step whose report.json exists is skipped,
# so resubmitting after a timeout continues with the unfinished steps.
#SBATCH -J resolveops-eval
#SBATCH -N 1
#SBATCH --ntasks-per-node=1
#SBATCH --cpus-per-task=8
#SBATCH --mem=32G
#SBATCH --gres=gpu:1
#SBATCH -t 08:00:00
#SBATCH -o resolveops-eval-%j.log
set -euo pipefail
cd "${SLURM_SUBMIT_DIR:-$(pwd)}"
[[ -f eval/resolveops/run.py ]] || { echo "Submit from the resolveops repository root" >&2; exit 1; }
source scripts/resolveops/pace/env.sh
steps="${ROPS_STEPS:-dev,t0,t07,qwen}"
trials="${ROPS_TRIALS:-5}"

echo "host $(hostname)  job ${SLURM_JOB_ID:-local}  commit $(git rev-parse --short HEAD)  steps $steps  trials $trials"
nvidia-smi -L || echo "warning: no GPU visible; Ollama will run on CPU"

work="${TMPDIR:-/tmp}/resolveops-$USER-${SLURM_JOB_ID:-$$}"
mkdir -p "$work"
cleanup() {
  [[ -n "${ollama_pid:-}" ]] && kill "$ollama_pid" 2>/dev/null || true
  [[ -d "$work/pg" ]] && pg_ctl -D "$work/pg" -m fast stop >/dev/null 2>&1 || true
  rm -rf "$work"
}
trap cleanup EXIT

port_free() { ! (exec 3<>"/dev/tcp/127.0.0.1/$1") 2>/dev/null; }
for port in 55434 11434; do
  port_free "$port" || { echo "Port $port is already in use on $(hostname); resubmit to get another node" >&2; exit 1; }
done

# PostgreSQL: throwaway cluster on node-local disk; the eval connects as postgres/postgres.
echo postgres >"$work/pw"
initdb -D "$work/pg" -U postgres --pwfile="$work/pw" --auth-local=trust --auth-host=scram-sha-256 >/dev/null
pg_ctl -D "$work/pg" -l "$work/pg.log" -w start \
  -o "-h 127.0.0.1 -p 55434 -k $work -c shared_buffers=128MB -c max_connections=20" >/dev/null
echo "PostgreSQL $(postgres --version | awk '{print $NF}') on 127.0.0.1:55434"

# Ollama on the port the eval expects; same limits as scripts/resolveops/local-model.sh.
OLLAMA_HOST=127.0.0.1:11434 OLLAMA_CONTEXT_LENGTH=8192 OLLAMA_NUM_PARALLEL=1 \
  OLLAMA_MAX_LOADED_MODELS=1 OLLAMA_KEEP_ALIVE=10m OLLAMA_NO_CLOUD=1 \
  ollama serve >"$work/ollama.log" 2>&1 &
ollama_pid=$!
for _ in $(seq 120); do curl -sf http://127.0.0.1:11434/api/version >/dev/null && break; sleep 1; done
curl -sf http://127.0.0.1:11434/api/version || { cat "$work/ollama.log"; exit 1; }
echo
grep -i -m3 -E "inference compute|cuda|no compatible GPUs" "$work/ollama.log" || true

# Compile and resolve every Maven plugin once, online, before the timed steps run offline.
echo "== maven: compiling and resolving dependencies"
if ! scripts/resolveops/check.sh -pl agent -am -Dtest=LocalModelConfigurationTest \
     -Dsurefire.failIfNoSpecifiedTests=false test -q >"$work/maven-warmup.log" 2>&1; then
  tail -40 "$work/maven-warmup.log"
  echo "Maven warm-up failed; if it says it cannot reach repo.maven.apache.org, this node has no internet:" >&2
  echo "run setup.sh again on a login node, then resubmit." >&2
  exit 1
fi
echo "== maven: ready"

run_step() {
  local name="$1"; shift
  local out="eval/results/v2-$name"
  if [[ -f "$out/report.json" ]]; then echo "== $name: already done, skipping"; return; fi
  if [[ -e "$out" ]]; then mv "$out" "$out.partial-$(date +%Y%m%d-%H%M%S)"; fi
  echo "== $name: started $(date '+%F %T')"
  local t0=$SECONDS
  python3 eval/resolveops/run.py --dataset eval/resolveops/cases.v2.json --offline --output "$out" "$@"
  echo "== $name: finished in $(( (SECONDS - t0) / 60 )) min; report at $out/report.md"
}

IFS=',' read -r -a step_list <<<"$steps"
for step in "${step_list[@]}"; do
  case "$step" in
    dev)  run_step dev-pace --split dev --trials 1 \
            --arms baseline,baseline-no-policy,validated,resolveops ;;
    t0)   run_step llama-t0 --split heldout --trials "$trials" \
            --arms baseline,baseline-no-policy,validated,resolveops ;;
    t07)  run_step llama-t07 --split heldout --trials "$trials" --temperature 0.7 \
            --arms baseline,validated,resolveops ;;
    qwen) run_step qwen-t0 --split heldout --trials "$trials" --model resolveops-qwen3:4b \
            --arms baseline,validated,resolveops ;;
    *) echo "Unknown step: $step" >&2; exit 2 ;;
  esac
done
echo "All requested steps done $(date '+%F %T')"
