# Setting up Pickwick, step by step

This guide assumes **no technical background**. It takes about 20 minutes:
ten for the TV, five for your phone, five to pair them and add channels.

> **Pickwick is in beta.** The setup below is the well-trodden path, but some
> newer features are still being confirmed across different phones, tablets and
> TVs — expect the occasional rough edge. Your channels and settings are stored
> on the devices themselves, not in the cloud, so keep in mind that uninstalling
> the app throws them away.

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

Settings opens on a short list of six pages — *Kids*, *Channels & playlists*,
*Content screening*, *Devices*, *Playback*, *Backup & app*. Tap one to open it,
**‹ Back** to return. **There is no Save button:** every change is kept as you
make it and sent to the TV a moment later.

---

## Part 4 — First-time setup on the phone

Do these in order. Each one is saved and sent to the TV on its own — there
is nothing to press at the end.

1. **Add channels** — **Channels & playlists**: search a channel your kid
   loves and tap **Add**. Repeat. (Or browse the in-app **Suggested
   channels** directory for ready-vetted picks, or import an exported
   `whitelist.txt` file — see the [whitelists folder](../whitelists/) for
   themed lists in that format.)
2. **Name your kid** — **Kids** already lists one child called *Kid*. Tap
   it to open their page and give it their real name, age, a color and an
   avatar. The age matters if you use AI screening later. Use **Add a kid**
   for each brother or sister. With **one** kid the TV goes straight to
   their channels; a **second** brings the "Who's watching?" screen.
3. **Their rules** — on the same page, under *Rules*: session length,
   sessions per weekday and weekend, break length, and optionally
   **hide videos shorter than** a few minutes (handy for cutting out
   clip-length uploads). Under *Blocked times*, add a **Bedtime** or
   **School hours** and adjust it. Nothing is set until you set it, so a
   kid with no rules can watch freely. **Copy rules from** a sibling saves
   typing.
4. **Optional — lock a profile**: the kid's page → *Profile lock* →
   **Set code**. The code is four presses of the remote's arrows/OK button,
   entered blind (only dots show on the TV) — so a younger sibling can't
   pick an older kid's profile.
5. **Optional — AI screening**: under **Content screening**, pick a
   provider, paste an API key, write your house rules in plain words. New
   videos are checked per kid's age before kids can see them, and the first
   time a video is pressed it gets a **deep check** of its description, tags
   and transcript before it plays. Anything the AI is unsure about waits for
   your OK under *Waiting for your OK*; anything it blocks is listed under
   *Blocked videos*, where Allow overrules it.

   Need different rules for one channel? Tap 📝 next to it under *Channels &
   playlists* and write them there ("only the engineering builds — no prank
   videos"). They apply to that channel only, on top of the family rules.

   Don't have a key yet? Pick one provider and get a key from it — you only
   need one:

   - **OpenRouter** (recommended — one key, works with lots of models,
     usually cheapest to start): go to
     [openrouter.ai/settings/keys](https://openrouter.ai/settings/keys),
     sign up, click **Create Key**, add a few dollars of credit, copy the
     key (starts with `sk-or-`).
   - **OpenAI**: go to
     [platform.openai.com/api-keys](https://platform.openai.com/api-keys),
     sign up, click **Create new secret key**, copy it (starts with `sk-`).
     You'll need billing set up under *Settings → Billing*.
   - **Anthropic**: go to
     [console.anthropic.com/settings/keys](https://console.anthropic.com/settings/keys),
     sign up, click **Create Key**, copy it (starts with `sk-ant-`). Add
     credit under *Plans & Billing*.
   - **Gemini**: go to
     [aistudio.google.com/apikey](https://aistudio.google.com/apikey), sign
     in with a Google account, click **Create API key**, copy it. Gemini has
     a free tier, so this is the only option that may cost nothing.

   Whichever you pick, paste the key into the **API key** field in Pickwick
   right after tapping that provider's button — the key never leaves your
   phone and TV except to talk directly to that provider.
6. Press **Done** when you've finished. Everything is already saved; this
   just closes settings.

**Check it worked:** both screens show the same settings fingerprint
(`Settings #a1b2c3d4`) — TV: ⚙ screen; phone: **Devices** → *Kid devices*.
Matching numbers = provably in sync. If the TV was asleep or off, it catches
up by itself the next time it's reachable.

---

## Everyday things

- **Give bonus time:** ⚙ → **Kids** → the kid → *Today* → **Grant**.
- **Stop one kid today:** ⚙ → **Kids** → the kid → *Today* →
  **Pause for today** (Resume undoes it). Their brothers and sisters are
  unaffected.
- **Stop everyone today:** ⚙ → **Pause everyone**, at the bottom of the
  first settings screen.
- **See what's playing / today's minutes:** ⚙ → **Devices** → *Kid devices*
  → **Stats** — works even while the TV is off (it shows the last report).
- **Approve a download** the kid requested: ⚙ → **Devices** →
  *Offline downloads*.
- **Updates:** ⚙ → **Backup & app** → **Check for updates** — installs new
  releases from inside the app; no more sideloading after the first time.

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
