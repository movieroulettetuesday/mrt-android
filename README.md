# Movie Roulette Tuesday for Android

Native Android port of the Movie Roulette Tuesday iOS app
([iOS source](https://github.com/movieroulettetuesday/movie-roulette-tuesday)), the companion app for
*Movie Roulette Tuesday: The Podcast*. Kotlin + Jetpack Compose (Material 3), single activity.

- Package / applicationId: `com.movieroulettetuesday.app` (same as the iOS bundle id)
- minSdk 26, targetSdk/compileSdk 36
- Versioning: `versionName` is semantic (`MAJOR.MINOR.PATCH`); `versionCode` is an integer that goes up by 1 for every Play upload and is never reused.
- Feature parity checklist: [PARITY.md](PARITY.md) · backend options: [docs/backend-options.md](docs/backend-options.md) · dev notes: [docs/NOTES.md](docs/NOTES.md)

## Build

Requirements: JDK 17 (Gradle and app), Android SDK with platform 36. Unit tests run Robolectric on a
JDK 21 toolchain that Gradle provisions automatically (foojay resolver).

```sh
scripts/install-hooks.sh                     # once per clone: pre-push secret scan
./gradlew testDebugUnitTest lintDebug assembleDebug
```

## Release signing

Signing reads only environment variables; nothing secret is ever committed:
`MRT_UPLOAD_KEYSTORE` (path), `MRT_UPLOAD_KEYSTORE_PASSWORD`, `MRT_UPLOAD_KEY_ALIAS`, `MRT_UPLOAD_KEY_PASSWORD`.
When any is missing, `assembleRelease`/`bundleRelease` produce an unsigned artifact.

## Secrets

This repository is **public**. `scripts/scan-secrets.sh` (run by the `.githooks/pre-push` hook)
blocks private keys, JWT-like tokens, GitHub/AWS/Google tokens, key=value secrets, and
keystore / `google-services.json` / `.env` / `local.properties` files.
