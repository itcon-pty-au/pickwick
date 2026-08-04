# Pickwick 🏮

<p align="center">
  <a href="https://pickwick.tv/donate.html">
    <img src="https://img.shields.io/badge/%E2%9D%A4%EF%B8%8F%20Donate-keep%20Pickwick%20maintained-00897B?style=for-the-badge&labelColor=00695C" alt="Donate — keep Pickwick maintained">
  </a>
  <br>
  <sub>Free forever &amp; open source — donations fund the upkeep that keeps playback working.</sub>
</p>

A kid-safe, whitelist-only video player for Android phones, tablets and Google TV —
**parents choose exactly which YouTube channels exist; nothing else is reachable.**
No ads, no Shorts, no comments, no recommendations, no rabbit holes, no accounts, no cloud.

Open source (GPL-3.0), sideloaded — not distributed via app stores.

## Why

YouTube Kids' filters are algorithmic and leaky; commercial whitelist apps are paid,
closed source, and still show ads. Pickwick flips the model: an explicit allow-list,
curated from a parent's phone, enforced on the kid's device — with the streams played
directly (via [NewPipeExtractor](https://github.com/TeamNewPipe/NewPipeExtractor)),
so there are never ads.

## What the kid sees

- A grid of **parent-approved channels and playlists**, ordered by their own favourites
- **🎲 Surprise me** — a random mix drawn from allowed channels
- **❤️ My list** — videos they saved by holding a tile (long-press / hold OK on the remote)
- **Keep watching** — resume where they left off, on any of the family's devices
- **📥 Downloads** — a row of videos saved for offline; the kid can *ask* for a
  video from its poster ("Waiting for approval…") and a parent approves on their
  phone, so car trips and dead Wi-Fi still work
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

Open settings (fingerprint-gated, with a 4-digit parent PIN as fallback) on the phone:

- **Channels & playlists** — search YouTube by name and tap Add; or paste any
  channel/playlist link. Import from a hosted whitelist text file, and
  **export/share** your own list back out (save to file or share sheet) so other
  families can import it. **Discover with AI** describes what you want in plain
  words ("fun science experiments for kids") and proposes channels, each verified
  against YouTube before it can be added.
  Each source has a **screen-time multiplier chip** — tap to cycle
  1x → 1.25x → 1.5x → 0.75x → 0.5x → 0.25x → FREE (long-press resets) — so
  educational channels can cost less (or nothing) and junk can cost extra.
- **Screen time** — session length, sessions per weekday/weekend, break length,
  bedtime window. The daily budget is `session × sessions`; only actual watching
  counts, and stopping early never forfeits time.
- **Grant extra time** — +15/+30/+60 today, applied to every device instantly.
- **Pause for today** — one tap stops watching on every device until midnight.
- **AI content screening** *(optional, off by default)* — screen new videos against
  your own house rules ("no horror, no unboxing, no fake challenges") using any
  OpenAI-compatible endpoint: Anthropic, OpenRouter, or a local server. Verdicts are
  allow / block / **review** — unsure ones queue for you to rule on, and decisions
  sync to the kid's devices. Only titles, channel names and durations are sent —
  never watch history — and each video is screened once per rules version, so the
  catalog isn't re-screened (or re-billed) on every launch. Child age and a
  connection test are built in.
- **Offline downloads** — approve (or decline) the kid's requests, pick download
  quality, watch progress, cancel or delete. Files live in the app's private
  storage and survive reboots.
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
3. Phone: scan it with the camera → Pickwick opens → confirm.
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

## Installing

Grab the APK from [Releases](https://github.com/itcon-pty-au/pickwick/releases)
(or build it yourself, below). Phone/tablet — open the APK and install;
Google TV — enable Developer mode and `adb install` (USB or
`adb connect <tv-ip>` over Wi-Fi). After that, updates come from inside the app
(parent settings → Check for updates).

## Privacy & good-citizen notes

- No accounts, no analytics, no cloud: history, stats and settings live on your
  devices; phone↔TV traffic never leaves the LAN (token-authenticated).
- The one exception is **AI screening**, which is off unless you turn it on and
  supply your own endpoint/key. Even then it sends only video titles, channel names
  and durations — never watch history — and pointing it at a local server keeps
  everything in the house.
- Plays streams directly rather than through YouTube's ad-supported player, which
  is against YouTube's Terms of Service — the same trade NewPipe users accept.
  For personal/family use; that's why it isn't in any app store.
- Occasionally YouTube changes internals and extraction breaks until the
  NewPipeExtractor dependency is updated — expect rare short outages.

## Contributing

The most valuable contributions aren't code: early breakage reports, bug reports
from TV models we've never seen, translations — and **channel lists**. Curated,
themed whitelists live in [`whitelists/`](whitelists/) and can be imported
straight into the app; sharing yours is the easiest way to help other families.
See [CONTRIBUTING.md](CONTRIBUTING.md).

## Support Pickwick ❤️

Pickwick is free and open source, but it isn't maintenance-free: YouTube changes
its internals every few weeks, and when that happens playback breaks for every
family using the app until someone updates the extractor, re-tests, and ships a
release. That work is ongoing for as long as the app exists.

If Pickwick is part of your family's routine, a small **monthly donation** is
the most useful way to help — it's the recurring nature of the maintenance that
makes recurring support matter. One-off donations are appreciated too.

**[❤️ Donate](https://pickwick.tv/donate.html)** — monthly or
one-time, via Stripe; card, Apple Pay or Google Pay; no account needed.

Donations fund maintenance of a hobby project; they aren't a purchase, aren't
tax-deductible, and don't come with support guarantees.

---

# For developers

*Everything below is about building, releasing and maintaining Pickwick —
parents can stop reading here.*

## Building

Open in Android Studio, or:

```
gradlew assembleDebug
```

The APK lands at `app/build/outputs/apk/debug/app-debug.apk` — sideload as above.

minSdk 26 · Kotlin + Jetpack Compose · Media3/ExoPlayer · NewPipeExtractor · GPL-3.0

## Releasing updates (self-update)

The app checks `version.json` in this repo (parent settings → Check for updates):

1. Bump `versionCode`/`versionName` in `app/build.gradle.kts`, build the APK.
2. Create a GitHub Release (tag `vX.Y.Z`) with the APK attached.
3. Point `version.json` at it:

```json
{ "versionCode": 2, "versionName": "0.2.0",
  "apkUrl": "https://github.com/<you>/pickwick/releases/download/v0.2.0/app-debug.apk" }
```

Updates must be signed with the same key as the installed build.

## When YouTube extraction breaks

Symptom: videos stop resolving app-wide (spinners, "Could not play video") while
YouTube itself works. Cause is almost always a server-side change that
NewPipeExtractor hasn't caught up with yet.

A scheduled CI canary ([extractor-smoke](.github/workflows/extractor-smoke.yml))
runs the smoke tests against live YouTube twice a day and automatically opens an
`extractor-breakage` issue when they fail (and closes it on recovery) — check
[open issues](https://github.com/itcon-pty-au/pickwick/issues) before diagnosing
locally.

1. Check [NewPipeExtractor issues](https://github.com/TeamNewPipe/NewPipeExtractor/issues)
   — a global breakage will have a fresh, very active issue, usually with a fix
   merged within days.
2. Bump `newpipeextractor` in `gradle/libs.versions.toml` to the newest
   [release tag](https://github.com/TeamNewPipe/NewPipeExtractor/releases) — or, if
   the fix is merged but unreleased, to the fix's **commit SHA** (JitPack builds any
   commit, e.g. `newpipeextractor = "e1853be2b"`).
3. Verify extraction against live YouTube:
   `gradlew :app:testDebugUnitTest --tests "io.pickwick.app.ExtractorSmokeTest"`
   — `resolvesStream` is the playback path kids feel first.
4. `gradlew assembleDebug`, then `adb install -r` to each device (or ship a
   self-update release, see above).

Transient failures (throttling, flaky Wi-Fi, bot checks) are already retried with
escalating backoff inside `YouTubeRepository` — a real breakage is one that
persists across retries and app restarts.

## Roadmap

- [ ] First public GitHub release (signed APK + self-update live)
- [x] Contribution scaffolding (CI canary, community whitelists, issue templates)
- [x] AI-assisted curation (channel discovery + rules-based screening)
- [x] Offline downloads with parent approval
- [x] Donations / sustainability
