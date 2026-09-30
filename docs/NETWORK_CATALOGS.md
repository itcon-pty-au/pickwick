# Network shares

Pickwick supports SMB2/3 shares from routers, computers and NAS devices. SMB1,
FTP, DLNA, WebDAV and NFS are not supported. No media server or transcoding
service is required. Playback uses the Android device's supported codecs.

## Setup

1. Enable SMB/Samba storage sharing on the server. For a TP-Link BE9700, consult
   the router's USB Storage Sharing page for its actual share name and account.
2. On the parent phone, open Settings > Content Sources > Network shares > Add network share.
3. Enter the server hostname/IP, or use Find servers. Port 445 is the default.
4. Choose guest access or enter the share username/password and optional domain.
5. Use Find shares, or enter the exact share name when enumeration is unavailable.
6. Choose Save connection. The catalog folder picker opens next; browse to a show
   folder and choose Save catalog. Set its children and time rate on its card.
7. Use Add catalog folder under the saved connection to add more collections without
   re-entering login details. Editing the connection updates every linked catalog.
8. On a child device, open Network shares, then the show, season and episode.

Existing catalogs are grouped automatically when parent settings opens. Only matching
server/share/login details are combined. Catalog IDs, folder paths, child access,
time rates, saved videos and history are retained. Removing a catalog keeps its
connection; removing a connection asks for confirmation and removes its catalogs
from Pickwick, never the files on the drive.

Example: share `Videos`, catalog folder `Kids/Pokemon`, with `Season 01` and
`Season 02` beneath it. Folder nesting is retained; episode numbers sort naturally.
Approving a catalog approves its current and future video files, including
subfolders. Network videos are parent-approved and do not use YouTube AI screening.

Every device connects directly to the share. Install this build on both the parent
phone and child devices before syncing network catalogs. The phone need not stay
awake for playback. Internet access is not required for SMB playback or time limits.

## Playback and availability

Content Sources > Content groups combines YouTube channels/playlists and network
catalogs under a per-child minutes-per-session cap. Unset means Unlimited; zero
blocks that group for the session. Usage counts real playing time, including
listening, regardless of a source's multiplier or FREE setting. Overlapping groups
all count, and the strictest cap wins. Favorites, Watch later and Up next retain
known source membership. Playlist membership is remembered when videos are fetched,
saved or played from the playlist; the app does not identify shows by title or scan
unvisited playlists for matching episodes.

Counters persist on the current device only. They reset with the existing sitting:
a full configured break, midnight, or a parent's fresh-sitting grant/skip. With
breaks disabled, reopening the app does not reset a group; it lasts until midnight
or a parent reset. Limits sync with configuration, but usage is not shared between
devices. Updating every playback device is necessary for enforcement.

- Uses the child's existing daily budget, session/break rules, blocked times,
  listening rules and parent pause. A catalog's time multiplier applies to all
  its folders. FREE bypasses the daily budget, not blocked times or breaks.
- Episodes in the current folder play in order. Resume and watched status use
  the existing per-child history and LAN history sync.
- Folder listings and thumbnails are stored locally in app storage. Recent listings
  reopen without network access for 15 minutes; older listings display immediately
  while refreshing. The Refresh button checks the drive immediately. Metadata and
  thumbnails are read incrementally on a network refresh; no full download is required.
- Catalogs and folders use channel-style cards, with cover art from cached videos
  in previously visited folders. Episodes share the online video-card layout.
- A disconnected drive retains cached listings and history. Retry reopens the
  current episode, preserving the saved playback position.
- Minimum-length rules hide videos until a sufficient duration is known.
- Network browsing has its own home entry. Long-press episodes to add them to
  Favorites, Watch later or Up next. Online channel search, Surprise and the download
  workflow do not index network catalogs.
- Discovery is a parent-triggered check of port 445 on up to 254 addresses on a
  local IPv4 subnet of /24 or smaller. Manual entry also works when discovery,
  share enumeration, guest networks or hostname lookup do not.
- A directory is limited to 10,000 entries. Filesystem links/reparse points are
  excluded. Removing a catalog never removes files from the server.

## Credentials and sync

SMB passwords are stored in Keystore-backed encrypted preferences with no
plaintext fallback, outside Android backup. They are removed from config.json.
Paired devices exchange configuration containing network credentials using an
RSA-wrapped AES-GCM envelope. Plain legacy config responses contain no SMB
passwords; encrypted push is required when catalogs or saved connections are present.

This protects the configuration contents from passive LAN observation. The
existing token-based HTTP pairing still assumes a trusted home LAN; this change
does not add authenticated TLS or protection against active key substitution.
After restoring Android backup, re-enter the share password or pull settings
from an updated paired device.

## Validation

Build and unit tests:

```powershell
.\gradlew.bat :app:testReleaseUnitTest :app:assembleRelease
```

With an Android emulator or test device attached, run
`.\gradlew.bat :app:connectedReleaseAndroidTest` for Android credential storage,
screen-time enforcement and offline folder-navigation checks. This uses the
signed release build. Run connected tests on a test device, not a family's
active player; the Android test runner installs and removes test packages.

Opt-in read-only integration tests use `PICKWICK_SMB_TEST_HOST`, optional
`PICKWICK_SMB_TEST_PORT` (445), `PICKWICK_SMB_TEST_SHARE` and
`PICKWICK_SMB_TEST_FILE` (relative to the share). Authenticated tests additionally
use `PICKWICK_SMB_TEST_USER` and `PICKWICK_SMB_TEST_PASSWORD`; never commit secrets.
Without these variables the integration tests are skipped.

For an isolated local fixture, install Impacket in a test environment and run
`app/src/test/fixtures/smb_server.py <fixture-directory>`; it serves only that
directory, read-only, on loopback port 1445. A physical router/NAS must also be
tested for its firmware's guest/authentication behavior, seeking, drive sleep,
disconnect/reconnect, and sustained playback on phone and TV.
