# MRT Android ↔ iOS parity checklist (living)

Canonical copy: `/workspace/mrt-android/PARITY.md` (box). Mirrored into the repo root by `scripts/sync-docs.sh`.
iOS source of truth: `movieroulettetuesday/movie-roulette-tuesday@main` (read-only clone at `/workspace/mrt-android/ios-ref`, reviewed at `b673df1`, 2026-10-20).
Android repo: https://github.com/movieroulettetuesday/mrt-android (public).

Status: ✅ done · 🟡 partial · ⬜ not started · ⏸ blocked on a decision.
Effort (Android work, rough): **S** ≤ ½ day · **M** 1–2 days · **L** 3–5 days · **XL** > 1 week.

_Last updated 2026-10-21 (ET) after merging skeleton `a323430`, secret-scan fix `c7b4e0d`, catalog `19192d5` and wheel `f4b1f37`._

## 0. App shell
| iOS (SwiftUI) | Android (Compose) | Effort | Status |
|---|---|---|---|
| `MovieRouletteTuesdayApp.swift`, `ContentView.swift` (TabView: Roulette, Episodes, Movies, Stats) | `MainActivity.kt`, `ui/MrtApp.kt` (single activity, `NavigationBar` + `NavHost`, same tab order) | S | ✅ |
| Episodes tab badge (new since last viewed, `NewEpisodeBadge.swift`) | `NavigationBar` `BadgedBox` + `EpisodeRepository.markViewed` (SharedPreferences `episodes.lastViewedAt`, first-load baseline) | S | ✅ tested |
| `Shared/Brand.swift`, `CleanPro.swift`, `Color+Hex.swift`, asset colors (BrandTeal/Peach, WheelRim, LaunchBackground) | `ui/theme/Theme.kt` (`BrandColors`, M3 light/dark schemes), `catalog/HexColor.kt` | S | 🟡 colors ported; CleanPro card styling not yet |
| `AppearancePreference.swift`, `Appearance.swift` (System/Light/Dark) | DataStore pref + `MrtTheme(darkTheme=…)` | S | ⬜ |
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
| `RouletteCore/RandomGenerators.swift` (SplitMix64) | `SeededRandom` (same algorithm, reference values tested) | S | ✅ |
| `Roulette/RouletteViewModel.swift` (load, spin, watchdog, forced re-spin 1.6 s, deferred catalog swap, reveal, spin again) | `roulette/RouletteViewModel.kt` | M | ✅ 8 tests |
| `Roulette/WheelView.swift`, `HubSpinButton.swift`, `RouletteView.swift` (slices, labels, pointer, glowing hub, tap wheel or hub to spin, Magic Tap) | `roulette/WheelView.kt`, `RouletteScreen.kt` (Canvas slices from the same geometry, readable labels, pointer, hub; tap wheel or hub) | M | 🟡 hub glow/visual polish, TalkBack custom action pending |
| Animated spin timed to recording (`SpinMotion.swift`, `SpinSoundTrack.swift`, 6.294 s, wind-up + brake) | `roulette/SpinMotion.kt` (same speed curve as easing) | S | ✅ base curve; ⬜ per-click time warp |
| Spin sound `wheel-spin.m4a` once per spin; haptic per slice crossing (`SpinFeedback.swift`, `SpinTicks.swift`) | `SoundPool`/`MediaPlayer` + `HapticFeedbackConstants.SEGMENT_TICK` | M | ⬜ |
| Hub ■ stops a spin early (`SpinStopPlan`) | — | M | ⬜ |
| Reduce Motion: same timeline, 1–2 turns | Remove-animations setting → 1–2 turns (`RouletteRoute.kt`) | S | ✅ |
| `RevealSheet.swift` (category, then movie, year, "Discussed on…", spin count, Spin Again, jackpot styling) | `RevealSheet` in `RouletteScreen.kt` (ModalBottomSheet) | S | ✅ (jackpot styling basic) |
| `ShareCard.swift` (1080×1350 branded image) + `ShareLink` text "Tonight’s Movie Roulette Tuesday spin: Title (Year)" | `roulette/Share.kt`: `ACTION_SEND` text/plain chooser, same text and subject | S / M | ✅ text · ⬜ image card (render composable → PNG → FileProvider) |
| `ConfettiView.swift` | Compose particle effect | S | ⬜ |
| `RouletteTaglines.swift`, header "Dinner, discussion, and the wheel" | Header in `RouletteScreen.kt` | S | 🟡 tagline only |
| `RulesSheet.swift` | Bottom sheet | S | ⬜ |
| `SpinHistoryView.swift`, `SpinHistoryStore.swift`, `SpinHistoryLog.swift`/`FileStore` (500 detailed + archive) | `roulette/SpinHistory.kt` (`SpinHistoryStore`, JSON file, 500 + archive counts) | S / M | 🟡 store ✅, history screen ⬜ |
| `MoviePageView.swift` (from reveal / movies) | Movie detail screen | M | ⬜ |
| `Intents/SpinMovieRouletteIntent.swift` (Siri/Shortcuts) | App Shortcut (`shortcuts.xml`) "Spin" → deep link | S | ⬜ |
| `QuickSpin.swift` | Launcher shortcut | S | ⬜ |

