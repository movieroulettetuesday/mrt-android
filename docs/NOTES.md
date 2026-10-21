# MRT Android: environment, commands, decisions

_Box: Debian 13 (trixie) x86_64, 8 vCPU, 16 GB RAM. All Android tooling, builds, emulator, keystore and checkouts stay on this box. Nothing goes to Matthew's Mac or any other registered machine._

## Paths
| What | Where |
|---|---|
| Workspace | `/workspace/mrt-android/` (PARITY.md, backend-options.md, NOTES.md, the canonical copies) |
| iOS reference (read-only clone, `main` @ `b673df1`) | `/workspace/mrt-android/ios-ref`. Never commit to it, never push from it. |
| Android repo (local clone) | `/workspace/mrt-android/repo` → https://github.com/movieroulettetuesday/mrt-android (**public**) |
| JDK 17 (Temurin 17.0.20.1) | `/opt/jdk17` (`JAVA_HOME`) |
| JDK 21 for Robolectric tests | Gradle toolchain, auto-provisioned (foojay) into `~/.gradle/jdks` (Temurin 21.0.12.1) |
| Android SDK | `ANDROID_HOME=/opt/android-sdk` (owned by box) |
| Gradle (used only to create the wrapper) | `/opt/gradle-9.8.2`. Builds use `./gradlew` (wrapper 9.8.2). |
| AVDs | `~/.android/avd/mrt-api36`, `mrt-api37` (unusable, see KVM) |

SDK packages: cmdline-tools 23.0.0 (latest), platform-tools 37.0.1, platforms android-36 (compile/target) and android-37.0, build-tools 36.0.0 / 36.1.0 / 37.0.0, emulator 37.2.12, system images android-36 and android-37.0 google_apis x86_64.

## Toolchain versions (gradle/libs.versions.toml)
AGP 9.4.1 (built-in Kotlin), Kotlin 2.4.21, Compose BOM 2026.06.01 (Compose UI 1.11.4, Material3 1.4.0), activity-compose 1.13.0, navigation-compose 2.9.8, lifecycle 2.10.0, core-ktx 1.18.0, kotlinx-serialization 1.12.0, coroutines 1.11.0, JUnit 4.13.2, Robolectric 4.17 (SDK 36), androidx.test core 1.7.0 / ext-junit 1.3.0.
**Why some versions aren't the newest:** Compose 1.12+, navigation 2.10+, lifecycle 2.11+ and core 1.19+ all require compileSdk 37. Matthew set compileSdk/targetSdk to **36**, so they are pinned one step back (commented in the catalog). Bump all of them together when moving to compileSdk 37.

## KVM / emulator result
- `/dev/kvm` exists (`crw-rw---- root:103`); user `box` isn't in group 103. Without changing any system config, access works with `sudo setpriv --groups=1000,998,103 …`, and `emulator -accel-check` says "KVM (version 12) is installed and usable".
- **Actually running a VM fails:** the API 37 and API 36 AVDs both hung with 0 CPU time after "Starting QEMU main loop". A direct `KVM_CREATE_VCPU` ioctl test segfaulted, and the host kernel logged `kernel BUG at arch/x86/kvm/x86.c:702` / `kvm_spurious_fault` (four oopses in total). Nested virtualization on this box is broken. **Do not retry the emulator here.**
- Plan: Robolectric for unit and Compose UI tests (including a native-graphics screenshot test). Device testing needs a real phone, a working-KVM host, or Firebase Test Lab / Play pre-launch reports later.

## Commands
```sh
cd /workspace/mrt-android/repo
export JAVA_HOME=/opt/jdk17 ANDROID_HOME=/opt/android-sdk
./gradlew testDebugUnitTest lintDebug assembleDebug        # the gate for every merge
./gradlew assembleRelease / bundleRelease                  # unsigned unless the MRT_UPLOAD_* env vars are set
scripts/scan-secrets.sh                                    # working tree; the pre-push hook also scans the pushed range
scripts/test-scan-secrets.sh                               # scanner self-test (12 cases)
scripts/sync-bundled-catalog.sh                            # refresh assets/movies.json from the iOS repo
scripts/sync-docs.sh                                       # copy the canonical docs from /workspace/mrt-android into the repo
```
Reports: `app/build/reports/lint-results-debug.txt`, `app/build/reports/tests/testDebugUnitTest/`, screenshot `app/build/outputs/screenshots/roulette-wheel.png`.

