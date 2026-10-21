# MRT Android ↔ iOS parity checklist (living)

Canonical copy: `/workspace/mrt-android/PARITY.md` (box). Mirrored into the repo root by `scripts/sync-docs.sh`.
iOS source of truth: `movieroulettetuesday/movie-roulette-tuesday@main` (read-only clone at `/workspace/mrt-android/ios-ref`, reviewed at `b673df1`, 2026-10-20).
Android repo: https://github.com/movieroulettetuesday/mrt-android (public).

Status: ✅ done · 🟡 partial · ⬜ not started · ⏸ blocked on a decision.
Effort (Android work, rough): **S** ≤ ½ day · **M** 1–2 days · **L** 3–5 days · **XL** > 1 week.

_Last updated 2026-10-21 (ET) after merging the shared contract (`feature/shared-contract`: mrt-shared vectors, D1–D5, D7–D9, D11)._

## 0. App shell
| iOS (SwiftUI) | Android (Compose) | Effort | Status |
|---|---|---|---|
| `MovieRouletteTuesdayApp.swift`, `ContentView.swift` (TabView: Roulette, Episodes, Movies, Stats) | `MainActivity.kt`, `ui/MrtApp.kt` (single activity, `NavigationBar` + `NavHost`, same tab order) | S | ✅ |
| Episodes tab badge (new since last viewed, `NewEpisodeBadge.swift`) | `NavigationBar` `BadgedBox` + `EpisodeRepository.markViewed` (SharedPreferences `episodes.lastViewedAt`, first-load baseline) | S | ✅ tested |
| `Shared/Brand.swift`, `CleanPro.swift`, `Color+Hex.swift`, asset colors (BrandTeal/Peach, WheelRim, LaunchBackground) | `ui/theme/Theme.kt` (`BrandColors`, M3 light/dark; Ink #1D2B2E, launcher bg #F1F6F6 per D8), `catalog/HexColor.kt`, `catalog/CategoryPalette.kt` (muted display palette + radial slice stops from mrt-shared `data/palette.json`, D2) | S | 🟡 colors ✅ (shared vectors); CleanPro card styling not yet |
| `AppearancePreference.swift`, `Appearance.swift` (System/Light/Dark) | `settings/AppPreferences.kt` (`settings.appearance` = system/light/dark, same raw values) → `MrtTheme(darkTheme = forcesDark ?: system)` in `MainActivity` | S | ✅ tests |
| `LaunchSplash.swift`, `LaunchSplashView.swift` (cold-launch animation, skippable, Reduce Motion crossfade) | `androidx.core:core-splashscreen` + Compose intro | M | ⬜ |
| App icon (`AppIcon.png`) | Adaptive icon built from the same artwork (`mipmap-*/ic_launcher_foreground.png`, monochrome layer), bg `#F5F5F5`; identical to Play 512 candidate | S | ✅ |
| `AdaptiveLayout.swift` (iPad/landscape) | Window size classes; wheel already capped at 520 dp | M | 🟡 phone-only layouts so far |
| `AppRouter.swift`, `DeepLink` (`mrt://movie/<id>`, `mrt://episode/<guid>`) | Intent filter `mrt://`, Navigation deep links | S | ⬜ |
| `DebugLaunch.swift` (`-MRT…` args, bundled-only under tests) | `MrtApplication.isUnderTest` keeps Robolectric offline/bundled | S | ✅ |
| `PrivacyInfo.xcprivacy` | Play Data safety (see `/workspace/mrt-release/play/android-answers.md`); no AD_ID, HTTPS-only network config | S | ✅ build side |

## 1. Roulette wheel
| iOS | Android | Effort | Status |
|---|---|---|---|
| `RouletteCore/WheelMath.swift` (pointer angle, uniform + weighted geometry, edge-padded random target) | `roulette/WheelMath.kt` (`WheelMath`, `WheelGeometry`) | S | ✅ 8 tests |
| `RouletteCore/MovieSelector.swift` (category → movie, recency window 5, ×0.1 last spin, infamous ×1.25, `pickIndex`) | `roulette/MovieSelector.kt` | S | ✅ 6 tests |
| `RouletteCore/RareOutcomes.swift` (1 % forced re-spin, 1 % Infamous jackpot, no chaining) | `roulette/RareOutcomes.kt` | S | ✅ 6 tests |
| `RouletteCore/RandomGenerators.swift` (SplitMix64) | `roulette/RandomGenerators.kt` `SwiftCompatRandom` (SplitMix64 + Swift double/Int mapping, Lemire; D1). Production uses `Random.Default` | S | ✅ shared rng vectors |
| `Roulette/RouletteViewModel.swift` (load, spin, watchdog, forced re-spin 1.6 s, deferred catalog swap, reveal, spin again) | `roulette/RouletteViewModel.kt` | M | ✅ 8 tests |
| `Roulette/WheelView.swift`, `HubSpinButton.swift`, `RouletteView.swift` (slices, labels, pointer, glowing hub, tap wheel or hub to spin, Magic Tap) | `roulette/WheelView.kt`, `RouletteScreen.kt` (Canvas slices from the same geometry, readable labels, pointer, hub; tap wheel or hub) | M | 🟡 hub glow/visual polish, TalkBack custom action pending |
| Animated spin timed to recording (`SpinMotion.swift`, `SpinSoundTrack.swift`, 6.294 s, wind-up + brake) | `roulette/SpinMotion.kt` full port: per-click time warp (`plan`, smoothest-choice DP, snap ≤ MAX_SNAP), `TimingCurve`, `SpinTicks`; onsets from `data/spin-sound-track.json` (D4) | S | ✅ shared wheel vectors |
| Spin sound `wheel-spin.m4a` once per spin; haptic per slice crossing (`SpinFeedback.swift`, `SpinTicks.swift`) | `roulette/SpinFeedback.kt` `SpinSoundPlayer` (MediaPlayer, fade-out on stop) + CLOCK_TICK haptics at the planned crossings (thinned to ≥45 ms). Sound file copied at build time from the iOS checkout, sha256-verified, not committed; Settings “Spin sound” (`settings.tickSound`) | M | ✅ |
| Hub ■ stops a spin early (`SpinStopPlan`) | `SpinStopPlan` (0.8 s ease-out, ≥120° travel, same-RNG target); tap wheel or hub (“STOP”) while spinning | M | ✅ shared stopPlan vector |
| Reduce Motion: same timeline, 1–2 turns | Remove-animations setting → 1–2 turns (`RouletteRoute.kt`) | S | ✅ |
| `RevealSheet.swift` (category, then movie, year, "Discussed on…", spin count, Spin Again, jackpot styling) | `RevealSheet` in `RouletteScreen.kt` (ModalBottomSheet) | S | ✅ (jackpot styling basic) |
| `ShareCard.swift` (1080×1350 branded image) + `ShareLink` text "Tonight’s Movie Roulette Tuesday spin: Title (Year)" | `roulette/Share.kt`: `ACTION_SEND` text/plain chooser, same text and subject | S / M | ✅ text · ⬜ image card (render composable → PNG → FileProvider) |
| `ConfettiView.swift` | Compose particle effect | S | ⬜ |
| `RouletteTaglines.swift`, header "Dinner, discussion, and the wheel" | Header in `RouletteScreen.kt` | S | 🟡 tagline only |
| `RulesSheet.swift` | Bottom sheet | S | ⬜ |
| `SpinHistoryView.swift`, `SpinHistoryStore.swift`, `SpinHistoryLog.swift`/`FileStore` (500 detailed + archive) | `roulette/SpinHistory.kt` (`SpinHistoryStore`, iOS v2 file format via `SpinHistoryCodec`, 500 + archive; unreadable file moved aside by `data/CorruptFileQuarantine.kt`, D3) | S / M | 🟡 store ✅, history screen ⬜ |
| `MoviePageView.swift` (from reveal / movies) | `movies/MovieDetailScreen.kt` (header, spin count, Episodes → episode page, Where to Watch, Your Rating + status, Categories, Listen: Apple/Spotify search, Buzzsprout); reachable from Movies, Episodes and the reveal sheet ("Movie page") | M | ✅ |
| `Intents/SpinMovieRouletteIntent.swift` (Siri/Shortcuts) | App Shortcut (`shortcuts.xml`) "Spin" → deep link | S | ⬜ |
| `QuickSpin.swift` | Launcher shortcut | S | ⬜ |

## 2. Movies catalog
| iOS | Android | Effort | Status |
|---|---|---|---|
| `RouletteCore/Models.swift` (`MovieCatalog`, `Movie`, `RouletteCategory`, `ShowInfo`, `StreamingInfo`, tolerant new fields, `validationIssues`, `deduplicated`, `subtitle`) | `catalog/Models.kt`, `catalog/CatalogJson.kt` (same required/optional/tolerant rules) | S | ✅ 10 + 5 tests |
| `RemoteCatalog.swift` (raw GitHub URL, 8 s, validation, build-keyed cache, refresher), `CatalogSource.swift`, `Shared/AppCatalog.swift` | `catalog/CatalogLoading.kt`, `catalog/CatalogRepository.kt` (cache in `filesDir`, keyed by versionCode; 15 s refresh throttle) | M | ✅ 9 tests |
| Bundled `Resources/movies.json` fallback | `app/src/main/assets/movies.json` (+ `scripts/sync-bundled-catalog.sh`) | S | ✅ (snapshot of `b673df1`, 180 movies, 10 categories) |
| Pull-to-refresh catalog | `PullToRefreshBox` in `MoviesScreen` → `CatalogRepository.refresh()` | S | ✅ |
| `Movies/MoviesView.swift` (search, sort, filters: category / watchlist / watched / rated by me / haven't spun yet; season/year grouping) | `movies/MoviesScreen.kt` (search title/genre/episode, Category / Show / Sort menus, On my services + Available first chips, Suggest row, footer "N of M movies", empty states), pure `MovieQuery` | L | ✅ tests (phone layout; no split view yet) |
| Where to Watch (`WhereToWatchDisplay`, `StreamingCatalog`, logos from image.tmdb.org, JustWatch/TMDB attribution, always visible with empty message) | `WhereToWatchSection` in `movies/MovieDetailScreen.kt` + `movies/Streaming.kt` (`WhereToWatchDisplay`, `StreamingCatalog`, "Your service" badge, TMDB link, attribution) | M | ✅ 4 tests |
| My Services (`MyServices`, "on my services", "available first") | `MyServices` (same "8,9,337" format) in SharedPreferences `streaming.myServices` → `MrtApplication.myServices`; picker `settings/SettingsScreen.kt` `MyServicesScreen` (checkmarks, attribution footer, empty text) | S | ✅ tests |
| `PersonalRatings.swift`, `PersonalRatingsStore.swift`, `StarRatingView.swift` (½–5 stars, Want to Watch / Watched) | `movies/PersonalRatings.kt` (`HalfStars`, entries, legacy whole-star read, rating ⇒ watched, corrupt-file quarantine, `personal-ratings.json` on device only), `ui/StarRating.kt` (tap/drag half stars, TalkBack adjustable) | M | ✅ 5 tests + Compose |
| `Artwork.swift`, `ArtworkLoader.swift`, `ArtworkImage.swift` | `ui/Artwork.kt`: dependency-free loader (memory LRU + disk cache, HTTPS-only, candidates best-first: episode art → poster → show art); `ArtworkResolver` port | M | ✅ (http artwork upgraded to https) |
| Host Rating (feature-flagged off on iOS) | Keep hidden | – | ⏸ off on iOS |

## 3. Episodes
| iOS | Android | Effort | Status |
|---|---|---|---|
| `RSSFeedParser.swift` (Buzzsprout `https://rss.buzzsprout.com/2117292.rss`, itunes:season/episode/image, fan-mail link strip) | `episodes/RssFeedParser.kt` (`XmlPullParser`; same fields, fullest notes, fan-mail strip, guid→link fallback, dedupe, RFC 822 variants); iOS fixture + trimmed live-feed fixture | M | ✅ 5 tests; the live feed parses all 184 episodes, 180 linked to the catalog |
| `EpisodeStoreCore.swift`, `Episodes/EpisodeStore.swift` (cache, refresh) | `episodes/EpisodeRepository.kt` (cache-first, 20 s timeout, 5-min freshness, shared in-flight load, soft offline error, `episodes-cache.json`), `EpisodeGrouper`/`EpisodeGroupExpansion` (same serialized format) | M | ✅ 5 + 6 tests |
| `EpisodesView.swift` (season sections), `EpisodeDetailView.swift` (About, Movie Discussed) | `episodes/EpisodesScreen.kt` (Season/Year/All segmented, collapsible remembered headers, pull-to-refresh, Offline label, footer count + updated), `EpisodeDetailScreen.kt` (artwork, About, fan mail, Movie Discussed, Listen) | M | ✅ 2 Compose tests (phone layout; no split view yet) |
| `ListenCard.swift`, `ShowLinks.swift`, `AppleEpisodeLinks.swift` (Apple exact episode via iTunes lookup, Spotify search, Overcast, Buzzsprout) | `episodes/PodcastLinks.kt` (`PodcastLinks`, `AppleEpisodeLookup` hourly-throttled cache, Spotify search cleaner, `ListenLinks`) → `ACTION_VIEW` | M | ✅ 5 tests (iOS title cases ported) |
| `EpisodeLabel.swift` ("Discussed on …") | `roulette/Share.kt` `EpisodeLabel` (full port: feed titles + "(S4E34)") | S | ✅ |

## 4. Stats
| iOS | Android | Effort | Status |
|---|---|---|---|
| `SpinStats.swift`, `Stats/StatsView.swift` (spin overview, top movie/category, Your Ratings count/average/top) | `stats/StatsCalculator.kt` (`StatsSnapshot`: totals incl. archive, most spun movie/category, by category, by genre, 10 recent; ties broken by id) + `stats/StatsScreen.kt` (No Stats Yet, This Device, Most Spun, Your Ratings, By Category bars, By Genre top 12, Recent Spins) | M | ✅ 3 + 2 Compose tests |

## 5. Settings (iOS: gear sheet on Roulette)
| iOS | Android | Effort | Status |
|---|---|---|---|
| `SettingsSheet.swift`, `AppSettings.swift`: Appearance; Spin sound; Haptics; New-episode alert; My services; Auto-share my ratings; Follow the Show (8 links); Suggest a movie; Credits & About (version, TMDB credit/notice); Privacy note | `settings/SettingsScreen.kt` (gear on Roulette → `roulette/settings`): Appearance, Haptics, New-episode alert toggle (scheduling lands with notifications), My services, Follow the Show (8 links, iOS order/colors), Suggest, Credits & About (TMDB mark/notice, version), Privacy note (`PrivacyCopy`); Auto-share shown only when community is configured. Prefs in SharedPreferences with iOS keys. Spin sound row hidden (see Roulette) | M | ✅ 4 + 3 Compose tests |

## 6. New-episode notifications
| iOS | Android | Effort | Status |
|---|---|---|---|
| `NewEpisodeAlerts.swift` (opt-in, BGAppRefresh ~6 h, watermark of seen guids (50), local notification "New episode of Movie Roulette Tuesday", tap → episode) | `notifications/NewEpisodeAlerts.kt` (`NewEpisodeDiff`, `NewEpisodeWatermarkStore` same keys `settings.newEpisodeAlerts` / `newEpisodeAlerts.watermark`, merge + 50-guid cap, `NewEpisodeChecker`, toggle logic) + `NewEpisodeWork.kt` (WorkManager 2.12 unique periodic 6 h, network required, KEEP; channel `new-episodes`; tag `episode-<guid>`; extra `episodeGUID` → `MainActivity` → episode page). POST_NOTIFICATIONS asked only when the toggle is turned on (API 33+); denied shows the iOS note. Episodes shown in the app are marked seen. No FCM | M | ✅ 5 + 4 tests + 1 Compose |

## 7. Home-screen widget
| iOS | Android | Effort | Status |
|---|---|---|---|
| `MovieRouletteTuesdayWidget/SpinOfTheDayWidget.swift`, `SpinOfTheDay.swift` (FNV-1a deterministic daily pick), `WidgetSnapshot.swift`, `WidgetBridge.swift` (latest episode, last spin; small/medium/large/accessory) | `widget/` Glance widget “Spin of the Day” (small / medium / large by size, same text and fallbacks as iOS, Clean Pro gradient light/dark from `palette.json`, category capsule in the display palette); `SpinOfTheDay` (FNV-1a, shared vectors), `WidgetSnapshot` + store (`filesDir/widget-snapshot.json`, no-op writes skipped), `WidgetBridge` (latest episode, last spin, catalog refresh), midnight WorkManager refresh; tap → `ACTION_OPEN_MOVIE` → movie page. Lock-screen accessory family: no Android equivalent | M | ✅ |

## 8. Suggest a Movie
| iOS | Android | Effort | Status |
|---|---|---|---|
| `SuggestMovieSheet.swift`, `MovieSuggestion` (mailto `theMrtPodcast@gmail.com`, subject "Movie suggestion: …", body template) | `suggest/SuggestMovieSheet.kt` + `MovieSuggestion.kt`: same fields/limits/prompts; `ACTION_SENDTO mailto:` with same subject/body plus EXTRA_SUBJECT/TEXT; credit name added as "From: …" like iOS `asEmail`; copies the address + "Email address copied" if no mail app | S | ✅ 5 + 3 Compose tests |
| `InAppSuggestion` → CloudKit `Suggestion` record (title ≤120, note ≤280, credit ≤60, 1 per 10 min) | Needs backend | M | ⏸ backend decision |

## 9. Polls (CloudKit) and 10. Shared ratings (CloudKit)
| iOS | Android | Effort | Status |
|---|---|---|---|
| `Community.swift` (`PollFactory` default poll per episode via FNV-1a: good/bad, recommend, who won the argument; `polls.json` overrides; `PollResults`, `RatingAggregate`, `CommunityBackoff`, `TimedCache`) | Pure Kotlin port (no backend needed) | M | ⬜ |
| `CloudKitCommunityStore.swift`, `CommunityService.swift`, `EpisodePollSection.swift`, `ListenerRatingSection.swift` (`PollVote`, `ListenerRating` stars + halfStars; record names hash iCloud user id) | `CommunityStore` interface + backend adapter | L | ⏸ see `backend-options.md` |
| `-MRTMockCommunity` in-memory store | Fake store for tests/previews | S | ⬜ |

## Shared contract (mrt-shared)
| Item | Android | Status |
|---|---|---|
| Pinned copy of `mrt-shared` `data/` + `test-vectors/` | `shared/` + `shared/SHA256SUMS`; `scripts/sync-shared.sh` refreshes it; the build fails if a file doesn't match its hash or if `/workspace/mrt-shared` (or `MRT_SHARED_DIR`) differs | ✅ |
| Constants from `data/*.json` (palette, show, spin-sound onsets) | Generated `SharedContract.kt` at build time (one source) | ✅ |
| Vector tests | `app/src/test/.../shared/*VectorsTest.kt`: rng, wheel, selection (incl. the full app spin sequence through `RouletteViewModel`), catalog, rss, stats | ✅ |
| D1 RNG mapping · D2 palette · D3 history file · D4 click-synced spin + tap-to-stop · D5 re-spin notice · D7 Discussed on · D8 brand colors · D9 empty posterURL rejected | Matched to iOS | ✅ |
| D6 rotation normalised mod 360 | Kept (harmless; vectors compared mod 360) | ✅ by decision |
| D10 http→https artwork upgrade | Kept (Android is HTTPS-only) | ✅ by decision |
| D11 broken feed | Android throws `malformed` (listed in the vector's `acceptable`), so a bad download never replaces the cached feed | ✅ by decision |
| New, found by vectors: nbsp in show notes; “sample-ep-N” labels; Apple search / mailto query encoding (Foundation rules) | Matched to iOS | ✅ |
| Infamous jackpot selection | `JackpotSelector`: `FlaggedInfamousSelector` (default = iOS main), `RatingWeightedSelector` (infamous-selection.md, iOS 1.0.1 pending; V1–V5 + seed-42 vectors pass) | 🟡 swap when 1.0.1 lands on iOS main |

## Android-only items
| Item | Status |
|---|---|
| Release signing from env vars only; unsigned when absent | ✅ |
| Public-repo secret scan (pre-push hook + `scripts/scan-secrets.sh` + self-test) | ✅ |
| Robolectric unit + Compose UI tests; lint 0 errors | ✅ 175 tests |
| Play: targetSdk 36, minSdk 26, AAB, Data safety, AD_ID absent | 🟡 build side done; no AAB/upload key yet |
| Device/emulator testing | ⏸ KVM on this box crashes the host kernel (see NOTES.md) |
