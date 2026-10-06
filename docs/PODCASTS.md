# Podcasts

Pickwick plays podcasts from their public RSS feeds. A parent adds a feed, and
it appears on the kids' home screens next to their YouTube channels. Podcasts are
audio-only, so they sit outside screen-time rules entirely.

Requires Pickwick 0.10.0 or later on every device: the parent phone and each
kid device. An older kid device keeps the podcast list when settings sync but
doesn't show it. Update every parent phone first: an older parent phone saves
settings without the podcast list, which removes podcasts from the family's
devices.

## Adding a podcast

1. On the parent phone, open **Settings > Content Sources > Podcasts**.
2. Tap **Add RSS podcast** and paste the podcast's **RSS feed address** — it
   starts with `http://` or `https://` and usually ends in `.xml`, `/rss` or
   `/feed`.
3. Tap **Check feed**. Pickwick downloads the feed, shows how many episodes it
   found and fills in the show's name. Change the name if you like; it's what
   the kids see on the tile.
4. Tap **Save**. The podcast reaches the kids' devices with the next settings
   sync.

With two or more kids, each podcast has per-kid switches, the same as a YouTube
channel. A podcast with no kid selected is visible to everyone. Use the pencil
to change a podcast's address or name, and the bin to remove it.

### Finding the RSS address

The RSS address is the podcast's own feed, not the page on a podcast app. Most
shows link it on their website as "RSS" or "Subscribe via RSS". Podcast
directories such as Podcast Index and many podcast apps show it in the show's
details. An Apple Podcasts or Spotify page link won't work; Pickwick needs the
feed itself.

## What kids see

- Each podcast gets its own tile on the home screen, marked with the podcast
  icon in the top-left corner. On a TV, podcasts have their own **Podcasts** row.
- Opening a podcast shows its episodes newest first, in the same grid as a
  YouTube channel. Square cover art is shown whole rather than cropped.
- Tapping an episode opens the podcast player: cover art, title, a progress
  bar, back 15 s / forward 30 s, and previous/next episode. Episodes resume
  where they were left, and a red bar on the episode shows how far in it is.
- On phones, the episode keeps playing with the screen off or another app in
  front, with a pause button on the lock screen. On a TV, leaving the player
  pauses it.
- The podcast player doesn't move on to the next episode by itself. Feeds list
  the newest episode first, so "next" would mean an older one.

### Favorites, Watch later and Up next

Hold an episode (long-press, or hold OK on the remote) for the same menu as a
YouTube video: **Add to Up next**, **Add to Favorites**, **Add to Watch later**
and **Mark as watched**. Downloads aren't available for podcasts.

Up next can mix podcast episodes and YouTube or network videos in one queue.
The whole queue plays in one player, so it carries on through the change from
video to podcast and back, including with the phone screen off. Each video in
the queue keeps all of its screen-time rules; each podcast episode keeps none.
If listening is turned off for your family and the queue reaches a video while
the screen is off, the video pauses rather than playing unseen.

## Screen time

Podcasts don't count toward screen time and aren't stopped by it:

- No daily budget or session minutes are used, and they don't trigger time
  warnings.
- Breaks, blocked times such as **Bedtime**, and **Pause everyone** don't stop
  them.
- Content groups don't include them, and the family listening rate doesn't
  apply.
- Listening time isn't added to the per-channel statistics. The episode does
  show as now playing on the parent's statistics page.

To keep a podcast away from a child — at bedtime or otherwise — turn off their
switch on that podcast, or remove the podcast.

## Screening and what you're approving

Adding a podcast approves the whole feed, including every future episode.
Episodes don't go through AI screening; that covers YouTube only.

Many podcast hosts insert ads into the audio when it's downloaded, different
for each listener. Pickwick can't see or screen those, and doesn't skip them.
Review a show's ads as well as its episodes before adding it.

## Feeds, caching and the network

- Pickwick keeps each feed on the device. Opening a podcast shows the saved
  episode list straight away and re-checks the feed if it's more than 30 minutes
  old, so episode lists still open without internet. Playing an episode needs
  internet: audio streams from the podcast's host.
- Feeds are limited to 12 MB, and only the first 2,000 episodes listed (usually
  the newest) are kept. Pickwick never loads external files or entities that a
  feed's XML points to.
- Progress and watched status are per kid and sync between paired devices like
  video history. Up next stays on the device.
- Each device fetches feeds and audio directly from the podcast's host. The host
  sees the device's IP address, and many route downloads through analytics
  services (such as Podtrac) that count listens.

## Troubleshooting

- **"Couldn't read a podcast feed at that address"** or **"This address isn't a
  podcast feed".** The address is a web page, not a feed. Find the RSS address
  as described above.
- **"The feed answered 404"** (or another number). The host refused the
  request. Check the address in a browser on the phone.
- **A podcast's tile shows no artwork.** That device hasn't fetched the feed
  yet. It fetches in the background on first view; opening the podcast also
  fetches it.
- **An episode won't play.** The host may have removed or moved it, or the device
  doesn't support its audio format. Try another episode. The episode list picks
  up the host's changes when the podcast is opened and its saved copy is more
  than 30 minutes old.
- **A podcast is missing on one device.** Update that device to 0.10.0 or later
  and sync settings.