## Workflow
Branch per change (`setup/…`, `feature/…`, `fix/…`) → gate (tests + lint 0 errors + assemble) → `git merge --ff-only` into main → push (the pre-push hook runs the secret scan) → delete the branch. Repo-local git identity: `movieroulettetuesday` / `342172652+movieroulettetuesday@users.noreply.github.com`. `core.hooksPath=.githooks` (set by `scripts/install-hooks.sh`).

## Decisions
- **Package / applicationId `com.movieroulettetuesday.app`.** It is the same as the iOS bundle id (`com.movieroulettetuesday.app`) and the CloudKit/App Group naming, and it is permanent on Play. The Kotlin namespace is the same.
- **Versioning:** `versionName` is semantic MAJOR.MINOR.PATCH (starts at 1.0.0). `versionCode` is an integer, +1 for every build uploaded to Play, never reused (starts at 1; Play max is 2,100,000,000). The catalog cache is keyed by versionCode, like iOS uses CFBundleVersion.
- minSdk 26, targetSdk/compileSdk 36 (Play requirement for new apps from Aug 31, 2026).
- The single activity is Compose; tabs match iOS (Roulette, Episodes, Movies, Stats). The tab icon avoids dice (Matthew asked iOS to drop dice imagery).
- The manifest removes `com.google.android.gms.permission.AD_ID` (`tools:node="remove"`; verified absent in merged debug and release manifests). `usesCleartextTraffic=false` plus a network security config with cleartext off. Backups are off (`allowBackup=false`).
- Release signing comes only from `MRT_UPLOAD_KEYSTORE`, `MRT_UPLOAD_KEYSTORE_PASSWORD`, `MRT_UPLOAD_KEY_ALIAS` and `MRT_UPLOAD_KEY_PASSWORD`. When any is missing, the release build is unsigned (logged). No upload key exists yet. When one is made, it is generated on the box, stored outside git, and its passwords are kept in Grok Bot secrets/env. Matthew decides the backup plan.
- The repo is public. Never commit keystores, passwords, `local.properties`, `google-services.json` or `.env`; both `.gitignore` and the scanner enforce this.
- Robolectric runs SDK 36 on JDK 21 (an Android 15+ runtime requirement) with `--add-exports java.base/jdk.internal.access`. The app itself compiles for Java 17.
- Under Robolectric the app is offline and bundled-only (`MrtApplication.isUnderTest`), like iOS's `bundledOnly`.
- Backend: see `backend-options.md` (recommendation: CloudKit bridge; ship 1.0 read-only/without voting until approved). No backend has been built.

## Shared contract and the spin sound (feature/shared-contract)
- `repo/shared/` is a pinned copy of `/workspace/mrt-shared/{data,test-vectors}` with `shared/SHA256SUMS`. Refresh with `scripts/sync-shared.sh` (reads `MRT_SHARED_DIR`, default `/workspace/mrt-shared`), then rerun the tests. `:app:verifySharedContract` fails the build when a pinned file doesn't match its hash, or when the external mrt-shared checkout exists and differs (so there is one source).
- `:app:generateSharedContract` turns `shared/data/*.json` into `SharedContract.kt` (`SharedPalette`, `SharedShow`, `SharedSpinTrack`), so constants are never hand-copied.
- `wheel-spin.m4a` (Matthew's recording; OK to reuse) is **not committed**. `:app:copySpinSound` copies it from `$MRT_IOS_CHECKOUT/MovieRouletteTuesday/Resources/wheel-spin.m4a` (default `../ios-ref`) into generated assets after checking sha256 `f9b6db796152c10493ee5c434fc4ef5f68ceaddc5f25a0b40e2491f623a666fd`. Release builds fail without it; debug builds warn and play nothing (haptics still work).
- Vector tests: `./gradlew :app:testDebugUnitTest --tests '*VectorsTest*'`. Seeded cases use `SwiftCompatRandom` (D1); production spins use `Random.Default`.
- D11 decision: a broken feed throws `malformed` (one of the vector's `acceptable` outcomes) so a bad download never replaces the cached episode list.
