# Content Groups

Content groups limit how much of a child's session can be spent on a particular
collection. They can combine YouTube channels/playlists and network catalogs.
They do not replace the child's overall session, daily budget or blocked times.
Podcasts can't be added to a group: they sit outside screen time entirely (see
[Podcasts](PODCASTS.md)).

## Setup

1. On the parent phone, add the YouTube sources or network catalogs under
   **Settings > Content Sources**.
2. Open **Content groups**, add a group and give it a name, such as Pokemon.
3. Select the YouTube channels/playlists and network catalogs belonging to it.
4. Set the minutes allowed per session for each child. Unlimited leaves that
   child's group uncapped; zero blocks the group.
5. Choose Save. The group settings sync to paired devices.

For example, a 30-minute session with a 10-minute Pokemon group allows up to
10 minutes across the selected Pokemon sources on that device. Once the group
allowance is used, the child can choose other content if their overall rules
still allow watching. Switching episodes does not restart the allowance.

## How Time Counts

- Only actual playback counts, including listening. Pausing and buffering do not.
- Group minutes are real minutes, independent of source time multipliers. FREE
  sources still count toward a group cap.
- A video in several groups counts toward all of them; the strictest remaining
  allowance applies.
- Favorites, Watch later and Up next retain known source membership, so saved
  videos do not bypass the cap.
- Group time remaining appears only with player controls. A warning appears near
  the limit, and playback stops when the allowance is exhausted.

## Sessions and Devices

Counters persist when the app is closed or reopened. They reset with the existing
sitting: after a full configured break, at midnight, or with a parent's
fresh-sitting grant/skip. When breaks are disabled, reopening the app does not
reset the counter; it lasts until midnight or a parent reset.

Group definitions and limits sync to paired devices, but **usage counters do not**.
Each device enforces its own allowance. Update all playback devices to v0.9.0 or
later before relying on content-group enforcement.

## Membership Is Explicit

Groups contain sources, not automatically recognized shows. A network catalog
includes videos in its subfolders. YouTube playlist membership is remembered
when videos are fetched, saved or played from that playlist; Pickwick does not
scan unvisited playlists or identify matching episodes by their titles.

For folder setup, see [Network shares](NETWORK_CATALOGS.md).
