# Video Saver 0.5 — side panel preview

A locally processed video/audio downloader for Chrome/Brave desktop on Windows.
Click the toolbar icon to open the persistent side panel. It follows the active
tab within its browser window; the download queue is shared across all tabs.
Only explicitly queued media is downloaded.

## Installation and updates

1. Install Python and FFmpeg (with ffprobe beside it).
2. Extract the website ZIP into a permanent folder. In chrome://extensions or
   brave://extensions, enable Developer Mode and use Load unpacked on video-saver.
3. Copy the extension ID and run from the extracted folder:

   powershell -NoProfile -ExecutionPolicy Bypass -File native/install.ps1 -ExtensionId YOUR_EXTENSION_ID

4. Click the extension toolbar icon to open the panel.

For an existing checkout, finish running jobs, rerun the helper installer, and
reload the extension. Accept the sidePanel permission. The folder must stay in place. Installation currently
requires Python/FFmpeg; a signed standalone installer is not implemented.

Website distribution is a manual-install preview, not a one-click Chrome install.
Ordinary Chrome on Windows/macOS restricts externally installed extensions to the
Web Store; developer-mode unpacked loading requires user action. Updates must be
extracted into the same folder and the extension reloaded. Browser-specific support
outside Chrome should be tested before making installation promises.
Reference: https://developer.chrome.com/docs/extensions/how-to/distribute/install-extensions

## Downloading

Play media on any HTTP(S) website to detect direct video/audio and HLS/DASH sources.
Select Best video + audio, Audio (best available), or MP3. Detected audio files
automatically use audio mode if the selector is set to video. Alternatively paste
a single page or media URL; yt-dlp attempts to resolve supported pages.

Support is broad, not universal. Detection covers common video/audio extensions
and MIME types, but a detected URL is not a guarantee of a successful download.
DRM, browser-only authentication, expiring links, unusual players, and unsupported
codecs can fail. No cookies are exported, no authentication or DRM bypass is
implemented, and live streams/playlists of multiple videos are not supported.
Audio extraction chooses the best exposed audio; MP3 conversion uses 192 kbps.
Video merging normally preserves codecs in MKV; combined files retain their
container. There is no arbitrary output-format or resolution picker yet.

Captured master manifests are preferred for video. Fallback separate-track
pairing uses frame, directory and duration heuristics; you can explicitly select
a companion track. Check the result: heuristic matching cannot prove asset identity.
Saved audio is checked to contain audio without video; saved video is checked
for both streams. These checks do not establish content correctness.

## Queue and concurrency

Any positive safe-integer parallel-download setting is accepted, with no four-job
cap. Start with 3. Fragment connections are separately configurable from 1 to 16,
default 2. Twelve jobs with eight fragments each can use roughly 96 fragment
connections, plus probes and other requests. High settings can exhaust resources
or trigger server throttling; they do not guarantee higher throughput. No automatic
bandwidth controller or per-host rate limit is implemented yet.

Changes apply to newly started jobs; lowering concurrency allows existing jobs to finish. Jobs persist
across restarts; running jobs become Interrupted for explicit retry. Queued jobs
resume automatically. Retry starts from scratch, not from partial files.
The queue/history currently holds up to 200 entries; clear finished history to
make room. Removing history never deletes output files.

Closing the panel or source tab does not cancel a job. Keep the browser open.
Running-job cancel and pause/resume are not implemented. Incomplete files remain
in Downloads/Pickwick/.incomplete for diagnosis. An optional installer
-OutputDirectory changes the destination.

## Website package

From this folder:

native/.venv/Scripts/python.exe package.py --output dist/video-saver.zip

The explicit file allowlist excludes config.json, launch.cmd, host.json, virtual
environments and tests. Do not ZIP the entire working folder.
Review dependency licenses before bundling binaries; this preview installs its
dependencies instead of redistributing those binaries.

## Privacy and uninstall

All-site access detects media loaded by embedded players on different hosts.
Captured sources live in extension session storage and clear on navigation/tab
closure. Queued URLs (including signed query strings), titles, progress and output
paths remain in local extension storage until cleared; nothing is synced. Displayed
URLs omit query strings, but the source URLs remain sensitive stored data.
Downloading necessarily contacts the media origin; there is no analytics or other
network service in this preview.

Remove the extension and these current-user registry entries to unregister the helper:
HKCU/Software/BraveSoftware/Brave-Browser/NativeMessagingHosts/io.pickwick.video_saver
HKCU/Software/Google/Chrome/NativeMessagingHosts/io.pickwick.video_saver
Downloaded files remain.

## Verification

From the repository root:

node --test tools/video-saver/media.test.mjs tools/video-saver/background.test.mjs tools/video-saver/queue.test.mjs
tools/video-saver/native/.venv/Scripts/python.exe -m unittest discover -s tools/video-saver/native -v

The Python tests generate local HLS media and verify audio/video merging, audio
extraction, MP3 output, and page-URL extraction.
panel.test.mjs uses playwright-core and a locally installed Chrome executable to
exercise the real panel with a mocked Chrome extension API. It verifies rendering,
tab switching, queue controls, MP3 selection, narrow layout and hidden
query tokens. This is not a live extension/native-host browser integration test.
Broad real-site compatibility and manual side-panel installation still need beta testing.
