#!/usr/bin/env bash
# Builds the signed sideload APK (release build type, APP signing profile = the Play App Signing key)
# and prints its file name, size, SHA-256 and signing-certificate SHA-256. Nothing secret is printed.
#
# Usage:
#   scripts/build-sideload-apk.sh [--expect-cert <sha256-of-signing-cert>]
#
# Needs JAVA_HOME, ANDROID_HOME and the APP signing values (env MRT_APP_* or mrt.app.* in
# ~/.gradle/gradle.properties). See docs/SIDELOAD.md. Output: app/build/sideload/
set -euo pipefail
cd "$(git rev-parse --show-toplevel)"

expect_cert=""
if [ "${1:-}" = "--expect-cert" ]; then
  expect_cert="$(echo "${2:?--expect-cert needs a value}" | tr -d ': ' | tr 'A-F' 'a-f')"
fi

if [ -n "$(git status --porcelain --untracked-files=no)" ]; then
  echo "build-sideload-apk: working tree has uncommitted changes; build from a clean commit." >&2
  exit 1
fi

: "${ANDROID_HOME:?ANDROID_HOME must be set}"
build_tools="$(ls -d "$ANDROID_HOME"/build-tools/* | sort -V | tail -1)"
apksigner="$build_tools/apksigner"
aapt2="$build_tools/aapt2"

./gradlew --quiet -Pmrt.releaseSigning=app :app:assembleRelease

apk="app/build/outputs/apk/release/app-release.apk"
if [ ! -f "$apk" ]; then
  echo "build-sideload-apk: $apk not found (unsigned builds are named app-release-unsigned.apk)." >&2
  exit 1
fi

"$apksigner" verify --min-sdk-version 26 "$apk" >/dev/null
cert="$("$apksigner" verify --print-certs "$apk" | sed -n 's/^Signer #1 certificate SHA-256 digest: //p' | head -1)"
if [ -z "$cert" ]; then
  echo "build-sideload-apk: could not read the signing certificate." >&2
  exit 1
fi
if [ -n "$expect_cert" ] && [ "$cert" != "$expect_cert" ]; then
  echo "build-sideload-apk: signed with an unexpected certificate (${cert:0:12}..., expected ${expect_cert:0:12}...)." >&2
  exit 1
fi

badging="$("$aapt2" dump badging "$apk" | head -1)"
version_name="$(echo "$badging" | sed -n "s/.*versionName='\([^']*\)'.*/\1/p")"
version_code="$(echo "$badging" | sed -n "s/.*versionCode='\([^']*\)'.*/\1/p")"
commit="$(git rev-parse --short=8 HEAD)"

out_dir="app/build/sideload"
mkdir -p "$out_dir"
name="mrt-android-${version_name}-${version_code}.apk"
cp "$apk" "$out_dir/$name"
( cd "$out_dir" && sha256sum "$name" > "$name.sha256" )
sha="$(cut -d' ' -f1 "$out_dir/$name.sha256")"

echo "APK:          $out_dir/$name"
echo "Version:      $version_name ($version_code), commit $commit"
echo "Size:         $(stat -c %s "$out_dir/$name") bytes"
echo "SHA-256:      $sha"
echo "Signing cert: $cert"
