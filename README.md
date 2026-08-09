
  <p align="center">
  <picture><img src="site/logo.svg" alt="" width="50"></picture>
    <h1 align="center">Pickwick</h1>
  </p>
<p align="center">
  <a href="https://pickwick.tv/donate.html">
    <img src="https://img.shields.io/badge/%E2%9D%A4%EF%B8%8F%20Donate-keep%20Pickwick%20maintained-00897B?style=for-the-badge&labelColor=00695C" alt="Donate — keep Pickwick maintained">
  </a>
  <img src="https://img.shields.io/badge/status-beta-E8A33D?style=for-the-badge&labelColor=B87A24" alt="Status: beta">
  <br>
  <sub>Free forever &amp; open source — donations fund the upkeep that keeps playback working.</sub>
</p>

A kid-safe, whitelist-only video player for Android phones, tablets and Google TV —
**parents choose exactly which YouTube channels exist; nothing else is reachable.**
No ads, no Shorts, no comments, no recommendations, no rabbit holes, no accounts, no cloud.

Open source (GPL-3.0), sideloaded — not distributed via app stores.

> **Pickwick is in beta.** It's in daily use and the core — curating channels,
> pairing, playback, screen time — is solid. But it's developed by a small team
> on a handful of devices, so some of the newer features haven't yet been
> confirmed on every phone, tablet and TV combination. Expect the occasional
> rough edge, and please [open an issue](https://github.com/itcon-pty-au/pickwick/issues)
> when you hit one. Note too that curation lives only on the device: an uninstall
> wipes it, and there is no cloud backup to restore from.

## Why

YouTube Kids' filters are algorithmic and leaky; commercial whitelist apps are paid,
closed source, and still show ads. Pickwick flips the model: an explicit allow-list,
curated from a parent's phone, enforced on the kid's device with the streams are played
with no ads.

## What the kid sees

- **"Who's watching?"** — in a multi-kid family, each kid picks their own
  profile (a color + a friendly avatar, readable before they can read), and
  everything below is *theirs*: channels, resume points, saved list, screen
  time. A profile can carry a **secret lock code** — four presses on the
  remote's D-pad/OK buttons, entered blind with only dots on screen — so a
  younger sibling can't borrow an older kid's channels or clock.
- A grid of **parent-approved channels and playlists**, ordered by their own favourites
- **🎲 Surprise me** — a random mix drawn from allowed channels
- **❤️ My list** — videos they saved by holding a tile (long-press / hold OK on the remote)
- **Keep watching** — resume where they left off, on any of the family's devices
- **🔎 Search** — find videos by name, but only *inside* the allowed channels: it
  searches an on-device index of the whitelist, so a search can never surface
  anything a parent didn't approve
- **📚 Up next** — hold any poster (long-press / hold OK) for one menu: add to
  the queue, save to My list, or request an offline copy; queued videos play in
  order and clear themselves only when truly finished
- **🎧 Listen mode** *(only if a parent enables it)* — press the power button and
  the audio keeps playing with the screen off, for audiobooks and music at
  bedtime; minutes drain at a rate the parent chooses
- **📥 Downloads** — a row of videos saved for offline; the kid can *ask* for a
  video from its poster ("Waiting for approval…") and a parent approves on their
  phone, so car trips and dead Wi-Fi still work
- A fullscreen player with no ads and nothing to escape into; playlists
  auto-play in order, and in-video sponsor segments are skipped automatically
  (via SponsorBlock's community data)
- **NEW badges** when an allowed channel has fresh uploads
- **Time price tags** on tiles (0.5x … FREE, or 1.5x for "junk food" channels) —
  cheaper channels drain the clock slower, so kids can spend their time knowingly
- **Subtitles, audio language and quality** on demand — CC button on phones,
  ▼ on the TV remote opens the track controls; choices stick
- Gentle **"5 minutes left" / "1 minute left"** warnings before time runs out
- Friendly screens when time is up: "Time for a break! ⏰", "It's bedtime! 🌙"

Fully D-pad navigable on TV (colored focus glow, remote shortcuts: OK = pause,
◀ ▶ = seek ±10s, ▼ = subtitles, any key shows elapsed/remaining time).

## What the parent controls (all from their phone)

Open settings (fingerprint-gated, with a 4-digit parent PIN as fallback) on the phone:

- **Kids** — one profile per child: name, age, color, avatar, own screen-time
  rules (with "copy from sibling"), optional lock code. One kid changes nothing
  visibly; a second brings the who's-watching screen. Each device can be
  **dedicated to one kid** (their phone) or stay shared (the TV) — shared
  devices re-ask per sitting, never between episodes. Watch history, saved
  lists, NEW badges and stats are all per kid; the first kid a family creates
  inherits everything the device already knew.
- **Channels & playlists** — search YouTube by name and tap Add; or paste any
  channel/playlist link. Pick from the built-in **suggested channels directory**
  (community-curated, multilingual), import a whitelist from a file, and
  **export/share** your own list back out (save to file or share sheet) — or
  **submit it to the directory** so other families can find it. With multiple kids, one shared list carries
  **per-kid switches** on every channel — adding one asks "who is this for?",
  and the default is everyone. **Discover with AI** describes what you want in
  plain words ("fun science experiments for kids") and proposes channels, each
  verified against YouTube before it can be added.
  Each source has a **screen-time multiplier chip** — tap to cycle
  1x → 1.25x → 1.5x → 0.75x → 0.5x → 0.25x → FREE (long-press resets) — so
  educational channels can cost less (or nothing) and junk can cost extra.
- **Screen time** — session length, sessions per weekday/weekend, break length,
  and **blocked windows**: any number of named no-watching spans (bedtime,
  school hours, dinner), each with its own days of the week and a one-time
  **Skip tonight** pass for special occasions. The daily budget is
  `session × sessions`; only actual watching counts, and stopping early never
  forfeits time. An optional **listening rate** turns on kid-side listen mode
  (screen-off audio) at that drain multiplier — leave it unset and the feature
  doesn't exist on kid devices.
- **Grant extra time** — +15/+30/+60 today for a named kid, applied to every
  device instantly.
- **Pause for today** — one tap stops watching on every device until midnight.
- **AI content screening** *(optional, off by default)* — screen new videos against
  your own house rules ("no horror, no unboxing, no fake challenges") using any
  OpenAI-compatible endpoint: Anthropic, OpenRouter, or a local server. Verdicts are
  allow / block / **review** — unsure ones queue for you to rule on, and decisions
  sync to the kid's devices. With kid profiles, **one call returns a verdict per
  kid by age** ("fine for the 12-year-old, held for the 4-year-old"); in the
  queue, tap rules for everyone and hold picks kids. Only titles, channel names
  and durations are sent — never watch history — and each video is screened once
  per rules version, so the catalog isn't re-screened (or re-billed) on every
  launch. A connection test is built in.
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
- **📅 Weekly digest** — a once-a-week summary per kid: minutes watched each
  day, channels added or removed during the week, screening decisions still
  waiting on you, and an optional AI-written note.
- **Admin phones** — approve/deny new phones that ask to pair; revoke old ones.
- **App updates** — parent-triggered self-update from GitHub releases.

## Pairing (one minute, once)

1. Install the APK on the TV and on the parent's phone.
2. TV: settings → a QR code appears. (A kid's phone or tablet pairs the same
   way — every kid device shows a pairing QR in its settings.)
3. Phone: scan it with the camera → Pickwick opens → confirm.
4. The **first** phone becomes the admin automatically, but only while the TV is
   actually showing that QR — so nothing else on the network can quietly claim
   the slot. Any later phone needs approval on an existing admin phone (the QR
   itself grants nothing).

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
blocked     = named no-watching windows (bedtime, school…), per days of week,
              may cross midnight; each grants one parent-issued Skip pass
grants      = parent adds minutes today; clears breaks; waives bedtime briefly
multiplier  = per-channel drain rate (FREE, 0.25x–1.5x): scales budget & sitting
              use only — bedtime and breaks always apply, even on FREE channels
```

Only actual playback consumes time. An idle gap of a break-length starts a fresh
sitting with nothing lost. Everything resets at midnight. No rules set → no limits.

## Quality

Thumbnail resolution and playback quality adapt to the device and connection:
up to 1080p on a TV with fast Wi-Fi (video+audio streams merged in ExoPlayer),
degrading gracefully to lighter streams on weak links. Streams are fetched in
ranged chunks the way official clients do — defeating server-side throttling —
with a five-minute read-ahead buffer, so a Wi-Fi dip drains the buffer instead
of stalling playback.

## Installing

**New to sideloading? Follow the [step-by-step setup guide](docs/SETUP.md)** —
it walks through the Google TV and phone installs button-by-button, no
technical background assumed.

The short version: grab the APK from
[Releases](https://github.com/itcon-pty-au/pickwick/releases)
(or build it yourself, below). Phone/tablet — open the APK and install;
Google TV — install via the Downloader app (code **1037466**). After that, updates
come from inside the app (parent settings → Check for updates).

## Privacy & good-citizen notes

- No accounts, no analytics, no cloud: history, stats and settings live on your
  devices; phone↔TV traffic never leaves the LAN (token-authenticated).
- The one opt-in exception is **AI screening**, which is off unless you turn it
  on and supply your own endpoint/key. Even then it sends only video titles,
  channel names and durations — never watch history — and pointing it at a local
  server keeps everything in the house.
- **Sponsor skipping** queries SponsorBlock's public database using only a
  4-character hash prefix of the video ID, so the service can't tell which
  video is actually being watched.
- Plays streams directly rather than through YouTube's ad-supported player, which
  is against YouTube's Terms of Service — the same trade NewPipe users accept.
  For personal/family use; that's why it isn't in any app store.
- Occasionally YouTube changes internals and extraction breaks until the
  NewPipeExtractor dependency is updated — expect rare short outages.

## Contributing

The most valuable contributions aren't code: early breakage reports, bug reports
from TV models we've never seen, translations — and **channel suggestions**.
The in-app "Suggested channels" directory (also browsable at
[pickwick.tv/directory.html](https://pickwick.tv/directory.html)) is the
maintained place to add a channel your kid loves; sharing yours there is the
easiest way to help other families. The themed lists in
[`whitelists/`](whitelists/) still work too, importable as a `.txt` file.
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
gradlew assembleRelease
```

The APK lands at `app/build/outputs/apk/release/pickwick.apk` — sideload as
above. Release builds require a signing key: point `PICKWICK_KEYSTORE` (plus
`PICKWICK_KEYSTORE_PASSWORD`, `PICKWICK_KEY_ALIAS`, `PICKWICK_KEY_PASSWORD`) in
`local.properties` or the environment at your own keystore. Your build will
carry your signature, so it won't upgrade over an official install (and vice
versa) — Android requires an uninstall across a signature change.

**Always install the release build on real devices.** A debug build is
*debuggable*, which enables `-Xcheck:jni` and skips ahead-of-time compilation,
so the whole Compose runtime is interpreted on first launch. Measured cold start
to first frame on a Chromecast with Google TV:

| Build | Cold start |
| --- | --- |
| `assembleDebug` | 10.2 s |
| `assembleRelease` | ~1.9 s |
| `assembleRelease` + forced AOT (below) | ~1.5 s |

Use `assembleDebug` only when you need `run-as`, breakpoints or a debugger —
never for someone's TV.

Optionally squeeze out the last ~0.4 s by AOT-compiling the installed app. This
is a device-local setting that does **not** survive a reinstall, so re-run it
after each install:

```
adb shell cmd package compile -m speed -f io.pickwick.app
```

minSdk 26 · Kotlin + Jetpack Compose · Media3/ExoPlayer · NewPipeExtractor · GPL-3.0
· profile avatars from [Fluent Emoji](https://github.com/microsoft/fluentui-emoji)
(MIT — see [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md))

## Releasing updates (self-update)

The app checks `version.json` in this repo (parent settings → Check for updates):

1. Bump `versionCode`/`versionName` in `app/build.gradle.kts`, then
   `gradlew assembleRelease`. A device only offers an update when the manifest's
   `versionCode` is **higher** than the installed one, so forgetting the bump
   silently ships nothing.
2. Create a GitHub Release (tag `vX.Y.Z`) with the APK attached.
3. Point `version.json` at it:

```json
{ "versionCode": 2, "versionName": "0.2.0",
  "apkUrl": "https://github.com/<you>/pickwick/releases/download/v0.2.0/pickwick.apk" }
```

Updates must be signed with the same key as the installed build. Ship the
*release* APK — self-updating a family's TV onto a debug build would hand them
the 10-second cold start described above.

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

- [x] First public GitHub release (signed APK + self-update live)
- [x] Kid profiles: per-kid channels, history, screen time, AI verdicts,
      who's-watching screen with remote-code profile locks
- [x] Contribution scaffolding (CI canary, community whitelists, issue templates)
- [x] AI-assisted curation (channel discovery + rules-based screening)
- [x] Offline downloads with parent approval
- [x] Donations / sustainability
