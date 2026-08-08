# Suggestion Worker — deploy guide

The receiving end of `site/suggest.html`. A stateless Cloudflare Worker: it
gate-checks each suggestion (honeypot → Turnstile → channel exists → not a
duplicate → queue not full) and opens a PR against the suggested language's
file in `site/directory/` — the first suggestion in a new language also adds
that file and its `index.json` listing in the same PR. Merging the PR is what
publishes; the Worker never touches `main`.

## One-time setup (~15 minutes)

1. **Cloudflare account** (free): <https://dash.cloudflare.com/sign-up>.

2. **Turnstile widget**: Dashboard → Turnstile → Add site.
   - Domain: `pickwick.tv`
   - Mode: Managed (shows the checkbox only when in doubt)
   - Note the **site key** (public) and **secret key**.

3. **GitHub token for the bot**: GitHub → Settings → Developer settings →
   Fine-grained tokens → Generate new token.
   - Repository access: **only** `itcon-pty-au/pickwick`
   - Permissions: **Contents: Read and write**, **Pull requests: Read and write**
   - Expiration: 1 year is fine; set a calendar reminder — submissions fail
     silently-ish (form shows "something went wrong") when it expires.

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
  polite 429 before anything reaches the API).
- **Reviewing**: each submission is a PR labeled by branch prefix
  `submission/…`. The screening workflow comments a verdict; **merge to
  publish**, close to reject. Both work fine from the GitHub mobile app.
- **Token expiry / rotation**: `npx wrangler secret put GITHUB_TOKEN` again;
  no redeploy needed.
- **Moving off Cloudflare**: the worker is a single plain HTTP handler with no
  platform-specific storage — porting to another host is an afternoon.
