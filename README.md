# Movie Roulette Tuesday for Android

Native Android port of the Movie Roulette Tuesday iOS app
([iOS source](https://github.com/movieroulettetuesday/movie-roulette-tuesday)), the companion app for
*Movie Roulette Tuesday: The Podcast*. Kotlin + Jetpack Compose (Material 3), single activity.

- Package / applicationId: `com.movieroulettetuesday.app` (same as the iOS bundle id)
- minSdk 26, targetSdk/compileSdk 36
- Current version: **1.1** (`versionCode` 3). `versionName` is semantic (`MAJOR.MINOR.PATCH`); `versionCode` is an integer that goes up by 1 for every Play upload and is never reused.
- Feature parity checklist: [PARITY.md](PARITY.md) · backend options: [docs/backend-options.md](docs/backend-options.md) · dev notes: [docs/NOTES.md](docs/NOTES.md)

## Features

- Search Movies and Episodes by title, year, episode number (S4E39, 4x39, "season 4 episode 39"), genre, or streaming service.
- On supported devices with Gemini Nano (on-device), a question becomes filters you can remove. A question about the other list switches lists. Regular search still works when Nano isn't available.
- On supported devices with Gemini Nano, Pick for Me on the Roulette tab chooses an unwatched movie, favoring your watchlist and your services, and gives a short reason from fixed lines plus facts the app already knows. The button is hidden when Nano is unavailable, and it shows that it isn't ready yet while the model downloads.
- The home-screen widget Spin of the Day.
- Four app shortcuts: Spin, Latest episode, Watchlist, and Pick for Me.
- House Rules explains the wheel, Pick for Me, Search, your lists, shortcuts, and the widget.
- Spin and Stop are easier to tap on a smaller wheel, and the result sheet scrolls only when the content doesn't fit.
- The bundled catalog has 184 movies.

## Build

Requirements: JDK 17 (Gradle and app), Android SDK with platform 36. Unit tests run Robolectric on a
JDK 21 toolchain that Gradle provisions automatically (foojay resolver).

```sh
scripts/install-hooks.sh                     # once per clone: pre-push secret scan
./gradlew testDebugUnitTest lintDebug assembleDebug
```

## Release signing

Signing values come only from environment variables, `~/.gradle/gradle.properties`, or an untracked
`keystore.properties`; nothing secret is ever committed. The default (Play upload key) uses
`MRT_UPLOAD_KEYSTORE` (path), `MRT_UPLOAD_KEYSTORE_PASSWORD`, `MRT_UPLOAD_KEY_ALIAS`, `MRT_UPLOAD_KEY_PASSWORD`.
When any is missing, `assembleRelease`/`bundleRelease` produce an unsigned artifact.
Sideload APKs use the app signing key (`MRT_APP_*`, `-Pmrt.releaseSigning=app`); see `docs/SIDELOAD.md`
and `scripts/build-sideload-apk.sh`.

## Secrets

This repository is **public**. `scripts/scan-secrets.sh` (run by the `.githooks/pre-push` hook)
blocks private keys, JWT-like tokens, GitHub/AWS/Google tokens, key=value secrets, and
keystore / `google-services.json` / `.env` / `local.properties` files.
