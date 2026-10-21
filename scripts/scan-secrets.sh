#!/usr/bin/env bash
# Secret scanner for mrt-android (public repo). Blocks:
#   private keys, JWT-like tokens, GitHub / AWS / Google API tokens, quoted or env-style
#   key=value secrets, and keystore / google-services / .env / key files.
#
# Usage:
#   scripts/scan-secrets.sh                 scan tracked + untracked (not ignored) files in the working tree
#   scripts/scan-secrets.sh --range A..B    scan every commit in A..B (file names + added lines)
#   scripts/scan-secrets.sh --commits SHA.. scan the listed commits
# Exit 0 = clean, 1 = findings (findings are printed with the matched value masked).
set -euo pipefail
cd "$(git rev-parse --show-toplevel)"

CONTENT_PATTERNS=(
  '-----BEGIN ([A-Z]+ )?PRIVATE KEY-----'
  '-----BEGIN PGP PRIVATE KEY BLOCK-----'
  'eyJ[A-Za-z0-9_-]{10,}\.eyJ[A-Za-z0-9_-]{10,}\.[A-Za-z0-9_-]{10,}'
  '(ghp|gho|ghu|ghs|ghr)_[A-Za-z0-9]{36,}'
  'github_pat_[A-Za-z0-9_]{22,}'
  '(AKIA|ASIA|AGPA|AIDA|AROA|ANPA)[0-9A-Z]{16}'
  '[Aa][Ww][Ss]_?[Ss][Ee][Cc][Rr][Ee][Tt]_?[Aa][Cc][Cc][Ee][Ss][Ss]_?[Kk][Ee][Yy]'
  'AIza[0-9A-Za-z_-]{35}'
  'xox[baprs]-[A-Za-z0-9-]{10,}'
  'sk_(live|test)_[A-Za-z0-9]{16,}'
  # quoted literal assigned to a secret-looking name:  password = "hunter2hunter2"
  '([Pp][Aa][Ss][Ss]([Ww][Oo][Rr][Dd]|[Ww][Dd])?|[Ss][Ee][Cc][Rr][Ee][Tt]|[Tt][Oo][Kk][Ee][Nn]|[Aa][Pp][Ii]_?[Kk][Ee][Yy]|[Aa][Cc][Cc][Ee][Ss][Ss]_?[Kk][Ee][Yy]|[Pp][Rr][Ii][Vv][Aa][Tt][Ee]_?[Kk][Ee][Yy])["'"'"']?[[:space:]]*[:=][[:space:]]*["'"'"'][^"'"'"'[:space:]$]{8,}["'"'"']'
  # env style:  MRT_UPLOAD_KEYSTORE_PASSWORD=abcdefgh
  '^[[:space:]]*(export[[:space:]]+)?[A-Z0-9_]*(PASSWORD|PASSWD|SECRET|TOKEN|API_KEY|APIKEY|PRIVATE_KEY)[A-Z0-9_]*[[:space:]]*=[[:space:]]*[^[:space:]$"'"'"'{(]{8,}'
  # lowercase properties style:  key.password=abcdefgh
  '^[[:space:]]*[a-z0-9_.]*(password|passwd|secret|token|api_key|apikey)[[:space:]]*[=:][[:space:]]*[^[:space:]$"'"'"'{(]{8,}'
)
# Blocked file names (basename or path regex).
FILE_PATTERNS='(\.jks|\.keystore|\.p12|\.p8|\.pem|\.key|\.mobileprovision)$|(^|/)google-services\.json$|(^|/)GoogleService-Info\.plist$|(^|/)\.env(\..*)?$|(^|/)local\.properties$|(^|/)(keystore|signing)\.properties$'
ALLOW_FILES='(^|/)\.env\.example$|^scripts/scan-secrets\.sh$'

findings=0
report() { echo "  [secret-scan] $1"; findings=$((findings + 1)); }
mask() { sed -E 's/([A-Za-z0-9_+\/=-]{4})[A-Za-z0-9_+\/=-]{4,}/\1****/g' | cut -c1-160; }

check_name() {
  local f="$1"
  [[ "$f" =~ $ALLOW_FILES ]] && return 0
  if [[ "$f" =~ $FILE_PATTERNS ]]; then report "blocked file: $f"; fi
}

# $1 = label, stdin = text
check_text() {
  local label="$1" text hit p
  text="$(cat)"
  [ -z "$text" ] && return 0
  for p in "${CONTENT_PATTERNS[@]}"; do
    hit="$(printf '%s\n' "$text" | grep -E -n -e "$p" 2>/dev/null | head -3 || true)"
    if [ -n "$hit" ]; then
      while IFS= read -r line; do report "$label: $(printf '%s' "$line" | mask)"; done <<< "$hit"
    fi
  done
  # Literal value of a known secret from the environment, if set (value never printed).
  for v in TMDB_API_TOKEN MRT_UPLOAD_KEYSTORE_PASSWORD MRT_UPLOAD_KEY_PASSWORD; do
    local val="${!v:-}"
    if [ -n "$val" ] && [ "${#val}" -ge 8 ] && printf '%s' "$text" | grep -F -q -e "$val"; then
      report "$label: contains the value of \$$v"
    fi
  done
}

scan_commit() {
  local c="$1" f
  while IFS= read -r f; do [ -n "$f" ] && check_name "$f"; done < <(git diff-tree --no-commit-id --name-only --diff-filter=ACMR -r --root "$c")
  while IFS= read -r f; do
    [ -z "$f" ] && continue
    [[ "$f" =~ $ALLOW_FILES ]] && continue
    { git show --format= --unified=0 --no-color "$c" -- "$f" | grep -E '^\+' | grep -v -E '^\+\+\+ ' || true; } | sed 's/^+//' | check_text "${c:0:9}:$f"
  done < <(git diff-tree --no-commit-id --name-only --diff-filter=ACMR -r --root "$c")
}

case "${1:-}" in
  --range)
    for c in $(git rev-list "$2"); do scan_commit "$c"; done ;;
  --commits)
    shift; for c in "$@"; do scan_commit "$c"; done ;;
  "")
    while IFS= read -r -d '' f; do
      check_name "$f"
      [[ "$f" =~ $ALLOW_FILES ]] && continue
      [ -f "$f" ] || continue
      if grep -I -q . "$f" 2>/dev/null; then check_text "$f" < "$f"; fi
    done < <(git ls-files -z --cached --others --exclude-standard) ;;
  *) echo "usage: $0 [--range A..B | --commits SHA...]" >&2; exit 2 ;;
esac

if [ "$findings" -gt 0 ]; then
  echo "secret-scan: $findings finding(s). Remove them (and rewrite the commit) before pushing." >&2
  exit 1
fi
echo "secret-scan: clean"
