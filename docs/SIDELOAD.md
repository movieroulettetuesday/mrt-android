# Sideload APK builds (GitHub Releases)

MRT Android is distributed as a signed APK on GitHub Releases, with an install guide on the website.
This repo is **public**: no keystore, password, or `keystore.properties` is ever committed.
`.gitignore` and `scripts/scan-secrets.sh` (pre-push hook) both block them.

## One key for sideload and Play

Sideload APKs are signed with the **app signing key**: the same key that is later registered
under Play App Signing ("Use my own key", exported with PEPK). Because Android only lets an app
update in place when the new APK has the same package name **and** the same signing certificate,
people who sideloaded can later update to the Play version without uninstalling (which would erase
their ratings, watchlist and spin history). Play uploads use a separate **upload key**.

| Profile | Used for | Values |
|---|---|---|
| `UPLOAD` (default) | `bundleRelease` uploaded to Play | `MRT_UPLOAD_KEYSTORE`, `MRT_UPLOAD_KEYSTORE_PASSWORD`, `MRT_UPLOAD_KEY_ALIAS`, `MRT_UPLOAD_KEY_PASSWORD` |
| `APP` | sideload APKs | `MRT_APP_KEYSTORE`, `MRT_APP_KEYSTORE_PASSWORD`, `MRT_APP_KEY_ALIAS`, `MRT_APP_KEY_PASSWORD` |

Each value is read from the environment variable first, then the Gradle property
`mrt.<profile>.keystore` / `.keystorePassword` / `.keyAlias` / `.keyPassword` (for example in
`~/.gradle/gradle.properties` on the signing machine, outside the repo), then an untracked
`keystore.properties` at the repo root with the same keys.

Choose the profile with `-Pmrt.releaseSigning=app` (or `MRT_RELEASE_SIGNING=app`). When the
default profile has no values, the release build is unsigned, so CI and forks still build. When
`app` is requested and the values are missing, the build fails instead of producing an unsigned APK.

## Build

```bash
scripts/build-sideload-apk.sh --expect-cert <app-signing-cert-sha256>
```

It runs `:app:assembleRelease` with the `APP` profile, checks the signature with `apksigner`
(min SDK 26), refuses a certificate other than the expected one, and writes
`app/build/sideload/mrt-android-<versionName>-<versionCode>.apk` plus a `.sha256` file. It prints the
version, commit, size, APK SHA-256 and signing-certificate SHA-256 (all public values).

## Updating over a debug build

A debug build (`assembleDebug`) is signed with the machine's debug key and uses the same
package name. A release-signed APK cannot install over it; Android reports
`INSTALL_FAILED_UPDATE_INCOMPATIBLE`. Do not uninstall to get around this without the owner's OK:
uninstalling erases the app's data.
