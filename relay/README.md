# mrt-community-relay (Cloudflare Worker)

Write relay for **Option A** of the Android community features (listener polls, listener ratings,
in-app suggestions). CloudKit stays the source of truth:

- **Reads**: the Android app queries the CloudKit public database directly through CloudKit Web
  Services with a read-only API token (`MRT_CLOUDKIT_API_TOKEN` at build time).
- **Writes**: the app posts to this Worker (`MRT_RELAY_URL`), which validates the request like the iOS
  app and saves it with a CloudKit server-to-server key (`CK_KEY_ID`, `CK_PRIVATE_KEY`, Worker secrets).

Not deployed yet. Setup steps: [`../KEYS_SETUP.md`](../docs/KEYS_SETUP.md).

## Endpoints

| Route | Body | CloudKit record |
|---|---|---|
| `POST /v1/vote` | `{pollId, option, voterKey}` | `PollVote` `vote-<safe(pollId)>-<voterKey>` (`pollId`, `option`) |
| `POST /v1/rating` | `{movieId, halfStars, voterKey}` | `ListenerRating` `rating-<safe(movieId)>-<voterKey>` (`movieId`, `halfStars`, `stars` = rounded whole stars) |
| `POST /v1/suggestion` | `{title, category, note, creditName, appVersion}` | `Suggestion` `suggestion-<UUID uppercase>` (same 5 fields as iOS) |

`voterKey` is `sha256(installId + pollId)` / `sha256(installId + "rating:" + movieId)`, computed on the
phone, so the names follow the iOS formula (`CommunityRecordNames`) with a random install id in place of
the iCloud user record name. The install id never reaches the relay. Votes and ratings use
`forceReplace` (re-voting overwrites the same record); suggestions use `create`.

Success returns CloudKit's `records/modify` JSON (`{"records":[…]}`). Errors return
`{"error": code, "message": …, "retryAfter"?}`: `bad_request`, `invalid_option`, `invalid_stars` (400),
`rate_limited` (429, `Retry-After: 60`), `busy` / `quota` / `not_configured` (503), `cloudkit` (502).

## Validation (matches iOS)
- `pollId`: `[A-Za-z0-9._-]`, at most 200 characters (iOS poll ids are `safeComponent(guid)` + a suffix).
- `option` must belong to the poll kind from `PollFactory`: `.good-bad` → good|bad, `.recommend` → yes|no,
  `.argument` → host-N|tie, any other id (a `polls.json` override) → opt-N.
- `halfStars`: an integer from 1 to 10. `voterKey`: 64 lowercase hex characters.
- Suggestions: title 1–120 characters after trimming, note ≤ 280, credit name ≤ 60 (`InAppSuggestion` limits).
- Bodies must be JSON objects of at most 4 KB.

## Rate limiting (choice)
The Workers **Rate Limiting API** bindings in `wrangler.toml`, keyed by client IP + route: 20 writes per
minute for votes/ratings, 2 per minute for suggestions (the app adds its own 1 per 10 minutes). The
bindings are free and need no storage. Their counters are per Cloudflare location and eventually
consistent: good enough to stop a script flooding a poll, though not an exact quota. Duplicate votes are
impossible anyway, because each voter key has one record that gets overwritten. Without a binding
(tests, `wrangler dev`), an in-memory window per isolate gives the same behaviour. If abuse ever shows
up, add Cloudflare Turnstile or Play Integrity in front of `/v1/*`.

## Signing
`X-Apple-CloudKit-Request-SignatureV1 = base64(DER(ECDSA-P256-SHA256("<date>:<base64(sha256(body))>:<subpath>")))`,
with `X-Apple-CloudKit-Request-KeyID` and `X-Apple-CloudKit-Request-ISO8601Date` (`yyyy-MM-ddTHH:mm:ssZ`).
`CK_PRIVATE_KEY` can be the SEC1 PEM (`openssl ecparam -genkey`) or PKCS#8.

## Develop
```sh
cd relay
npm ci
npm test            # vitest; uses a throwaway key generated in memory per run (never on disk)
npm run typecheck
npx wrangler deploy --dry-run --outdir /tmp/relay-dry   # bundle + config check, no upload, no login
```
Never commit `.dev.vars`, keys or tokens (they're gitignored, and `scripts/scan-secrets.sh` blocks them).
