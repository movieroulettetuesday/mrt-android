#!/usr/bin/env bash
# Copy the canonical living docs from the box workspace into the repo (PARITY.md at root, others in docs/).
set -euo pipefail
cd "$(git rev-parse --show-toplevel)"
src="${MRT_DOCS_DIR:-/workspace/mrt-android}"
mkdir -p docs
cp "$src/PARITY.md" PARITY.md
cp "$src/backend-options.md" docs/backend-options.md
cp "$src/NOTES.md" docs/NOTES.md
cp "$src/KEYS_SETUP.md" docs/KEYS_SETUP.md
echo "Docs synced from $src"
