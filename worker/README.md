# Suggestion Worker — deploy guide

A stateless Cloudflare Worker with two routes, both fed by the website.

`POST /` is the receiving end of `site/suggest.html`. It gate-checks each
suggestion (honeypot → Turnstile → channel exists → not a duplicate → queue not
full) and opens a PR against the suggested language's file in `site/directory/`
— the first suggestion in a new language also adds that file and its
`index.json` listing in the same PR. Merging the PR is what publishes; the
Worker never touches `main`.

`POST /contact` takes the contact form. Same gate order (honeypot → shape →
Turnstile → queue not full); a **problem** becomes an issue labelled
`contact` + `bug`, an **idea** becomes a discussion in the Ideas category. The
queue cap counts only *open* items, so closing what you've dealt with is what
reopens the form.

`POST /submit-list` takes a family's whole whitelist from the Android app's
"Submit list to directory" button: `{ urls: [...], lang, website }`. There's
no Turnstile widget here — a sideloaded app has no browser to host one in —
so bill protection is bounds instead: at most 50 URLs, 300 characters each,
at most 6 PRs opened per request (the free plan's 50-subrequest ceiling —
the app chunks to match), and the same `MAX_OPEN_SUBMISSIONS` cap as the
single-suggestion route, re-read mid-batch so parallel requests can't
multiply it. Candidates are deduped within the batch, then each survivor is
probed and opened as its own PR, one at a time, so every channel still gets
its own AI screening verdict and human merge — nothing about this route
skips review. Responds
`{ status: "ok", submitted, duplicates, invalid, queueFull, errors }` —
`errors` is GitHub trouble, not the parent's links, and the app says so.

## One-time setup (~15 minutes)

1. **Cloudflare account** (free): <https://dash.cloudflare.com/sign-up>.

2. **Turnstile widget**: Dashboard → Turnstile → Add site.
   - Domain: `pickwick.tv`
   - Mode: Managed (shows the checkbox only when in doubt)
   - Note the **site key** (public) and **secret key**.

3. **GitHub token for the bot**: GitHub → Settings → Developer settings →
   Fine-grained tokens → Generate new token.
   - Repository access: **only** `itcon-pty-au/pickwick`
   - Permissions: **Contents: Read and write**, **Pull requests: Read and
     write**, **Issues: Read and write**, **Discussions: Read and write**
   - Expiration: 1 year is fine; set a calendar reminder — submissions fail
     silently-ish (form shows "something went wrong") when it expires.

   An existing token predating the contact form has only the first two. Add
   Issues and Discussions to it (or regenerate), then
   `npx wrangler secret put GITHUB_TOKEN` again. Without them `/contact`
   answers `error` on every submission — the queue check refuses to read a
   permissions failure as an empty queue.

4. **Deploy** (from this `worker/` directory):

   ```
   npx wrangler login
   npx wrangler secret put TURNSTILE_SECRET
   npx wrangler secret put GITHUB_TOKEN
   npx wrangler deploy
   ```

   `deploy` prints the worker URL, e.g.
   `https://pickwick-suggest.<your-subdomain>.workers.dev`.

5. **Connect the form**: in `site/suggest.html`, fill in the two constants at
   the top of the script block —

   ```js
   var WORKER_URL = 'https://pickwick-suggest.<your-subdomain>.workers.dev';
   var TURNSTILE_SITE_KEY = '<site key from step 2>';
   ```

   Commit and push; the "opening soon" notice disappears on its own.

## Operating notes

- **Cost**: free tier is 100k requests/day; the AI screening bill is capped by
  the workflow's daily limit plus `MAX_OPEN_SUBMISSIONS` here (queue full →
  polite 429 before anything reaches the API). `MAX_OPEN_CONTACT` (20) does the
  same for the contact form.
- **Reviewing**: each submission is a PR labeled by branch prefix
  `submission/…`. The screening workflow comments a verdict; **merge to
  publish**, close to reject. Both work fine from the GitHub mobile app.
- **Contact triage**: problems arrive as `contact`-labelled issues, ideas as
  Ideas discussions. Close them when handled — 20 open of either kind and the
  form starts answering "busy". Submitters are anonymous and unauthenticated,
  so there is no one to reply to in-thread; `@`-mentions inside a submitted
  message are deliberately defused before posting and won't notify anyone.
- **Changing routes**: `REPO_NODE_ID` and `IDEAS_CATEGORY_ID` in
  `wrangler.toml` are GraphQL node ids. If the repo moves or the Ideas category
  is recreated, re-read them (`gh api graphql` on `repository { id
  discussionCategories }`) and `npx wrangler deploy`.
- **Token expiry / rotation**: `npx wrangler secret put GITHUB_TOKEN` again;
  no redeploy needed.
- **Moving off Cloudflare**: the worker is a single plain HTTP handler with no
  platform-specific storage — porting to another host is an afternoon.
