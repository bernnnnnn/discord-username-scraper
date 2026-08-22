# Noctra

An Android app that hunts for unused 4-character Discord usernames in the background and keeps
a permanent log of everything it has tried, so stopping and restarting never loses progress.

Themed after the [Noctra](https://raw.githubusercontent.com/backroomsa3-a11y/Mythemesssss/refs/heads/main/E.json)
Discord theme: AMOLED black, slate-blue accents (`#3C4856`), pale blue highlights (`#D8E8FF`),
with a matching crescent-moon adaptive launcher icon.

## What it does

- **Background scanning.** A foreground service walks the 4-character name space, one check at a
  time, at a pace you set. It keeps running with the screen off and survives being swiped away
  (Android restarts it and it picks up exactly where it stopped).
- **Remembers everything.** Every answer is written to SQLite. Two tabs read from it:
  - **Available** — names Discord reported as free.
  - **Tried** — every name checked, free or taken, newest first.
- **Never re-checks.** Progress is a single cursor into a shuffled walk of the name space, saved
  after each batch. Names already in the log are skipped without a network call.
- **Notifies on a hit.** Each free name fires its own notification; tap a row in either tab to
  copy the name.
- **Stays responsive.** No Compose, no ORM — plain views, a recycler with paged queries
  (150 rows at a time), batched writes, and list refreshes throttled and suppressed while you
  are scrolled down. The Tried tab stays smooth at hundreds of thousands of rows.

## Settings

| Setting | Default | Notes |
| --- | --- | --- |
| Character set | `a–z 0–9` (1,679,616 names) | Also `a–z` (456,976) or `a–z 0–9 _` (1,874,161). Changing it restarts the walk but keeps the log. |
| Delay between checks | 1200 ms | The floor. Lower is faster and more likely to hit a rate limit. Minimum 250 ms. |
| Auto-pace | on | Multiplies the delay by 1.5 on every 429 (up to 30 s) and shaves 10% off after 25 clean checks, so the scan converges on the fastest rate Discord will allow instead of you guessing. |
| Stop after N found | 0 | 0 keeps scanning forever. |
| Discord token | empty | Optional — see below. |

### If you keep getting rate limited

Being rate limited is not fatal: the app waits out the exact `retry_after` Discord returns and
carries on from the same cursor. But if it stalls constantly, in order:

1. **Leave Auto-pace on.** It finds the sustainable rate by itself; the footer shows the delay
   actually in use and how many 429s this run has taken.
2. **Add a token** (Settings → Discord token). The unauthenticated endpoint is throttled per IP;
   the authenticated one is throttled per account, which is usually the more generous bucket.
3. **Raise the delay floor.** If auto-pacing has parked at the 30 s ceiling, the account or IP is
   in a slow bucket and a bigger floor will stall less.

There is no way to check faster by attempting a real username change: `PATCH /users/@me` has no
dry-run, so a free name would actually be claimed by the account making the request, it needs the
account password rather than just a token, and account-mutation endpoints are throttled far more
tightly than the availability check.

### Which endpoint it uses

Discord has moved this endpoint before, so the app probes candidates on the first check of a run
and locks onto whichever answers. A missing path *or a refused token* moves to the next, so a bad
token can never dead-end a run while the public check still works. The footer names the winner.

| Order | Endpoint | Needs a token | Verified |
| --- | --- | --- | --- |
| 1 | `POST /api/v9/users/@me/pomelo-attempt` | yes | 401 without auth, so it exists |
| 2 | `POST /api/v9/unique-username/username-attempt` | yes | 404 — gone |
| 3 | `POST /api/v9/unique-username/username-attempt-unauthed` | no | 200 `{"taken":true}` |

All take `{"username": "abcd"}` and answer `{"taken": true|false}`. None can claim or change a
name. **The unauthenticated endpoint works, so no account is needed.**

### What to expect

Measured against the live endpoint from one address, honouring every `retry_after`:

| Measurement (public endpoint) | Result |
| --- | --- |
| Cold burst, no delay | 14 of 15 returned 200 in ~4 s |
| Sustained, honouring `retry_after` | 14 checks, 7 rate limits, ~1.8 checks/min |
| Steady 3 s cadence, 45 requests | **8 ok, 37 refused — 3.3 checks/min** |
| `retry_after` seen | 60–260 s |
| 4-character names sampled | 37, **all taken** |
| Control (11-char random names) | `{"taken":false}` — the free path works |

The decisive detail is in the 429 headers:

```
x-ratelimit-scope: shared
```

The unauthenticated endpoint is **one global pool shared by everyone using it**, not a per-IP
allowance — and it is permanently drained, presumably by every other tool doing this. No pacing
strategy wins a contended shared bucket; ~3 checks/min is simply what is left over. At that rate a
full sweep is not a real plan.

`users/@me/pomelo-attempt` is scoped per user instead, which is why runs with a token hit the limit
far less. **If you want to sweep the space, the token is not an optimisation, it is the only
version of this that works.** The app already prefers that endpoint when a token is set, and now
names the shared pool in the status line when it is throttled without one.

Whatever endpoint you land on, read the **Rate** tile after ten minutes and multiply: that number
× 1,440 is your names per day, against 1,679,616 for `a–z 0–9` or 456,976 for `a–z`.

### About the token

No account is needed — the unauthenticated endpoint the Discord signup form uses still works, and
answers "is this name taken?" and nothing else.

A token only changes which bucket you are throttled in: per account instead of per IP. It is
strictly optional, and using a user token with a third-party client is self-botting under
Discord's ToS, so it carries real account risk. If one is set and Discord refuses it, the app
falls back to the public check and says so in the status line rather than stopping. The token is stored in this app's private `SharedPreferences` on your device and is
sent to `discord.com` only. Nothing else is transmitted anywhere.

The app only *asks* whether a name is free — it never registers or claims one. Rate limits are
always honoured: a 429 makes it wait for exactly the `retry_after` Discord returns, and network
errors back off exponentially. Automated querying is a grey area under Discord's ToS; keep the
delay sane and use it at your own risk.

## Getting the APK

Every push builds a signed release APK in GitHub Actions:

- **Releases → `latest`** has the `.apk` attached, or
- **Actions → Build APK → the run → Artifacts → `noctra-apk`**.

Sideload it (Android 8.0 / API 26 and up). On first launch allow notifications, then hit
**Start scanning**. Also worth doing: exclude Noctra from battery optimisation
(Settings → Apps → Noctra → Battery → Unrestricted) so long scans are not paused.

## Building locally

```bash
./gradlew assembleRelease     # app/build/outputs/apk/release/app-release.apk
```

Needs JDK 17 and an Android SDK with platform 34.

### Signing

No keystore or password is committed. Release signing is loaded from outside the repo:

- **Locally:** copy `keystore.properties.example` to `keystore.properties` (git-ignored) and
  point it at your own key. Generate one with
  `keytool -genkeypair -v -keystore my-release.jks -alias noctra -keyalg RSA -keysize 2048 -validity 10000`.
- **CI:** set the repository secrets `RELEASE_KEYSTORE_BASE64` (the `.jks` base64-encoded),
  `RELEASE_KEYSTORE_PASSWORD`, `RELEASE_KEY_ALIAS`, and `RELEASE_KEY_PASSWORD`.

If none are configured the build falls back to the auto-generated debug key, so a plain
checkout still builds — it just won't install as an update over a differently-signed copy.

## Layout

| File | Role |
| --- | --- |
| `NameSpace.kt` | Maps a cursor to a name via a coprime-stride bijection, so the walk looks shuffled but resumes from one integer. |
| `DiscordClient.kt` | The single availability request, with 429/backoff handling. |
| `ScraperService.kt` | Foreground service: the loop, batched writes, notifications, wake lock. |
| `Db.kt` | SQLite log, paged reads, counts. |
| `ScraperState.kt` | The stats the UI observes. |
| `MainActivity.kt` / `ListFragment.kt` | Header stats, the two tabs, paging. |
