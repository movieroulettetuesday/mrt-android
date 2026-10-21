# Community keys setup (Option A: CloudKit Web Services reads + Cloudflare Worker relay writes)

_Status (Oct 21, 2026): code done and tested. **No keys, tokens or accounts exist yet; nothing is deployed.**
Matthew does steps 1–3 (they need his Apple Developer and a new Cloudflare login). Steps 4–6 can
then be run on the box. Nothing here goes into git: the token and URL are build-time environment
variables, the private key stays on the box, and the Worker secrets live in Cloudflare._

Container `iCloud.com.movieroulettetuesday.app`, **production** environment, **public** database.
Record types `PollVote`, `ListenerRating` and `Suggestion` already exist for iOS (`CloudKit/SCHEMA.md` in the iOS repo).

## 0. Already done on the box
- A P-256 key pair for the server-to-server key, created with
  `openssl ecparam -name prime256v1 -genkey -noout -out eckey.pem`.
  - Private key: `/home/box/mrt-secrets/cloudkit-s2s/eckey.pem` (mode 600, outside every git checkout, never copied anywhere).
  - Public key (safe to share; this is what gets pasted into the CloudKit Console):
    ```
    -----BEGIN PUBLIC KEY-----
    MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAExAuGKNxR7dkbFb7W4DrHLWFG+hSx
    p1GrRJCcMCtNvF3GT0t7UGdIZNRurEh5zkbmhCo1WoiWK2fiVTf4gra1fg==
    -----END PUBLIC KEY-----
    ```
  - To regenerate (for example if the box is ever wiped): run the same `openssl` command, then
    `openssl ec -in eckey.pem -pubout`, and replace the key in step 2.

## 1. CloudKit read-only API token (Matthew, CloudKit Console)
1. Go to https://icloud.developer.apple.com/dashboard, open container **iCloud.com.movieroulettetuesday.app**, then **Tokens & Keys** (in the **Production** environment).
2. Under **API Tokens**, click **+**. Name it `mrt-android-read`. Sign-in callback: **Post Message** (unused). Allowed origins: any.
3. Save, then copy the token value. It reads the public database as an unauthenticated user. Per
   the schema that means **READ only** (`_world` READ; CREATE needs `_icloud`). It sits in the APK
   the same way a CloudKit JS token sits in a web page, so it is a client identifier, not a secret.
   Still, keep it out of git so it can be rotated freely.

## 2. Server-to-server key (Matthew, CloudKit Console)
1. Same page, **Server-to-Server Keys**, click **+**. Name it `mrt-android-relay`.
2. Paste the public key from step 0 and save.
3. Copy the **Key ID** (a long hex string). The private key never leaves the box or Cloudflare.

## 3. Cloudflare account (Matthew)
1. Sign up at https://dash.cloudflare.com/sign-up. The **Free** plan is enough: Workers Free gives 100,000 requests a day, and the Rate Limiting bindings are included.
2. Choose a workers.dev subdomain when prompted, e.g. `mrt`. The relay URL becomes `https://mrt-community-relay.<subdomain>.workers.dev`.
3. Either run `npx wrangler login` on the box yourself, or create an API token (template "Edit Cloudflare Workers") and provide it as `CLOUDFLARE_API_TOKEN` in the box shell for the next step only. Never commit it.

## 4. Secrets and deploy (box)
```sh
cd /workspace/mrt-android/repo/relay
npm ci
npm test                                   # 18 vitest tests, throwaway key in memory
npx wrangler secret put CK_KEY_ID          # paste the Key ID from step 2
npx wrangler secret put CK_PRIVATE_KEY < /home/box/mrt-secrets/cloudkit-s2s/eckey.pem
npx wrangler deploy                        # prints https://mrt-community-relay.<subdomain>.workers.dev
```
`CK_CONTAINER` and `CK_ENVIRONMENT` (production) are plain vars in `wrangler.toml`. The rate limits
are in the same file (20 writes per minute and 2 suggestions per minute per IP; see `relay/README.md`).

## 5. Smoke test (box)
```sh
RELAY=https://mrt-community-relay.<subdomain>.workers.dev
TOKEN=<api token from step 1>
# Write one vote to a throwaway poll id that no app shows:
curl -s -X POST "$RELAY/v1/vote" -H 'Content-Type: application/json' \
  -d "{\"pollId\":\"relay-smoke.good-bad\",\"option\":\"good\",\"voterKey\":\"$(printf smoke | sha256sum | cut -c1-64)\"}"
# Read it back the way the app does:
curl -s -X POST "https://api.apple-cloudkit.com/database/1/iCloud.com.movieroulettetuesday.app/production/public/records/query?ckAPIToken=$TOKEN" \
  -H 'Content-Type: application/json' \
  -d '{"query":{"recordType":"PollVote","filterBy":[{"fieldName":"pollId","comparator":"EQUALS","fieldValue":{"value":"relay-smoke.good-bad"}}]},"resultsLimit":5}'
```
Expected: `{"records":[{"recordName":"vote-relay-smoke.good-bad-…"…}]}` from both calls. The smoke
record can be deleted afterwards in the CloudKit Console (Records → Public → PollVote).

If something fails:
- `AUTHENTICATION_FAILED` from the relay means a wrong Key ID or key. From the query, it means a wrong token or the wrong environment.
- `ACCESS_DENIED` / `PERMISSION_FAILURE` on create: the S2S identity needs CREATE on the record type. iOS uses `_icloud` CREATE. Check the security roles in the CloudKit Console.
- `BAD_REQUEST … not marked queryable`: `pollId` / `movieId` need the QUERYABLE index in **production** (iOS already relies on them).
- Votes use `operationType: forceReplace`, so a re-vote overwrites the voter's own record. If CloudKit ever rejects that for a record that doesn't exist yet, switch the relay to try `create` first (one-line change in `relay/src/cloudkit.ts`).

## 6. Build the Android app with community on (box)
```sh
cd /workspace/mrt-android/repo
export JAVA_HOME=/opt/jdk17 ANDROID_HOME=/opt/android-sdk
export MRT_CLOUDKIT_API_TOKEN=<token from step 1>
export MRT_RELAY_URL=https://mrt-community-relay.<subdomain>.workers.dev
./gradlew bundleRelease
```
- Without `MRT_CLOUDKIT_API_TOKEN`, every community UI stays hidden.
- With the token but no relay, polls and ratings are read-only ("Voting isn’t available in this version of the app.").
- With both, voting, listener ratings, auto-share and in-app suggestions are on.
- The token is baked into `BuildConfig`. Rotating it means a new build.

## Rotation / revocation
- API token: create a new one in the CloudKit Console, rebuild, then revoke the old one.
- S2S key: generate a new pair on the box, add it in the Console, run `wrangler secret put` again for both secrets, deploy, then revoke the old key. Records keep working, because they are keyed by voter hash, not by key.
- To switch writes off instantly: `npx wrangler delete`, or remove the secrets (the relay then answers `not_configured`, and the app shows the error and keeps working read-only).
