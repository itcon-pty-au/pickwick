# Setting up Pickwick, step by step

This guide assumes **no technical background**. It takes about 20 minutes:
ten for the TV, five for your phone, five to pair them and add channels.

Pickwick isn't in any app store (see the README for why), so you install it
by "sideloading" — which just means installing an app from a file instead of
from a store. Millions of people do this; the only catch is that Android makes
you flip a permission switch first.

**What you need:**

- Your Google TV / Android TV device (Chromecast with Google TV, or a TV
  running Google TV)
- The parent's Android phone
- Both on the same home Wi-Fi

---

## Part 1 — Install Pickwick on the TV

You'll use the free **Downloader** app — no computer needed.

First, two one-time preparations — doing them now means the install later
runs straight through without security interruptions:

1. On the TV, open the **Play Store** and search for **Downloader by
   AFTVnews** (orange icon). Install it.
2. Flip the permission switch for it: go to **Settings → Apps → Security &
   Restrictions → Unknown sources** (on some TVs: **Settings → Privacy →
   Security & Restrictions**), find **Downloader** in the list and turn it
   **on**. This tells the TV "apps this app downloads are allowed to
   install" — it's the switch every sideload needs, and it only appears in
   the list *after* Downloader is installed, which is why it's step 2.

Now the install itself:

3. Open Downloader. The first time, it asks for permission to access
   files — allow it.
4. In Downloader's URL box, type the Pickwick code:

   ```
   1037466
   ```

   The download starts straight away and asks to install.
5. Press **Install**. (If instead the TV says *"your TV is not allowed to
   install unknown apps from this source"*, step 2 was missed — press
   **Settings** right on that message, turn Downloader **on**, go back, and
   the install screen returns.)
6. Done — Pickwick appears in your TV's app row. You can uninstall
   Downloader now if you like; Pickwick updates itself from inside the app
   from here on.

If the code ever doesn't work, the newest release is always at
`github.com/itcon-pty-au/pickwick/releases/latest/download/pickwick.apk` —
typing that full address in Downloader does the same thing.

---

## Part 2 — Install Pickwick on the parent's phone

You'll see two security prompts along the way — both are the standard
Android ritual for *any* app installed outside a store, not something wrong.

1. On the phone, open this address in Chrome — the download starts
   immediately:
   **github.com/itcon-pty-au/pickwick/releases/latest/download/pickwick.apk**
2. Chrome warns the file "might be harmful" (prompt one — it says this for
   every APK): tap **Download anyway**.
3. Open the downloaded file (notification shade, or Files → Downloads).
4. Android asks to allow Chrome (or Files) to install unknown apps (prompt
   two): allow it, then press **Install**.
5. Open Pickwick. It looks empty — that's right, nothing is allowed yet.

If a kid has their own phone or tablet, install Pickwick on it the same way.

---

## Part 3 — Pair the phone with the TV

1. On the **TV**: open Pickwick and press the **⚙ settings** icon (top
   right). A **QR code** appears.
2. On the **phone**: open the normal camera app and point it at the QR
   code. Tap the link that pops up — Pickwick opens and asks
   *"Pair with …?"* — confirm.
3. That's it. The **first phone to pair becomes the admin** automatically.
   (Any phone that scans later needs your approval on the first phone, so a
   visitor photographing your TV gains nothing.)

Everything from here is done on the phone, in **Pickwick → ⚙ →** (it asks
for your fingerprint, or a parent PIN you set on first use).

---

## Part 4 — First-time setup on the phone

Do these in order; each pushes to the TV automatically when you press
**Save & close**.

1. **Add channels** — under *Channels & playlists*, search a channel your
   kid loves and tap **Add**. Repeat. (Or import a ready-made list from the
   [whitelists folder](../whitelists/).)
2. **Add your kids** — under *Kids*, tap **Add your first kid**: name, age,
   a color and an avatar. The age matters if you use AI screening later.
   With **one** kid the app looks unchanged; adding a **second** kid brings
   the "Who's watching?" screen on the TV.
3. **Screen time** — pick a kid, set session length, sessions per
   weekday/weekend, and bedtime. **Copy rules from** a sibling saves typing.
4. **Optional — lock a profile**: Kids → Edit → **Set code**. The code is
   four presses of the remote's arrows/OK button, entered blind (only dots
   show on the TV) — so a younger sibling can't pick an older kid's profile.
5. **Optional — AI screening**: under *AI content screening*, pick a
   provider, paste an API key, write your house rules in plain words. New
   videos are checked per kid's age before kids can see them; anything the
   AI is unsure about waits for your OK under *Waiting for your OK*.
6. Press **Save & close** — the phone pushes everything to the TV and shows
   "Synced ✓".

**Check it worked:** both screens show the same settings fingerprint
(`Settings #a1b2c3d4`) — TV: ⚙ screen; phone: under *Kid devices*. Matching
numbers = provably in sync.

---

## Everyday things

- **Give bonus time:** ⚙ → *Screen time today* → pick the kid → **Grant**.
- **Stop everything today:** ⚙ → **Pause for today** (Resume undoes it).
- **See what's playing / today's minutes:** ⚙ → *Kid devices* → **Stats** —
  works even while the TV is off (it shows the last report).
- **Approve a download** the kid requested: ⚙ → *Offline downloads*.
- **Updates:** ⚙ → *App* → **Check for updates** — installs new releases
  from inside the app; no more sideloading after the first time.

## If something doesn't work

- **TV shows "offline" on the phone** — open Pickwick on the TV (the
  pairing service runs while the app is open) and check both devices are on
  the same Wi-Fi, then try again.
- **Channels look thin right after changing AI rules or kids' ages** — the
  catalog is being re-checked against the new rules; tiles reappear as the
  AI clears them (minutes, not hours).
- **A kid's videos stopped resolving app-wide** — usually a YouTube-side
  change; check the
  [open issues](https://github.com/itcon-pty-au/pickwick/issues) — a fix
  release typically follows within days, delivered via Check for updates.
