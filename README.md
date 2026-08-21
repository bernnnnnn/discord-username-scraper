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
and locks onto whichever answers. A 404/405 moves to the next; a 401 or 403 stops the run, because
that means the path was right and something else is wrong. The footer names the winner.

| Order | Endpoint | Needs a token |
| --- | --- | --- |
| 1 | `POST /api/v9/users/@me/pomelo-attempt` | yes |
| 2 | `POST /api/v9/unique-username/username-attempt` | yes |
| 3 | `POST /api/v9/unique-username/username-attempt-unauthed` | no |

All three take `{"username": "abcd"}` and answer `{"taken": true|false}`. None of them can claim or
change a name. As of this build the unauthenticated endpoint 404s, so a token is effectively
required.

### About the token

The unauthenticated endpoint the Discord signup form used now returns 404, so in practice you
need a token: with one set the app uses `users/@me/pomelo-attempt`, which answers "is this name
taken?" and nothing else. The token is stored in this app's private `SharedPreferences` on your device and is
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

`noctra-release.jks` is committed so that every build — local or CI — is signed with the same
key and installs as an update over the previous one. It guards nothing; if you plan to
distribute the app, replace it and move the credentials in `app/build.gradle.kts` into
Gradle properties or CI secrets.

## Layout

| File | Role |
| --- | --- |
| `NameSpace.kt` | Maps a cursor to a name via a coprime-stride bijection, so the walk looks shuffled but resumes from one integer. |
| `DiscordClient.kt` | The single availability request, with 429/backoff handling. |
| `ScraperService.kt` | Foreground service: the loop, batched writes, notifications, wake lock. |
| `Db.kt` | SQLite log, paged reads, counts. |
| `ScraperState.kt` | The stats the UI observes. |
| `MainActivity.kt` / `ListFragment.kt` | Header stats, the two tabs, paging. |
