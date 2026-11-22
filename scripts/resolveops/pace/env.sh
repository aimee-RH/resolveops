# Shared paths for the PACE scripts. Sourced, not executed.
if [[ -z "${ROPS_BASE:-}" ]]; then
  if [[ -d "$HOME/scratch" ]]; then ROPS_BASE="$HOME/scratch/resolveops-env"; else ROPS_BASE="$HOME/resolveops-env"; fi
fi
export ROPS_BASE
export ROPS_ENV="$ROPS_BASE/conda"
export ROPS_OLLAMA_DIR="$ROPS_BASE/ollama"
export ROPS_OLLAMA_VERSION="${ROPS_OLLAMA_VERSION:-0.40.13}"
export OLLAMA_MODELS="$ROPS_BASE/ollama-models"
export PATH="$ROPS_ENV/bin:$ROPS_OLLAMA_DIR/bin:$PATH"
if [[ -d "$ROPS_ENV/lib/jvm" ]]; then export JAVA_HOME="$ROPS_ENV/lib/jvm"; else export JAVA_HOME="$ROPS_ENV"; fi
