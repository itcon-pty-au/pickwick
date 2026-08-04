# SantaTube 🎅

A kid-safe, whitelist-only YouTube app for Android phones, tablets and Google TV —
**parents choose exactly which channels exist; nothing else is reachable.**
No ads, no Shorts, no comments, no recommendations, no rabbit holes, no accounts, no cloud.

Open source (GPL-3.0), sideloaded — not distributed via app stores.

## Why

YouTube Kids' filters are algorithmic and leaky; commercial whitelist apps are paid,
closed source, and still show ads. SantaTube flips the model: an explicit allow-list,
curated from a parent's phone, enforced on the kid's device — with the streams played
directly (via [NewPipeExtractor](https://github.com/TeamNewPipe/NewPipeExtractor)),
so there are never ads.

## What the kid sees

- A grid of **parent-approved channels and playlists**, ordered by their own favourites
- **🎲 Surprise me** — a random mix drawn from allowed channels
- **❤️ My list** — videos they saved by holding a tile (long-press / hold OK on the remote)
- **Keep watching** — resume where they left off, on any of the family's devices
- A fullscreen player with no ads and nothing to escape into; playlists auto-play in order
- **NEW badges** when an allowed channel has fresh uploads
- **Time price tags** on tiles (0.5x … FREE, or 1.5x for "junk food" channels) —
  cheaper channels drain the clock slower, so kids can spend their time knowingly
- **Subtitles** on demand — CC button on phones, ▼ on the TV remote; the choice sticks
- Gentle **"5 minutes left" / "1 minute left"** warnings before time runs out
- Friendly screens when time is up: "Time for a break! ⏰", "It's bedtime! 🌙"

Fully D-pad navigable on TV (colored focus glow, remote shortcuts: OK = pause,
◀ ▶ = seek ±10s, ▼ = subtitles, any key shows elapsed/remaining time).

## What the parent controls (all from their phone)

Open settings (fingerprint-gated) on the phone:

- **Channels & playlists** — search YouTube by name and tap Add; or paste any
  channel/playlist link. One-time import from a hosted whitelist text file supported.
  Each source has a **screen-time multiplier chip** — tap to cycle
  1x → 1.25x → 1.5x → 0.75x → 0.5x → 0.25x → FREE (long-press resets) — so
  educational channels can cost less (or nothing) and junk can cost extra.
- **Screen time** — session length, sessions per weekday/weekend, break length,
  bedtime window. The daily budget is `session × sessions`; only actual watching
  counts, and stopping early never forfeits time.
- **Grant extra time** — +15/+30/+60 today, applied to every device instantly.
- **Kid devices** — sync status per device (settings are content-fingerprinted:
  matching `#hash` = provably in sync), push settings, per-device **stats**:
  what's playing right now — with a **pause/resume** button for "come to dinner"
  moments — minutes today, daily history, most-watched channels,
  recently watched. Stats snapshots stay on the phone, so reviewing the day —
  and ruling on AI-flagged videos — works even while the TV is off; decisions
  sync to it automatically the next time it's reachable.
- **Admin phones** — approve/deny new phones that ask to pair; revoke old ones.
- **App updates** — parent-triggered self-update from GitHub releases.

## Pairing (one minute, once)

1. Install the APK on the TV and on the parent's phone.
2. TV: settings → a QR code appears.
3. Phone: scan it with the camera → SantaTube opens → confirm.
4. The **first** phone becomes the admin automatically; any later phone needs
   approval on an existing admin phone (the QR itself grants nothing).

From then on the phone manages the TV over the home network: settings pushes,
grants, stats, and watch-state sync (resume positions + saved list follow the kid
across devices). Everything stays inside the house — there is no server.

Reinstalls don't cost your curation: uninstalling offers **Keep app data**,
Android backup covers the config where the platform supports it, and a freshly
reinstalled phone that re-pairs automatically copies the family's settings
(channels, blocked videos, safe-list, screen time) back from the TV — or pull
them manually per device (settings → Kid devices → **Pull**).

## Screen-time model

```
budget/day  = session × sessions (weekday or weekend)
sitting cap = session          — after that: a break of «break» minutes
bedtime     = hard window (may cross midnight)
grants      = parent adds minutes today; clears breaks; waives bedtime briefly
multiplier  = per-channel drain rate (FREE, 0.25x–1.5x): scales budget & sitting
              use only — bedtime and breaks always apply, even on FREE channels
```

Only actual playback consumes time. An idle gap of a break-length starts a fresh
sitting with nothing lost. Everything resets at midnight. No rules set → no limits.

## Quality

Thumbnail resolution and playback quality adapt to the device and connection:
up to 1080p on a TV with fast Wi-Fi (video+audio streams merged in ExoPlayer),
degrading gracefully to lighter streams on weak links.

## Building

Open in Android Studio, or:

```
gradlew assembleDebug
```

Sideload `app/build/outputs/apk/debug/app-debug.apk`:
phone — install the APK directly; Google TV — enable Developer mode and
`adb install` (USB or `adb connect <tv-ip>` over Wi-Fi).

minSdk 26 · Kotlin + Jetpack Compose · Media3/ExoPlayer · NewPipeExtractor · GPL-3.0

## Releasing updates (self-update)

The app checks `version.json` in this repo (parent settings → Check for updates):

1. Bump `versionCode`/`versionName` in `app/build.gradle.kts`, build the APK.
2. Create a GitHub Release (tag `vX.Y.Z`) with the APK attached.
3. Point `version.json` at it:

```json
{ "versionCode": 2, "versionName": "0.2.0",
  "apkUrl": "https://github.com/<you>/santatube/releases/download/v0.2.0/app-debug.apk" }
```

Updates must be signed with the same key as the installed build.

## When YouTube extraction breaks

Symptom: videos stop resolving app-wide (spinners, "Could not play video") while
YouTube itself works. Cause is almost always a server-side change that
NewPipeExtractor hasn't caught up with yet.

1. Check [NewPipeExtractor issues](https://github.com/TeamNewPipe/NewPipeExtractor/issues)
   — a global breakage will have a fresh, very active issue, usually with a fix
   merged within days.
2. Bump `newpipeextractor` in `gradle/libs.versions.toml` to the newest
   [release tag](https://github.com/TeamNewPipe/NewPipeExtractor/releases) — or, if
   the fix is merged but unreleased, to the fix's **commit SHA** (JitPack builds any
   commit, e.g. `newpipeextractor = "e1853be2b"`).
3. Verify extraction against live YouTube:
   `gradlew :app:testDebugUnitTest --tests "io.santatube.app.ExtractorSmokeTest"`
   — `resolvesStream` is the playback path kids feel first.
4. `gradlew assembleDebug`, then `adb install -r` to each device (or ship a
   self-update release, see above).

Transient failures (throttling, flaky Wi-Fi, bot checks) are already retried with
escalating backoff inside `YouTubeRepository` — a real breakage is one that
persists across retries and app restarts.

## Support SantaTube ❤️

SantaTube is free and open source, but it isn't maintenance-free: YouTube changes
its internals every few weeks, and when that happens playback breaks for every
family using the app until someone updates the extractor, re-tests, and ships a
release. That work is ongoing for as long as the app exists.

If SantaTube is part of your family's routine, a small **monthly donation** is
the most useful way to help — it's the recurring nature of the maintenance that
makes recurring support matter. One-off donations are appreciated too.

**[Donate via Stripe](https://donate.stripe.com/REPLACE_ME)** — card,
Apple Pay or Google Pay; no account needed.

Donations fund maintenance of a hobby project; they aren't a purchase and don't
come with support guarantees.

## Privacy & good-citizen notes

- No accounts, no analytics, no cloud: history, stats and settings live on your
  devices; phone↔TV traffic never leaves the LAN (token-authenticated).
- Plays streams directly rather than through YouTube's ad-supported player, which
  is against YouTube's Terms of Service — the same trade NewPipe users accept.
  For personal/family use; that's why it isn't in any app store.
- Occasionally YouTube changes internals and extraction breaks until the
  NewPipeExtractor dependency is updated — expect rare short outages.

## Roadmap

- [ ] First public GitHub release (signed APK + self-update live)
- [ ] AI-assisted curation (under discussion)
- [x] Donations / sustainability (Stripe link pending)
- [x] Everything above
