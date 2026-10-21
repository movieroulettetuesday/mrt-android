#!/usr/bin/env bash
# Refresh the pinned copy of the cross-platform contract (shared/) from mrt-shared and rewrite
# shared/SHA256SUMS. The build verifies those hashes (verifySharedContract) and, when the mrt-shared
# folder is present, fails if the pinned copy is stale. Usage: scripts/sync-shared.sh [mrt-shared dir]
set -euo pipefail
cd "$(git rev-parse --show-toplevel)"
src="${1:-${MRT_SHARED_DIR:-/workspace/mrt-shared}}"
[ -d "$src/data" ] && [ -d "$src/test-vectors" ] || { echo "no mrt-shared at $src" >&2; exit 1; }
rm -rf shared/data shared/test-vectors
mkdir -p shared
cp -r "$src/data" "$src/test-vectors" shared/
(cd shared && find data test-vectors -type f -name '*.json' | LC_ALL=C sort | xargs sha256sum > SHA256SUMS)
commit=$(python3 -c "import json;print(json.load(open('shared/test-vectors/catalog.json'))['_generated'].get('iosCommit',''))")
echo "synced from $src (vectors generated from iOS $commit)"
