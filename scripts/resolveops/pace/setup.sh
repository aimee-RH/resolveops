#!/usr/bin/env bash
# One-time setup on a PACE login node: Java 17, Maven, PostgreSQL, Ollama and both models,
# all in user space. Downloads happen here because compute nodes may not reach the internet.
# Re-running is safe; finished parts are skipped.
#
#   bash scripts/resolveops/pace/setup.sh
#
# ROPS_BASE (default ~/scratch/resolveops-env, or ~/resolveops-env without scratch) holds the
# tools and model weights, about 8 GB.
set -euo pipefail
repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"
cd "$repo_root"
# shellcheck source=env.sh
source scripts/resolveops/pace/env.sh

log() { printf '\n== %s\n' "$*"; }

log "Base directory: $ROPS_BASE"
mkdir -p "$ROPS_BASE" "$OLLAMA_MODELS"

# 1. Java 17, Maven, PostgreSQL 17 and Python from conda-forge.
if [[ ! -x "$ROPS_ENV/bin/java" || ! -x "$ROPS_ENV/bin/initdb" || ! -x "$ROPS_ENV/bin/zstd" ]]; then
  if ! command -v conda >/dev/null 2>&1; then
    module load anaconda3 2>/dev/null || true
  fi
  command -v conda >/dev/null 2>&1 || { echo "conda not found; run 'module load anaconda3' first" >&2; exit 1; }
  log "Creating conda env $ROPS_ENV (a few minutes)"
  conda create -y -p "$ROPS_ENV" -c conda-forge --override-channels \
    openjdk=17 maven postgresql=17 python=3.11 zstd
fi
"$ROPS_ENV/bin/java" -version 2>&1 | head -1
"$ROPS_ENV/bin/postgres" --version

# 2. Ollama, the same version as on the Mac where v1 ran.
if [[ ! -x "$ROPS_OLLAMA_DIR/bin/ollama" ]]; then
  log "Downloading Ollama $ROPS_OLLAMA_VERSION"
  mkdir -p "$ROPS_OLLAMA_DIR"
  url="https://github.com/ollama/ollama/releases/download/v$ROPS_OLLAMA_VERSION"
  if curl -fsSL --retry 3 -o "$ROPS_BASE/ollama.tar.zst" "$url/ollama-linux-amd64.tar.zst"; then
    "$ROPS_ENV/bin/zstd" -dc "$ROPS_BASE/ollama.tar.zst" | tar -x -C "$ROPS_OLLAMA_DIR"
  else
    curl -fL --retry 3 -o "$ROPS_BASE/ollama.tgz" "$url/ollama-linux-amd64.tgz"
    tar -xzf "$ROPS_BASE/ollama.tgz" -C "$ROPS_OLLAMA_DIR"
  fi
  rm -f "$ROPS_BASE"/ollama.tgz "$ROPS_BASE"/ollama.tar.zst
fi
"$ROPS_OLLAMA_DIR/bin/ollama" --version 2>&1 | tail -1 || true

# 3. Model weights. A temporary server on a side port so we never clash with another
#    user's Ollama on the shared login node. CPU only; this step only downloads.
log "Pulling models into $OLLAMA_MODELS"
pull_port=11435
OLLAMA_HOST="127.0.0.1:$pull_port" "$ROPS_OLLAMA_DIR/bin/ollama" serve >"$ROPS_BASE/ollama-setup.log" 2>&1 &
pull_pid=$!
trap 'kill $pull_pid 2>/dev/null || true' EXIT
for _ in $(seq 60); do
  curl -sf "http://127.0.0.1:$pull_port/api/version" >/dev/null && break
  sleep 1
done
ol() { OLLAMA_HOST="127.0.0.1:$pull_port" "$ROPS_OLLAMA_DIR/bin/ollama" "$@"; }
ol pull llama3.2:3b
ol create resolveops-llama:3b -f resources/ollama/Modelfile.llama
ol pull qwen3:4b
ol create resolveops-qwen3:4b -f resources/ollama/Modelfile
ol list
kill "$pull_pid" 2>/dev/null || true
trap - EXIT

# 4. Maven dependencies. Login nodes cap memory per user and may kill a full compile, so this
#    is best effort with a small heap; eval.sbatch compiles again on the compute node anyway.
log "Warming the Maven cache (best effort)"
if MAVEN_OPTS="-Xmx1g -XX:TieredStopAtLevel=1" scripts/resolveops/check.sh -T1 -pl agent -am \
     -Dtest=LocalModelConfigurationTest -Dsurefire.failIfNoSpecifiedTests=false test -q; then
  echo "Maven cache ready"
else
  echo "warning: Maven warm-up did not finish here; the job will compile and download on the compute node" >&2
fi

# 5. Scorer tests and dataset freeze check.
log "Python tests"
PYTHONDONTWRITEBYTECODE=1 python3 -m unittest discover -s eval/resolveops -p 'test_*.py'
python3 eval/resolveops/build_cases_v2.py --check

log "Setup done. Submit the evaluation with: sbatch scripts/resolveops/pace/eval.sbatch"