## 2. Movies catalog
| iOS | Android | Effort | Status |
|---|---|---|---|
| `RouletteCore/Models.swift` (`MovieCatalog`, `Movie`, `RouletteCategory`, `ShowInfo`, `StreamingInfo`, tolerant new fields, `validationIssues`, `deduplicated`, `subtitle`) | `catalog/Models.kt`, `catalog/CatalogJson.kt` (same required/optional/tolerant rules) | S | ✅ 10 + 5 tests |
| `RemoteCatalog.swift` (raw GitHub URL, 8 s, validation, build-keyed cache, refresher), `CatalogSource.swift`, `Shared/AppCatalog.swift` | `catalog/CatalogLoading.kt`, `catalog/CatalogRepository.kt` (cache in `filesDir`, keyed by versionCode; 15 s refresh throttle) | M | ✅ 9 tests |
| Bundled `Resources/movies.json` fallback | `app/src/main/assets/movies.json` (+ `scripts/sync-bundled-catalog.sh`) | S | ✅ (snapshot of `b673df1`, 180 movies, 10 categories) |
| Pull-to-refresh catalog | `PullToRefreshBox` → `CatalogRepository.refresh()` | S | ⬜ (launch refresh ✅) |
| `Movies/MoviesView.swift` (search, sort, filters: category / watchlist / watched / rated by me / haven't spun yet; season/year grouping) | Movies screen (LazyColumn/Grid, SearchBar, FilterChips) | L | ⬜ |
| Where to Watch (`WhereToWatchDisplay`, `StreamingCatalog`, logos from image.tmdb.org, JustWatch/TMDB attribution, always visible with empty message) | Section on movie page (Coil for logos or plain `ImageBitmap` loader) | M | 🟡 model ✅, UI ⬜ |
| My Services (`MyServices`, "on my services", "available first") | DataStore + filter | S | ⬜ |
| `PersonalRatings.swift`, `PersonalRatingsStore.swift`, `StarRatingView.swift` (½–5 stars, Want to Watch / Watched) | Local store + star composable | M | ⬜ |
| `Artwork.swift`, `ArtworkLoader.swift`, `ArtworkImage.swift` | `ui/Artwork.kt`: dependency-free loader (memory LRU + disk cache, HTTPS-only, candidates best-first: episode art → poster → show art); `ArtworkResolver` port | M | ✅ (http artwork upgraded to https) |
| Host Rating (feature-flagged off on iOS) | Keep hidden | – | ⏸ off on iOS |

## 3. Episodes
| iOS | Android | Effort | Status |
|---|---|---|---|
| `RSSFeedParser.swift` (Buzzsprout `https://rss.buzzsprout.com/2117292.rss`, itunes:season/episode/image, fan-mail link strip) | `episodes/RssFeedParser.kt` (`XmlPullParser`; same fields, fullest notes, fan-mail strip, guid→link fallback, dedupe, RFC 822 variants); iOS fixture + trimmed live-feed fixture | M | ✅ 5 tests; the live feed parses all 184 episodes, 180 linked to the catalog |
| `EpisodeStoreCore.swift`, `Episodes/EpisodeStore.swift` (cache, refresh) | `episodes/EpisodeRepository.kt` (cache-first, 20 s timeout, 5-min freshness, shared in-flight load, soft offline error, `episodes-cache.json`), `EpisodeGrouper`/`EpisodeGroupExpansion` (same serialized format) | M | ✅ 5 + 6 tests |
| `EpisodesView.swift` (season sections), `EpisodeDetailView.swift` (About, Movie Discussed) | `episodes/EpisodesScreen.kt` (Season/Year/All segmented, collapsible remembered headers, pull-to-refresh, Offline label, footer count + updated), `EpisodeDetailScreen.kt` (artwork, About, fan mail, Movie Discussed, Listen) | M | ✅ 2 Compose tests (phone layout; no split view yet) |
| `ListenCard.swift`, `ShowLinks.swift`, `AppleEpisodeLinks.swift` (Apple exact episode via iTunes lookup, Spotify search, Overcast, Buzzsprout) | `episodes/PodcastLinks.kt` (`PodcastLinks`, `AppleEpisodeLookup` hourly-throttled cache, Spotify search cleaner, `ListenLinks`) → `ACTION_VIEW` | M | ✅ 5 tests (iOS title cases ported) |
| `EpisodeLabel.swift` ("Discussed on …") | `roulette/Share.kt` `EpisodeLabel` (catalog-only until feed lands) | S | 🟡 |

## 4. Stats
| iOS | Android | Effort | Status |
|---|---|---|---|
| `SpinStats.swift`, `Stats/StatsView.swift` (spin overview, top movie/category, Your Ratings count/average/top) | Stats screen | M | ⬜ (data from `SpinHistoryStore` is ready) |

## 5. Settings (iOS: gear sheet on Roulette)
| iOS | Android | Effort | Status |
|---|---|---|---|
| `SettingsSheet.swift`, `AppSettings.swift`: Appearance; Spin sound; Haptics; New-episode alert; My services; Auto-share my ratings; Follow the Show (8 links); Suggest a movie; Credits & About (version, TMDB credit/notice); Privacy note | Settings screen (DataStore) | M | ⬜ |

## 6. New-episode notifications
| iOS | Android | Effort | Status |
|---|---|---|---|
| `NewEpisodeAlerts.swift` (opt-in, BGAppRefresh ~6 h, watermark of seen guids (50), local notification "New episode of Movie Roulette Tuesday", tap → episode) | WorkManager periodic (6 h, network constraint) → RSS diff → `NotificationCompat`; `POST_NOTIFICATIONS` asked only when toggled on (API 33+); tap → `mrt://episode/<guid>`; no FCM | M | ⬜ |

## 7. Home-screen widget
| iOS | Android | Effort | Status |
|---|---|---|---|
| `MovieRouletteTuesdayWidget/SpinOfTheDayWidget.swift`, `SpinOfTheDay.swift` (FNV-1a deterministic daily pick), `WidgetSnapshot.swift`, `WidgetBridge.swift` (latest episode, last spin; small/medium/large/accessory) | Jetpack Glance widget; port FNV-1a so the same day shows the same pick as iOS | M | ⬜ |

## 8. Suggest a Movie
| iOS | Android | Effort | Status |
|---|---|---|---|
| `SuggestMovieSheet.swift`, `MovieSuggestion` (mailto `theMrtPodcast@gmail.com`, subject "Movie suggestion: …", body template) | `ACTION_SENDTO mailto:` with same subject/body; copy address if no mail app | S | ⬜ |
| `InAppSuggestion` → CloudKit `Suggestion` record (title ≤120, note ≤280, credit ≤60, 1 per 10 min) | Needs backend | M | ⏸ backend decision |

## 9. Polls (CloudKit) and 10. Shared ratings (CloudKit)
| iOS | Android | Effort | Status |
|---|---|---|---|
| `Community.swift` (`PollFactory` default poll per episode via FNV-1a: good/bad, recommend, who won the argument; `polls.json` overrides; `PollResults`, `RatingAggregate`, `CommunityBackoff`, `TimedCache`) | Pure Kotlin port (no backend needed) | M | ⬜ |
| `CloudKitCommunityStore.swift`, `CommunityService.swift`, `EpisodePollSection.swift`, `ListenerRatingSection.swift` (`PollVote`, `ListenerRating` stars + halfStars; record names hash iCloud user id) | `CommunityStore` interface + backend adapter | L | ⏸ see `backend-options.md` |
| `-MRTMockCommunity` in-memory store | Fake store for tests/previews | S | ⬜ |

## Android-only items
| Item | Status |
|---|---|
| Release signing from env vars only; unsigned when absent | ✅ |
| Public-repo secret scan (pre-push hook + `scripts/scan-secrets.sh` + self-test) | ✅ |
| Robolectric unit + Compose UI tests; lint 0 errors | ✅ 60 tests |
| Play: targetSdk 36, minSdk 26, AAB, Data safety, AD_ID absent | 🟡 build side done; no AAB/upload key yet |
| Device/emulator testing | ⏸ KVM on this box crashes the host kernel (see NOTES.md) |
