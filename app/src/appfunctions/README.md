# Android App Functions (optional, default OFF)

This source set is **not compiled** by the default build. It is a thin wrapper around
`ActionCoordinator` (in the main source, always compiled and tested) that would expose the same
1.1 actions as Android 16 App Functions.

## Why a flag, and why the default is OFF

Google’s App Functions Jetpack library (`androidx.appfunctions:appfunctions`, 1.0.0-rc01 as of
2026-11-04) is still an experimental / preview-to-RC surface. The official integration guide
(https://developer.android.com/ai/appfunctions/add-appfunctions, updated 2026-11-10) requires:

- `compileSdk` **37** or higher
- Android Gradle Plugin 9.1.0 or higher (this project already has 9.4.1)
- Kotlin Symbol Processing for `appfunctions-compiler`

This project’s `compileSdk` and `targetSdk` are **36**, a deliberate Matthew decision (see
`docs/NOTES.md`). Bumping to 37 would also force Compose 1.12+, navigation 2.10+, lifecycle 2.11+,
and core 1.19+. The default gate (`testDebugUnitTest`, `lintDebug`, `lintRelease`) must never
depend on those preview artifacts.

Gemini still cannot generally invoke third-party App Functions. Shipping the wrappers does not
make “Hey Google, spin the wheel in Movie Roulette” work. The EAP application is drafted at
`/workspace/mrt-android/plans/app-functions-eap-application.md` and has **not** been submitted.

## Enabling a flag-on build (for investigation only)

```sh
# This is expected to fail on compileSdk 36, with a clear error:
./gradlew :app:assembleDebug -Pmrt.appFunctions=true
```

The Gradle property `mrt.appFunctions=true` (or env `MRT_APP_FUNCTIONS=true`) is the flag.
`BuildConfig.APP_FUNCTIONS_ENABLED` is `false` unless that property is set **and** the project
has been moved to compileSdk 37. The flag-off APK has no `androidx.appfunctions` dependency.

## Functions (same verbs as the deep links)

| Function        | Deep link                              | Behavior                                      |
|-----------------|----------------------------------------|-----------------------------------------------|
| `spin`          | `mrt://spin`                           | Open the Roulette tab and play the wheel      |
| `openMovie`     | `mrt://movie/{id}`                     | Open a catalog movie                          |
| `latestEpisode` | `mrt://latest`                         | Open the in-app page for the latest episode   |
| `showWatchlist` | `mrt://watchlist`                      | Movies tab, Watchlist filter                  |
| `addToWatchlist`| `mrt://watchlist/add/{id}`             | `WantToWatch`; does not invent a rating       |
| `markWatched`   | `mrt://watched/{id}`                   | `Watched`; does not invent a rating           |
| `whereToWatch`  | `mrt://wtw/{id}`                       | Speak providers; open the movie’s WTW section |
| `openOnProvider`| `mrt://movie/{id}/provider/{name}`     | `ProviderLaunch.open`                         |
| `whatShouldIWatch` | `mrt://suggest`                     | On-device suggestion, or offer Spin           |

Test with `adb shell cmd app_function` once a flag-on build is installed on Android 16+.

The Kotlin in `java/` is the intended `@AppFunctionServiceEntryPoint` shape. It is documented,
not compiled, until the flag can be turned on without breaking compileSdk 36.
