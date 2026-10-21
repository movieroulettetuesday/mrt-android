#!/usr/bin/env bash
# One-time per clone: use the repo's tracked hooks (pre-push secret scan).
set -euo pipefail
cd "$(git rev-parse --show-toplevel)"
git config core.hooksPath .githooks
chmod +x .githooks/* scripts/*.sh
echo "Hooks installed (core.hooksPath=.githooks)."
