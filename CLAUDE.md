# Pickwick — working notes for Claude

A parent-curated YouTube front-end for Google TV and phones. Kotlin + Jetpack
Compose, Media3/ExoPlayer for playback, NewPipeExtractor for extraction. No
accounts, no cloud: a phone administers a TV over the LAN with a token-gated
HTTP server (`data/Pairing.kt`). Sideloaded only — never shipped to a store.

## Building and installing — read this before any install

**Always build and install `assembleRelease` on real devices.** This is a
performance requirement, not a preference.

```
gradlew assembleRelease
```

APK lands at `app/build/outputs/apk/release/pickwick.apk` (renamed in Gradle;
keep the asset name constant so `releases/latest/download/pickwick.apk` — the
Downloader-code URL — never goes stale). Release builds are signed with the
**real release keystore** — `PICKWICK_KEYSTORE` (plus `_PASSWORD`,
`PICKWICK_KEY_ALIAS`, `PICKWICK_KEY_PASSWORD`) in `local.properties` or the
environment. There is deliberately no debug-key fallback: release packaging
fails if the key is absent, because that key is the sole trust anchor for
self-update and Android refuses in-place upgrades across a signature change.

On this machine the keystore lives at
`~/.pickwick/pickwick-release.keystore` (alias `pickwick`, password alongside
in `pickwick-release.keystore.password.txt`). **Back both up off-machine** —
losing them means every installed family must uninstall, which wipes their
curation.

A debug build is *debuggable*, which enables `-Xcheck:jni` and skips
ahead-of-time compilation, leaving the Compose runtime interpreted on first
launch. Measured cold start to first frame on a Chromecast with Google TV:

| Build | Cold start |
| --- | --- |
| `assembleDebug` | 10.2 s |
| `assembleRelease` | ~1.9 s |
| `assembleRelease` + forced AOT | ~1.5 s |

Reach for `assembleDebug` only when something genuinely needs a debuggable
process — `run-as` to read `/data/data/...`, breakpoints, a debugger. Never
leave a debug build installed on a device someone actually uses; reinstall the
release build afterwards.

After installing, optionally AOT-compile to recover the last ~0.4 s. It is a
device-local setting that does **not** survive a reinstall, so re-run it every
time:

```
adb shell cmd package compile -m speed -f io.pickwick.app
```

If a change is supposed to make startup faster or slower, measure it rather than
asserting it. `-S` forces a genuine cold start; without it you will silently
measure a warm launch and get `TotalTime: 0`:

```
adb shell am start -S -W -n io.pickwick.app/.ui.MainActivity
```

Take three samples and read `TotalTime` (milliseconds to first frame). Watch
logcat for `Choreographer: Skipped N frames` and `Displayed ... +Xs` too.

## Releasing (self-update)

The app polls `version.json` at the repo root of `main` (URL is baked into
`BuildConfig.UPDATE_MANIFEST_URL`).

1. Bump **both** `versionCode` and `versionName` in `app/build.gradle.kts`. A
   device only offers an update when `versionCode` is strictly higher than the
   installed one — forgetting this silently ships nothing.
2. `gradlew assembleRelease`, attach the APK to a GitHub Release tagged `vX.Y.Z`.
3. Update `version.json` to point at that asset.

Always publish the release APK. Self-updating a family's TV onto a debug build
would hand them the 10-second cold start.

## Devices and adb

- `adb` is usually **not on PATH**; the SDK location is in `local.properties`
  (`platform-tools/adb.exe` under it).
- The Google TV connects via **wireless debugging over mDNS**, not `adb connect
  <ip>:5555` — that port is refused. It appears in `adb devices -l` on its own
  once Developer options → debugging is enabled on the TV. That toggle often
  resets after a reboot or system update.
- Transport ids from `adb devices -l` change between sessions. Re-read them; do
  not hardcode. The TV reports `model:Chromecast`, the phone `model:Pixel_7_Pro`.
- `install -r` preserves app data, so pairing and curation survive an upgrade.
  A signature mismatch means the installed build was signed with a different
  key (e.g. an old debug-keystore install from before the release key existed);
  that needs an uninstall, which **wipes the family's config**.

## Verifying pairing without breaking it

The TV's LAN server binds the first free port in 8765..8775. Check it is alive
with an unauthenticated read, and check an approved token with `/status`:

```
GET /pair-status?me=<32 hex>     -> {"status":"unknown"}
GET /status   (header X-Token: <approved token>)  -> {"hash":...,"updatedAt":...}
```

**Never send `POST /pair-request` while testing.** When the TV has no approved
phones yet and its pairing QR is on screen, the first requester is auto-approved
as the admin — a stray test request would seize that slot and force the real
phone into `pending`, where only an approved phone could rescue it.

That bootstrap is gated on `PairingWindow`, which the QR screen holds open and
which lapses ~15 s after that screen goes away; off-window requests get
`{"status":"closed"}`. `/pair-request` also refuses anything carrying an
`Origin` header or a non-JSON content type, so a page in a browser on the LAN
can't take the slot with a no-preflight cross-site POST.

## Conventions

- Comments explain constraints and *why*, not what the line does. Match the
  density already in the file; several non-obvious decisions are documented
  in-place and are worth preserving.
- Colors live in `ui/Theme.kt`. Watched/played progress is
  `WatchedProgressRed` (YouTube convention) — deliberately not the brand teal.
- `LaunchedEffect` and composable bodies run on the **main thread**. Disk I/O,
  SharedPreferences and JSON parsing must go through
  `withContext(Dispatchers.IO)`; `ConfigStore`/`PairingStore` calls are all
  synchronous. Long operations need visible progress, not a frozen dialog.
- `gradlew test` runs the JVM unit tests.
- The AI API key is **not** in `config.json`. It lives in `SecretStore`
  (Keystore-encrypted, unlisted in the backup rules so it never reaches cloud
  backup) and is overlaid onto `AiConfig` by `ConfigStore.load()`. It still
  travels to paired devices in the pushed payload — they need it to screen —
  and `saveRaw` strips it before the copy hits disk. Keep it out of the backup
  include lists, and keep `stripSecrets` surgical so unknown fields from newer
  builds survive the round trip.
- The `LanServer` faces the whole LAN before any token is checked, so every
  read there is bounded (line, header count, body, worker threads). Anything
  new that allocates from request data needs the same treatment.
