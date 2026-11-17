#!/usr/bin/env bash
set -euo pipefail
repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$repo_root"
if [[ -z "${JAVA_HOME:-}" ]] && [[ -d /opt/homebrew/opt/openjdk@17 ]]; then
  export JAVA_HOME=/opt/homebrew/opt/openjdk@17
fi
repo_cache="${RESOLVEOPS_MAVEN_REPO:-$repo_root/.resolveops/maven}"
exec mvn -B -ntp "-Dmaven.repo.local=$repo_cache" "$@"
