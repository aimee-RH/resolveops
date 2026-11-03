#!/usr/bin/env bash
set -euo pipefail
repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
task_root="$(cd "$repo_root/../.." && pwd)"
ollama_bin="${RESOLVEOPS_OLLAMA_BIN:-$task_root/work/ollama-0.40.13/ollama}"
if [[ ! -x "$ollama_bin" ]]; then
  ollama_bin="$(command -v ollama || true)"
fi
if [[ -z "$ollama_bin" || ! -x "$ollama_bin" ]]; then
  echo 'Install Ollama or set RESOLVEOPS_OLLAMA_BIN; see docs/resolveops/LOCAL_MODEL.md.' >&2
  exit 1
fi
export OLLAMA_HOST=127.0.0.1:11434
model="${RESOLVEOPS_LOCAL_MODEL:-resolveops-qwen3:4b}"
case "${1:-status}" in
  serve)
    export OLLAMA_MODELS="${RESOLVEOPS_OLLAMA_MODELS:-$task_root/work/ollama-models}"
    export OLLAMA_CONTEXT_LENGTH=8192 OLLAMA_NUM_PARALLEL=1 OLLAMA_MAX_LOADED_MODELS=1
    export OLLAMA_KEEP_ALIVE=5m OLLAMA_NO_CLOUD=1
    mkdir -p "$OLLAMA_MODELS"
    exec "$ollama_bin" serve
    ;;
  setup)
    "$ollama_bin" pull qwen3:4b
    exec "$ollama_bin" create resolveops-qwen3:4b -f "$repo_root/resources/ollama/Modelfile"
    ;;
  setup-llama)
    "$ollama_bin" pull llama3.2:3b
    exec "$ollama_bin" create resolveops-llama:3b -f "$repo_root/resources/ollama/Modelfile.llama"
    ;;
  status)
    "$ollama_bin" list
    exec "$ollama_bin" ps
    ;;
  chat) shift; exec "$ollama_bin" run "$model" "$@" ;;
  unload) exec "$ollama_bin" stop "$model" ;;
  *) echo 'Usage: local-model.sh {serve|setup|setup-llama|status|chat|unload}' >&2; exit 2 ;;
esac
