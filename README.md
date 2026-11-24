# Movie Roulette Tuesday for Android

Native Android port of the Movie Roulette Tuesday iOS app
([iOS source](https://github.com/movieroulettetuesday/movie-roulette-tuesday)), the companion app for
*Movie Roulette Tuesday: The Podcast*. Kotlin + Jetpack Compose (Material 3), single activity.

- Package / applicationId: `com.movieroulettetuesday.app` (same as the iOS bundle id)
- minSdk 26, targetSdk/compileSdk 36
- Current version: **1.1** (`versionCode` 3). `versionName` is semantic (`MAJOR.MINOR.PATCH`); `versionCode` is an integer that goes up by 1 for every release and is never reused.
- **Download:** [Movie Roulette Tuesday 1.1 for Android](https://github.com/movieroulettetuesday/mrt-android/releases/tag/v1.1-android) (`mrt-android-1.1-3.apk`, Android 8.0+) · [install guide](https://movieroulettetuesday.github.io/movie-roulette-tuesday/android.html). Distributed as a signed APK on GitHub Releases plus the install guide. Signing certificate SHA-256: `6b5d157e8b04b923e9b83840b742104675acbc691eeaaa8718020dbb2f4dac0c`.
- Feature parity checklist: [PARITY.md](PARITY.md) · backend options: [docs/backend-options.md](docs/backend-options.md) · dev notes: [docs/NOTES.md](docs/NOTES.md)

## Features

- Search Movies and Episodes by title, year, episode number (S4E39, 4x39, "season 4 episode 39"), genre, or streaming service.
- On supported devices with Gemini Nano (on-device), a question becomes filters you can remove. A question about the other list switches lists. Regular search still works when Nano isn't available.
- On supported devices with Gemini Nano, Pick for Me on the Roulette tab chooses an unwatched movie, favoring your watchlist and your services, and gives a short reason from fixed lines plus facts the app already knows. The button is hidden when Nano is unavailable, and it shows that it isn't ready yet while the model downloads.
- The home-screen widget Spin of the Day.
- Four app shortcuts: Spin, Latest episode, Watchlist, and Pick for Me.
- House Rules explains the wheel, Pick for Me, Search, your lists, shortcuts, and the widget.
- Spin and Stop are easier to tap on a smaller wheel, and the result sheet scrolls only when the content doesn't fit.
- The bundled catalog has 185 movies.

## Build

Requirements: JDK 17 (Gradle and app), Android SDK with platform 36. Unit tests run Robolectric on a
JDK 21 toolchain that Gradle provisions automatically (foojay resolver).

```sh
scripts/install-hooks.sh                     # once per clone: pre-push secret scan
./gradlew testDebugUnitTest lintDebug assembleDebug
```

## Release signing

Signing values come only from environment variables, `~/.gradle/gradle.properties`, or an untracked
`keystore.properties`; nothing secret is ever committed. Release APKs on GitHub Releases are signed
with the app signing key (`MRT_APP_*`, `-Pmrt.releaseSigning=app`) by `scripts/build-sideload-apk.sh`;
see `docs/SIDELOAD.md`. Without signing values, `assembleRelease` produces an unsigned APK, so CI and
forks still build.

## Secrets

This repository is **public**. `scripts/scan-secrets.sh` (run by the `.githooks/pre-push` hook)
blocks private keys, JWT-like tokens, GitHub/AWS/Google tokens, key=value secrets, and
keystore / `google-services.json` / `.env` / `local.properties` files.
