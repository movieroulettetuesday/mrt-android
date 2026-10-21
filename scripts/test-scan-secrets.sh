#!/usr/bin/env bash
# (Fixtures are split with "" so this file itself never matches.)
# Self-test for scan-secrets.sh: each planted secret must be caught on its own (working tree and --range).
set -uo pipefail
here="$(cd "$(dirname "$0")" && pwd)"
fail=0
check() { # $1 = name, $2 = file name, $3 = content, $4 = expected exit
  local t; t="$(mktemp -d)"
  mkdir -p "$t/scripts"; cp "$here/scan-secrets.sh" "$t/scripts/"
  ( cd "$t" && git init -q && printf '%s\n' "$3" > "$2" && git add -f -A \
    && git -c user.email=t@t -c user.name=t commit -qm t )
  ( cd "$t" && scripts/scan-secrets.sh >/dev/null 2>&1 ); local a=$?
  ( cd "$t" && scripts/scan-secrets.sh --range HEAD >/dev/null 2>&1 ); local b=$?
  rm -rf "$t"
  if [ "$a" != "$4" ] || [ "$b" != "$4" ]; then echo "FAIL $1 (tree=$a range=$b, want $4)"; fail=1; else echo "ok   $1"; fi
}
gh="ghp_$(printf 'a%.0s' $(seq 36))"
check private-key k.txt "-----BEGIN OPENSSH ""PRIVATE KEY-----" 1
check jwt j.txt "eyJhbGciOiJIUzI1NiJ9"".eyJzdWIiOiIxMjM0NTY3ODkwIn0.dozjgNryP4J3jVmNHl0w5N" 1
check github g.txt "$gh" 1
check aws a.txt "AKIA""ABCDEFGHIJKLMNOP" 1
check quoted q.kt 'storePassword = "hunter2''hunter2"' 1
check env e.sh "MRT_UPLOAD_KEY_PASSWORD""=abcdefgh123" 1
check keystore upload.jks "x" 1
check google-services google-services.json "{}" 1
check dotenv .env "A=b" 1
check local-properties local.properties "sdk.dir=/x" 1
check getenv-ok ok.kt 'val p = System.getenv("MRT_UPLOAD_KEY_PASSWORD")' 0
check identifier-ok ok2.kt "storePassword = uploadKeystorePassword" 0
exit $fail
