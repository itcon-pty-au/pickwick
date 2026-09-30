# Video Saver 0.4 (Brave desktop / Windows)

Auto-captures media requests in Pokeflix tabs. Downloads are started explicitly;
nothing is downloaded just by visiting a page. Use for media you may download.

## Use

1. Reload this extension at `brave://extensions`. Accept the new permissions if
   Brave asks. Keep site access enabled for media loaded from separate hosts.
2. Reload a Pokeflix episode and start playback. No Capture button is needed.
3. Open Video Saver and select Queue best audio + video. Automatic pairing prefers
   a captured `playlist.m3u8` or `master.m3u8` in the same episode folder and frame,
   even when you clicked an individual track. The popup updates as requests arrive.
4. The helper selects the highest-resolution video (frame rate and bitrate break
   ties) and best available audio exposed by that manifest, then merges without
   re-encoding. MKV preserves the original codecs. Already-combined files retain
   their container. The output is verified to contain both audio and video.
5. Finished files appear in `~/Downloads/Pickwick`. The popup shows progress,
   speed, ETA, errors, and the final path. Downloads continue while the popup is closed.
   HLS/DASH fragments download with up to eight workers; companion probes use up
   to four workers and omit duplicate playlist/MP4 representations before probing.
   These helper changes apply to new downloads, not an already running job.

## Shared queue

The Downloads list is shared across all tabs and saved locally across browser
restarts. Three jobs run concurrently by default; choose 1-4 in Parallel downloads.
Each job still uses up to eight fragment workers. Lower the setting if your network
or the source server becomes overloaded. Lowering it lets existing jobs finish
before enforcing the new limit; it does not terminate them.

Closing a source tab or popup does not stop downloads. Detected sources belong to
the current tab; queued jobs retain their own source snapshot. Duplicate active
requests for the same source/pair are not added twice. Finished and failed jobs
remain visible until removed or cleared; clearing history does not delete files.
Failed/interrupted jobs can be retried. Retry starts a new job, not a partial-file
resume. Queue history is capped at 200 entries.

Closing Brave or reloading the extension disconnects native jobs. On startup,
previously running jobs are marked Interrupted for manual retry; waiting jobs start
automatically. Let old-version downloads finish before upgrading: the old single
download status is not imported into the new queue.

If no captured master is available, the helper probes same-frame candidates from
the same episode directory, matches durations, and selects the highest-resolution
video plus a unique audio track. Playlist/MP4 duplicates of a track are deduplicated.
For ambiguous sources, use Companion feed to explicitly pair a video and audio
source. Explicit pairing uses those sources, not a different master. Different
durations are rejected. Directory/duration matching is a heuristic, so confirm the
saved episode contents. Audio language follows the master default unless manually
paired. Higher qualities absent from both the master and capture cannot be inferred.

## Install on another Windows machine

Install Python, FFmpeg (including ffprobe), and load this directory unpacked in
Brave. Note the extension ID, then run:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File native/install.ps1 -ExtensionId YOUR_EXTENSION_ID
```

The installer creates a local Python virtual environment with pinned yt-dlp,
registers `io.pickwick.video_saver` under the current user's Brave and Chrome-compatible
native messaging registry keys, and restricts it to that extension ID. No administrator privileges
or network listener. An optional `-OutputDirectory` selects a different location.
Keep this directory in place; the registered launcher points here.

To uninstall the helper, remove only the registry key
`HKCU\Software\BraveSoftware\Brave-Browser\NativeMessagingHosts\io.pickwick.video_saver`
and `HKCU\Software\Google\Chrome\NativeMessagingHosts\io.pickwick.video_saver`,
and remove this extension in Brave. Downloaded files remain untouched.

## Privacy and limits

- HTTP(S) host access is required because embedded players use other media hosts.
  Only tabs whose top-level hostname is `pokeflix.tv` or a subdomain are captured.
- Detected sources are held in session memory and cleared on navigation/tab closure.
  Queued requests (including signed URLs), status and output paths persist in local
  extension storage until removed. They are not synced. Full query strings are hidden.
- No cookies are exported, no request headers spoofed, no DRM/challenge bypass.
  Protected or browser-authenticated sources may fail. Live streams are rejected.
- Keep Brave running until downloads finish. Waiting jobs can be removed; running
  jobs have no cancel control yet. Failed files remain under `Downloads/Pickwick/.incomplete`.
- Highest quality means highest exposed by the chosen manifest, not upscaling.
  Device codec support still determines whether Pickwick can play the output.
- Local generated HLS tests verify master selection, explicit and automatic separate
  track merging, highest resolution, duplicate audio, and ambiguous-pair rejection.
  Brave-to-helper communication is verified. The supplied Pokeflix master was
  inspected without downloading the episode: yt-dlp selects 1080p plus English audio.
  Full Pokeflix episode downloading remains unverified.

## Tests

```powershell
node --test tools/video-saver/media.test.mjs tools/video-saver/background.test.mjs tools/video-saver/queue.test.mjs
```

From `native` after installation:

```powershell
.\.venv\Scripts\python.exe -m unittest -v test_host
```
