#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
database="${1:-resolveops_demo}"
if [[ ! "$database" =~ ^resolveops_demo(_[a-z0-9]+)?$ ]]; then
  echo 'Choose a fresh database named resolveops_demo or resolveops_demo_<suffix>.' >&2
  exit 2
fi

pg_options=(-h 127.0.0.1 -p "${PGPORT:-55434}" -U "${PGUSER:-postgres}")
export DEMO_OPERATOR_PASSWORD="$(openssl rand -hex 12)"
export DEMO_APPROVER_PASSWORD="$(openssl rand -hex 12)"
export DEMO_SOP='Subscription activation reconciliation: if payment is confirmed and activation failed for a paid pending subscription, recommend RECONCILE_ACTIVATION for operator review.'
DEMO_EMBEDDING="$(python3 - <<'PY'
import json
import os
from urllib.request import Request, urlopen

body = json.dumps({"model": "nomic-embed-text:v1.5", "input": os.environ["DEMO_SOP"]})
request = Request("http://127.0.0.1:11434/v1/embeddings", body.encode(), {"Content-Type": "application/json"})
with urlopen(request, timeout=120) as response:
    embedding = json.load(response)["data"][0]["embedding"]
if len(embedding) != 768:
    raise ValueError("Expected a 768-dimensional Nomic embedding")
print(json.dumps(embedding))
PY
)"
export DEMO_EMBEDDING

# createdb fails if the target exists, before any schema or fixture writes.
createdb "${pg_options[@]}" "$database"
{
  sed 's/vector(1536)/vector(768)/' "$repo_root/resources/database/schema_pg.sql"
  cat "$repo_root/resources/database/resolveops_demo_pg.sql"
} | psql "${pg_options[@]}" -d "$database" -X -1 -q -v ON_ERROR_STOP=1 -f -

printf 'Database: %s\nOperator: demo-operator\nPassword: %s\nApprover: demo-approver\nPassword: %s\n' \
  "$database" "$DEMO_OPERATOR_PASSWORD" "$DEMO_APPROVER_PASSWORD"
