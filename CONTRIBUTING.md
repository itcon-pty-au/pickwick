# Contributing to Pickwick

Pickwick is a small, spare-time project used daily by real families. Contributions
are very welcome — and the most valuable ones are **not code**. In rough order of
usefulness:

## 1. Report extraction breakage early ⏰

When YouTube changes its internals, playback breaks for every family at once.
The sooner it's reported, the sooner a fix ships. A nightly CI canary
([extractor-smoke](.github/workflows/extractor-smoke.yml)) tests live extraction
and opens an issue automatically when it fails — but a human report from a
different network/region is still valuable early warning.

Use the **"Playback broken"** issue template. The two facts that matter most:

1. Does it persist after force-closing and reopening the app? (Transient
   throttling is retried automatically; real breakage survives restarts.)
2. Is there a fresh, active issue on the
   [NewPipeExtractor tracker](https://github.com/TeamNewPipe/NewPipeExtractor/issues)?
   If yes, link it — that usually contains the fix timeline.

## 2. Bug reports from your devices 📱📺

The Android/Google TV device matrix is huge and the maintainer only owns a few
devices. A clear report — device model, OS version, app version, steps — from
hardware we've never seen is worth more than most PRs. Use the **"Bug report"**
template. D-pad/remote focus issues on TV models are especially wanted.

## 3. Share a channel list 📋

Curating a good whitelist is the real work of using Pickwick, and it's the one
thing every parent here is an expert in. Community lists live in
[`whitelists/`](whitelists/) and can be imported straight into the app.

- **Via PR:** add a `whitelists/<theme>.txt` file — format documented in
  [`whitelists/README.md`](whitelists/README.md).
- **No git?** Open an issue with the **"Suggest a channel list"** template and
  a maintainer will add it.

Lists must be genuinely kid-appropriate; anything with even borderline content
will be declined.

## 4. Translations 🌍

App strings live in [`app/src/main/res/values/strings.xml`](app/src/main/res/values/strings.xml).
To add a language, contribute a translated copy under
`app/src/main/res/values-<lang>/strings.xml` (standard Android resource
qualifiers). Kid-facing strings (time-up screens, warnings) matter most.

## 5. Fix an extractor breakage yourself 🔧

The highest-impact code contribution is also the most mechanical one. When
extraction breaks, the fix is almost always a version bump — the README section
[**"When YouTube extraction breaks"**](README.md#when-youtube-extraction-breaks)
is a step-by-step runbook. A PR that bumps `newpipeextractor` in
[`gradle/libs.versions.toml`](gradle/libs.versions.toml) and pastes passing
`ExtractorSmokeTest` output is easy to merge same-day.

## 6. Code contributions 💻

PRs are welcome. To keep them mergeable:

- **Open an issue first for features.** Pickwick's core value is what it
  *doesn't* do; see the non-goals below before investing effort.
- **Keep PRs small and focused** — one fix or one feature.
- **Run the tests:** `gradlew :app:testDebugUnitTest`. The extractor smoke
  tests hit live YouTube and can be flaky on some networks; CI treats the
  compile + non-network tests as the PR gate.
- Match the existing code style (Kotlin + Compose, no new dependencies without
  discussion).

### Building

Open in Android Studio, or:

```
gradlew assembleDebug
```

JDK 17, Android SDK 34. Sideload `app/build/outputs/apk/debug/app-debug.apk`.

### Non-goals — PRs we will decline

These are load-bearing product decisions, not missing features:

- Anything that adds **discovery**: recommendations, related videos, trending,
  open search for kids, comments, Shorts.
- **Accounts, cloud sync, analytics, or any server component** — everything
  stays on the family's devices and LAN.
- Weakening or bypassing parental controls, even behind a setting.
- **App store distribution.** Playing streams directly is against YouTube's
  ToS; Pickwick is sideload-only by design and mass distribution work makes the
  project a bigger target.

## License

Pickwick is GPL-3.0 (required by its NewPipeExtractor dependency). By
contributing you agree your contribution is licensed under GPL-3.0.
