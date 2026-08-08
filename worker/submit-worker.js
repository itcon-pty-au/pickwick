/**
 * Pickwick mail-slot for the website — stateless, two routes, not a backend.
 *
 * POST /         channel suggestions from site/suggest.html
 *   { url, ages[], topics[], note, lang, website, turnstile }
 *   Gates, in cost order (everything before the PR is free):
 *     1. honeypot ("website" filled → pretend success, tell the bot nothing)
 *     2. Turnstile verification
 *     3. URL shape + channel/playlist existence on YouTube
 *     4. dedup against the published directory and open submission PRs
 *     5. open-queue cap (bill protection for the AI screening Action)
 *   Survivors become a PR against site/directory/<lang>.json — created along
 *   with its index.json listing when it's the first suggestion in that
 *   language. The AI screening workflow comments a verdict and a human merges.
 *
 * POST /contact  problem reports and ideas from the contact form
 *   { kind: "problem"|"idea", message, website, turnstile }
 *   Same cost order: honeypot → shape → Turnstile → open-queue cap → create.
 *   A problem becomes a labelled issue, an idea a discussion in Ideas.
 *
 * POST /submit-list  a family's whole whitelist, from the Android app's
 *   "Submit list to directory" button
 *   { urls: [...], lang, website }
 *   No Turnstile here — the sideloaded app has no browser to host the widget
 *   in. Bill protection instead comes from strict bounds on the batch (≤50
 *   URLs, ≤300 chars each) plus the same MAX_OPEN_SUBMISSIONS cap the single
 *   suggestion route uses, and every survivor still goes through AI screening
 *   and a human merge same as any other suggestion — nothing here skips that.
 *   Candidates are deduped within the batch, then processed one at a time so
 *   each channel gets its own PR (and its own AI verdict), same shape as a
 *   parent submitting one-by-one through the website form.
 *
 * This worker never writes to main directly.
 *
 * Secrets:  TURNSTILE_SECRET, GITHUB_TOKEN (fine-grained: Contents RW +
 *           Pull requests RW + Issues RW + Discussions RW)
 * Vars:     see wrangler.toml
 */

const KNOWN_AGES = ['2-4', '5-7', '8-10', '11+'];

export default {
  async fetch(request, env) {
    const cors = corsHeaders(request, env);
    if (request.method === 'OPTIONS') return new Response(null, { status: 204, headers: cors });
    if (request.method !== 'POST') return json({ status: 'error' }, 405, cors);

    // Same convention as the app's LAN server: bound every read from the
    // network before parsing it. The biggest legitimate body (50 URLs at 300
    // chars) is under 16 KB.
    if (Number(request.headers.get('Content-Length') || 0) > 32768) {
      return json({ status: 'error' }, 413, cors);
    }
    let body;
    try {
      body = await request.json();
    } catch {
      return json({ status: 'error' }, 400, cors);
    }

    // Only /contact is carved out; everything else stays the suggestion route
    // the deployed form has always POSTed to at "/".
    const path = new URL(request.url).pathname.replace(/\/+$/, '');
    if (path === '/contact') return handleContact(body, request, env, cors);
    if (path === '/submit-list') return handleSubmitList(body, env, cors);
    return handleSuggestion(body, request, env, cors);
  },
};

async function handleSuggestion(body, request, env, cors) {
  // Honeypot: report success so the bot has nothing to learn from.
  if (body.website) return json({ status: 'ok' }, 200, cors);

  const note = String(body.note || '').trim().slice(0, 140);
  const ages = (Array.isArray(body.ages) ? body.ages : []).filter((a) => KNOWN_AGES.includes(a));
  const topics = (Array.isArray(body.topics) ? body.topics : [])
    .map((t) => String(t).slice(0, 40)).slice(0, 3);
  // Ages, topics and note may all be empty — the screening workflow fills
  // blanks with AI values on the PR, where the reviewer sees them pre-merge.

  const lang = normalizeLang(body.lang);
  if (!lang) return json({ status: 'invalid', message: 'Pick the channel’s language.' }, 422, cors);

  if (!(await verifyTurnstile(body.turnstile, request, env))) {
    return json({ status: 'error', message: 'Human check failed.' }, 403, cors);
  }

  const channel = parseSuggestionUrl(String(body.url || ''));
  if (!channel) {
    return json({ status: 'invalid', message: 'Not a recognizable YouTube channel or playlist link.' }, 422, cors);
  }

  const probe = channel.kind === 'playlist'
    ? await probePlaylist(channel)
    : await probeChannel(channel);
  if (probe.status === 'missing') {
    return json({ status: 'invalid', message: `YouTube says that ${channel.kind} doesn’t exist.` }, 422, cors);
  }

  const gh = github(env);
  const dir = env.DIRECTORY_DIR || 'site/directory';
  let index, openPrs;
  try {
    [index, openPrs] = await Promise.all([gh.getFile(`${dir}/index.json`), gh.openSubmissionPrs()]);
  } catch (e) {
    console.error('directory fetch failed', e);
    return json({ status: 'error' }, 500, cors);
  }
  if (!index) return json({ status: 'error' }, 500, cors);
  if (openPrs.length >= Number(env.MAX_OPEN_SUBMISSIONS || 25)) {
    return json({ status: 'busy' }, 429, cors);
  }

  // Dedup against every published language, not just the target — the same
  // channel filed under two languages is still one channel.
  const languages = (index.json.languages || []).filter((l) => l && l.file);
  const files = await Promise.all(languages.map((l) => gh.getFile(`${dir}/${l.file}`)));
  const allEntries = files.filter(Boolean).flatMap((f) => f.json.entries || []);
  if (isDuplicate(channel, probe, allEntries, openPrs)) {
    return json({ status: 'duplicate' }, 200, cors);
  }

  const entry = {
    url: channel.url,
    name: probe.title || channel.display,
    kind: channel.kind,
    // The canonical UC id when the probe resolved one — dedup's other half,
    // so a later "@handle vs channel/UC…" submission of the same channel
    // matches no matter which form this entry was stored in.
    ...(probe.channelId ? { channelId: probe.channelId } : {}),
    ages,
    topics,
    note,
    added: new Date().toISOString().slice(0, 10),
  };

  const existing = languages.findIndex((l) => l.code === lang.code);
  const target = {
    path: `${dir}/${existing !== -1 ? languages[existing].file : `${lang.code}.json`}`,
    file: existing !== -1 ? files[existing] : null, // null → first entry in a new language
    index: existing !== -1 ? null : index, // index.json only changes for a new language
  };

  try {
    const prUrl = await gh.openPr(target, entry, probe, lang);
    return json({ status: 'ok', pr: prUrl }, 200, cors);
  } catch (e) {
    console.error('PR creation failed', e);
    return json({ status: 'error' }, 500, cors);
  }
}

/**
 * Batch version of handleSuggestion for the app's "Submit list to directory"
 * button. Same duplicate/probe/queue-cap gates as the single-suggestion
 * route, just run once per candidate instead of once per request — see the
 * file header for why there's no Turnstile here.
 */
async function handleSubmitList(body, env, cors) {
  // Honeypot: report success so a scripted caller learns nothing.
  if (body.website) return json({ status: 'ok' }, 200, cors);

  if (!Array.isArray(body.urls) || body.urls.length > 50) {
    return json({ status: 'invalid', message: 'Expected a list of up to 50 URLs.' }, 422, cors);
  }
  if (body.urls.some((u) => typeof u !== 'string' || u.length > 300)) {
    return json({ status: 'invalid', message: 'Every entry must be a short URL string.' }, 422, cors);
  }
  const lang = normalizeLang(body.lang);
  if (!lang) return json({ status: 'invalid', message: 'Pick the list’s language.' }, 422, cors);

  // Parse + dedup within the batch by .key before anything hits the network.
  // Count the drops — the app echoes these numbers back to the parent, and
  // they should add up to the size of the list that was sent.
  const seenKeys = new Set();
  const candidates = [];
  let invalid = 0, duplicates = 0;
  for (const raw of body.urls) {
    const channel = parseSuggestionUrl(raw);
    if (!channel) { invalid++; continue; }
    if (seenKeys.has(channel.key)) { duplicates++; continue; }
    seenKeys.add(channel.key);
    candidates.push(channel);
  }

  // Nothing left after local parsing → answer without spending a single
  // GitHub call. This route has no Turnstile, so every request that reaches
  // the API costs shared PAT rate limit an anonymous caller shouldn't be
  // able to drain for free.
  if (candidates.length === 0) {
    return json({ status: 'ok', submitted: 0, duplicates, invalid, queueFull: 0, errors: 0 }, 200, cors);
  }

  const gh = github(env);
  const dir = env.DIRECTORY_DIR || 'site/directory';
  const maxOpen = Number(env.MAX_OPEN_SUBMISSIONS || 25);

  // Cheapest GitHub read first, and alone: when the queue is already full
  // there is no reason to also fetch every language file.
  let openPrs;
  try {
    openPrs = await gh.openSubmissionPrs();
  } catch (e) {
    console.error('open PR fetch failed', e);
    return json({ status: 'error' }, 500, cors);
  }
  if (openPrs.length >= maxOpen) {
    return json({ status: 'ok', submitted: 0, duplicates, invalid, queueFull: candidates.length, errors: 0 }, 200, cors);
  }

  let index;
  try {
    index = await gh.getFile(`${dir}/index.json`);
  } catch (e) {
    console.error('directory fetch failed', e);
    return json({ status: 'error' }, 500, cors);
  }
  if (!index) return json({ status: 'error' }, 500, cors);

  const languages = (index.json.languages || []).filter((l) => l && l.file);
  const files = await Promise.all(languages.map((l) => gh.getFile(`${dir}/${l.file}`)));
  const allEntries = files.filter(Boolean).flatMap((f) => f.json.entries || []);

  // Bodies of PRs opened earlier in *this* request join openPrs' dedup pool,
  // so entry 2 of the batch won't re-submit what entry 1 just opened.
  const openedThisRequest = [];
  // The free Workers plan allows 50 subrequests per request and each accepted
  // channel costs ~6 (probe + branch + content + PR); past this budget the
  // fetches would start throwing mid-PR. The app chunks its batches to fit,
  // and resubmission is idempotent, so the honest answer for the overflow is
  // "queue is busy, try again".
  const maxPrsThisRequest = 6;
  // The cap was read once at request start; concurrent requests would each
  // see the same near-empty queue and multiply it. Re-reading it after every
  // few PRs shrinks that window to a handful instead of a whole batch.
  let openCount = openPrs.length;
  let submitted = 0, queueFull = 0, errors = 0;

  for (const channel of candidates) {
    if (submitted >= maxPrsThisRequest) {
      queueFull++;
      continue;
    }
    if (submitted > 0 && submitted % 3 === 0 && openCount < maxOpen) {
      try {
        openCount = (await gh.openSubmissionPrs()).length;
      } catch { /* keep the local count; the next PR attempt surfaces real trouble */ }
    }
    if (openCount >= maxOpen) {
      queueFull++;
      continue;
    }
    if (isDuplicate(channel, {}, allEntries, [...openPrs, ...openedThisRequest])) {
      duplicates++;
      continue;
    }
    const probe = channel.kind === 'playlist' ? await probePlaylist(channel) : await probeChannel(channel);
    if (probe.status === 'missing') {
      invalid++;
      continue;
    }
    // Re-check after the probe too — dedup keyed on channel ids the probe
    // just resolved can catch what the URL-only check above couldn't.
    if (isDuplicate(channel, probe, allEntries, [...openPrs, ...openedThisRequest])) {
      duplicates++;
      continue;
    }

    const entry = {
      url: channel.url,
      name: probe.title || channel.display,
      kind: channel.kind,
      ...(probe.channelId ? { channelId: probe.channelId } : {}),
      ages: [],
      topics: [],
      note: '',
      added: new Date().toISOString().slice(0, 10),
    };

    const existing = languages.findIndex((l) => l.code === lang.code);
    // Caveat: within one request, a second entry for a brand-new language
    // still branches off main and won't see the first entry's just-created
    // file/PR — same as two independent form submissions arriving close
    // together today. isDuplicate against openedThisRequest keeps the two
    // PRs' entries distinct; a human resolves any merge conflict on review,
    // same as always. Not worth special-casing further.
    const target = {
      path: `${dir}/${existing !== -1 ? languages[existing].file : `${lang.code}.json`}`,
      file: existing !== -1 ? files[existing] : null,
      index: existing !== -1 ? null : index,
    };

    try {
      const prUrl = await gh.openPr(target, entry, probe, lang);
      openedThisRequest.push({ body: `${channel.url} ${channel.key} ${probe.channelId || ''} ${prUrl}` });
      submitted++;
    } catch (e) {
      // GitHub hiccups are not the parent's fault — counting them as
      // "invalid" would send them off to re-check perfectly good URLs.
      console.error('PR creation failed (submit-list)', e);
      errors++;
    }
  }

  return json({ status: 'ok', submitted, duplicates, invalid, queueFull, errors }, 200, cors);
}

/**
 * Contact form: a problem becomes a labelled issue, an idea a discussion in
 * the Ideas category. Same cheap-first order as the suggestion route, and the
 * cap counts only OPEN items so triaging the queue is what reopens the form.
 */
async function handleContact(body, request, env, cors) {
  // Honeypot: success, and with a url — a fake reply missing the field the
  // real one carries would be all a bot needs to tell the two apart.
  if (body.website) {
    return json({ status: 'ok', url: `https://github.com/${env.GITHUB_REPO}/issues` }, 200, cors);
  }

  const kind = String(body.kind || '');
  if (kind !== 'problem' && kind !== 'idea') return json({ status: 'invalid' }, 422, cors);
  const message = String(body.message || '').trim().slice(0, 2000);
  if (!message) return json({ status: 'invalid' }, 422, cors);

  if (!(await verifyTurnstile(body.turnstile, request, env))) {
    return json({ status: 'error', message: 'Human check failed.' }, 403, cors);
  }

  const gh = github(env);
  try {
    const open = kind === 'problem' ? await gh.openContactIssues() : await gh.openIdeaCount();
    if (open >= Number(env.MAX_OPEN_CONTACT || 20)) {
      return json({ status: 'busy' }, 429, cors);
    }
  } catch (e) {
    // A permissions gap here must not read as an empty queue.
    console.error('contact queue check failed', e);
    return json({ status: 'error' }, 500, cors);
  }

  const title = contactTitle(message, kind);
  const text = contactBody(message);
  try {
    const url = kind === 'problem'
      ? await gh.createContactIssue(title, text)
      : await gh.createIdeaDiscussion(title, text);
    return json({ status: 'ok', url }, 200, cors);
  } catch (e) {
    console.error('contact create failed', e);
    return json({ status: 'error' }, 500, cors);
  }
}

/** First line, clamped — the rest of the message is the body anyway. */
function contactTitle(message, kind) {
  const first = message.split('\n')[0].trim();
  if (!first) return kind === 'idea' ? 'Idea from the website' : 'Problem report from the website';
  return first.length > 70 ? `${first.slice(0, 69).trimEnd()}…` : first;
}

function contactBody(message) {
  return [
    neutralizeMentions(message),
    '',
    '---',
    '_Sent anonymously via the contact form on pickwick.tv._',
  ].join('\n');
}

/**
 * An anonymous, unauthenticated body lands in markdown, where "@someone"
 * notifies a real GitHub user — a free spam cannon aimed at anyone. An empty
 * HTML comment after the @ stops the mention from linking while still reading
 * as "@someone" to a human. A code fence would do it too, but the submitter
 * controls the text and can close the fence; this has nothing to escape.
 */
function neutralizeMentions(text) {
  return text.replace(/@(?=[A-Za-z0-9-])/g, '@<!---->');
}

/**
 * ISO 639-1 shape plus a real name from Intl — no hand-kept language table.
 * DisplayNames echoes the code back for made-up ones ("zz"), which is the
 * rejection signal. The English name feeds index.json and the PR body.
 */
function normalizeLang(raw) {
  const code = String(raw || '').trim().toLowerCase();
  if (!/^[a-z]{2}$/.test(code)) return null;
  try {
    const name = new Intl.DisplayNames(['en'], { type: 'language' }).of(code);
    if (!name || name.toLowerCase() === code) return null;
    return { code, name };
  } catch {
    return null;
  }
}

function corsHeaders(request, env) {
  const allowed = (env.ALLOWED_ORIGINS || '').split(',').map((s) => s.trim());
  const origin = request.headers.get('Origin') || '';
  return {
    'Access-Control-Allow-Origin': allowed.includes(origin) ? origin : allowed[0] || '*',
    'Access-Control-Allow-Methods': 'POST, OPTIONS',
    'Access-Control-Allow-Headers': 'Content-Type',
    'Access-Control-Max-Age': '86400',
  };
}

function json(obj, status, cors) {
  return new Response(JSON.stringify(obj), {
    status,
    headers: { 'Content-Type': 'application/json', ...cors },
  });
}

async function verifyTurnstile(token, request, env) {
  if (!token) return false;
  const form = new URLSearchParams({
    secret: env.TURNSTILE_SECRET,
    response: token,
    remoteip: request.headers.get('CF-Connecting-IP') || '',
  });
  const r = await fetch('https://challenges.cloudflare.com/turnstile/v0/siteverify', {
    method: 'POST',
    body: form,
  });
  const data = await r.json().catch(() => ({}));
  return data.success === true;
}

/**
 * Channel and playlist forms the app's WhitelistParser also accepts: @handle,
 * /channel/UC…, /user/name, /c/name, playlist?list=…. Deliberately lenient —
 * parents paste whatever YouTube's Share button gave them (missing scheme,
 * m. host, trailing /videos tab, watch?v=…&list=… share links). Mirrors
 * parseYouTubeLink in site/suggest.html; keep the two in sync.
 */
function parseSuggestionUrl(raw) {
  raw = raw.trim();
  if (raw && !/^https?:\/\//i.test(raw)) raw = 'https://' + raw;
  let u;
  try {
    u = new URL(raw);
  } catch {
    return null;
  }
  const host = u.hostname.toLowerCase().replace(/^(www|m)\./, '');
  if (host !== 'youtube.com' && host !== 'youtu.be') return null;
  // A playlist id anywhere wins — including watch?v=…&list=… share links.
  const list = u.searchParams.get('list');
  if (list && /^[A-Za-z0-9_-]{10,}$/.test(list)) {
    return {
      kind: 'playlist',
      url: `https://www.youtube.com/playlist?list=${list}`,
      playlistId: list,
      channelId: null,
      display: list,
      // Case preserved: playlist ids are case-sensitive, unlike handles.
      key: `list=${list}`,
    };
  }
  if (host === 'youtu.be') return null;
  const m = u.pathname.match(/^\/(@[\w.-]{3,}|channel\/(UC[\w-]{22})|(?:user|c)\/[\w.-]+)(?:\/[\w-]*)?\/?$/);
  if (!m) return null;
  const path = m[1];
  return {
    kind: 'channel',
    url: `https://www.youtube.com/${path}`,
    playlistId: null,
    channelId: m[2] || null,
    display: path.startsWith('@') ? path : path.split('/').pop(),
    key: path.toLowerCase(),
  };
}

/**
 * Existence check that must never cost money: fetch the channel page and pull
 * the canonical UC id + title out of the HTML. YouTube sometimes bot-walls
 * datacenter IPs — treat anything but a clean 404 as "unverified", not a
 * rejection, so a wall never blocks a real parent. The screening Action and
 * the human review both look again later.
 */
async function probeChannel(channel) {
  try {
    const r = await fetch(channel.url, {
      headers: { 'User-Agent': 'Mozilla/5.0 (compatible; PickwickDirectory/1.0)' },
      redirect: 'follow',
    });
    if (r.status === 404) return { status: 'missing' };
    if (!r.ok) return { status: 'unverified' };
    const html = await r.text();
    // Canonical link first — the page HTML mentions other channels' ids too
    // (a parent org's, featured channels'), and the first "channelId" match
    // can be one of those (seen live: @StorylineOnline's first match was the
    // SAG-AFTRA Foundation).
    const id = html.match(/rel="canonical" href="https:\/\/www\.youtube\.com\/channel\/(UC[\w-]{22})"/)?.[1]
      || html.match(/"channelId":"(UC[\w-]{22})"/)?.[1] || null;
    const title = html.match(/<meta property="og:title" content="([^"]{1,120})"/)?.[1] || null;
    return { status: id ? 'verified' : 'unverified', channelId: id, title: decodeEntities(title) };
  } catch {
    return { status: 'unverified' };
  }
}

/**
 * Playlist existence check via the public RSS feed — same no-cost, no-key
 * constraint as probeChannel, and the feed endpoint is far less bot-walled
 * than watch/playlist pages. First <title> in the feed is the playlist name.
 */
async function probePlaylist(channel) {
  try {
    const r = await fetch(
      `https://www.youtube.com/feeds/videos.xml?playlist_id=${channel.playlistId}`,
      { headers: { 'User-Agent': 'Mozilla/5.0 (compatible; PickwickDirectory/1.0)' } }
    );
    if (r.status === 404) return { status: 'missing' };
    if (!r.ok) return { status: 'unverified' };
    const xml = await r.text();
    const title = xml.match(/<title>([^<]{1,120})<\/title>/)?.[1] || null;
    return { status: title ? 'verified' : 'unverified', channelId: null, title: decodeEntities(title) };
  } catch {
    return { status: 'unverified' };
  }
}

function decodeEntities(s) {
  return s && s
    .replace(/&amp;/g, '&').replace(/&lt;/g, '<').replace(/&gt;/g, '>')
    .replace(/&quot;/g, '"').replace(/&#39;/g, "'");
}

function isDuplicate(channel, probe, entries, openPrs) {
  const ids = [channel.channelId, probe.channelId].filter(Boolean);
  // Handles compare case-insensitively (YouTube treats @Kids and @kids as
  // one channel); playlist ids are case-sensitive and must match raw.
  const caseSensitive = channel.kind === 'playlist';
  const seen = (text) => {
    const matched = caseSensitive
      ? text.includes(channel.key)
      : text.toLowerCase().includes(channel.key);
    return matched || ids.some((id) => text.includes(id));
  };
  // An entry's stored channelId catches the direction URL text can't: the
  // directory says "@handle", the submission says "channel/UC…" — the probe
  // resolved the submission's UC id, and the stored id is the other half.
  return entries.some((e) => seen(e.url) || (e.channelId && ids.includes(e.channelId)))
    || openPrs.some((pr) => seen(pr.body || ''));
}

function github(env) {
  const api = (path, init = {}) =>
    fetch(`https://api.github.com${path}`, {
      ...init,
      headers: {
        Authorization: `Bearer ${env.GITHUB_TOKEN}`,
        Accept: 'application/vnd.github+json',
        'User-Agent': 'pickwick-suggest-worker',
        ...init.headers,
      },
    });

  /** GraphQL reports failures in a 200 body; surface them as throws. */
  const graphql = async (query, variables) => {
    const r = await api('/graphql', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ query, variables }),
    });
    if (!r.ok) throw new Error(`graphql ${r.status}`);
    const data = await r.json();
    if (data.errors?.length) throw new Error(`graphql ${data.errors[0].message}`);
    return data.data;
  };

  const putFile = async (path, branch, message, json, sha) => {
    const content = JSON.stringify(json, null, 2) + '\n';
    // Spreading the whole file into one call hits engine argument limits
    // once a language file grows past a few hundred KB; chunk it.
    const bytes = new TextEncoder().encode(content);
    let bin = '';
    for (let i = 0; i < bytes.length; i += 0x8000) {
      bin += String.fromCharCode(...bytes.subarray(i, i + 0x8000));
    }
    const b64 = btoa(bin);
    const r = await api(`/repos/${env.GITHUB_REPO}/contents/${path}`, {
      method: 'PUT',
      body: JSON.stringify({ message, content: b64, branch, ...(sha ? { sha } : {}) }),
    });
    if (!r.ok) throw new Error(`content put ${path} ${r.status}`);
  };

  return {
    /** {sha, json} from main, or null on 404 (a language with no file yet). */
    async getFile(path) {
      const r = await api(`/repos/${env.GITHUB_REPO}/contents/${path}?ref=main`);
      if (r.status === 404) return null;
      if (!r.ok) throw new Error(`${path} fetch ${r.status}`);
      const file = await r.json();
      const text = new TextDecoder().decode(
        Uint8Array.from(atob(file.content.replace(/\n/g, '')), (c) => c.charCodeAt(0))
      );
      return { sha: file.sha, json: JSON.parse(text) };
    },

    async openSubmissionPrs() {
      const r = await api(`/repos/${env.GITHUB_REPO}/pulls?state=open&per_page=100`);
      if (!r.ok) return [];
      const prs = await r.json();
      return prs.filter((pr) => pr.head?.ref?.startsWith('submission/'));
    },

    /** Open contact issues, bounded by the page — the cap is far under 100. */
    async openContactIssues() {
      const r = await api(`/repos/${env.GITHUB_REPO}/issues?labels=contact&state=open&per_page=100`);
      if (!r.ok) throw new Error(`contact issues ${r.status}`);
      const issues = await r.json();
      // The issues endpoint also returns pull requests; they carry a pull_request key.
      return issues.filter((i) => !i.pull_request).length;
    },

    /**
     * Discussions have no REST list endpoint. Counting through the repository
     * node rather than the search API keeps this exact and immediate — search
     * is eventually consistent, and a lagging index would let the cap drift.
     */
    async openIdeaCount() {
      const data = await graphql(
        `query($repo: ID!, $category: ID!) {
           node(id: $repo) {
             ... on Repository {
               discussions(categoryId: $category, states: OPEN, first: 1) { totalCount }
             }
           }
         }`,
        { repo: env.REPO_NODE_ID, category: env.IDEAS_CATEGORY_ID }
      );
      const count = data?.node?.discussions?.totalCount;
      if (typeof count !== 'number') throw new Error('discussion count missing');
      return count;
    },

    async createContactIssue(title, body) {
      const r = await api(`/repos/${env.GITHUB_REPO}/issues`, {
        method: 'POST',
        body: JSON.stringify({ title, body, labels: ['contact', 'bug'] }),
      });
      if (!r.ok) throw new Error(`issue create ${r.status}`);
      return (await r.json()).html_url;
    },

    async createIdeaDiscussion(title, body) {
      const data = await graphql(
        `mutation($repo: ID!, $category: ID!, $title: String!, $body: String!) {
           createDiscussion(input: {
             repositoryId: $repo, categoryId: $category, title: $title, body: $body
           }) { discussion { url } }
         }`,
        { repo: env.REPO_NODE_ID, category: env.IDEAS_CATEGORY_ID, title, body }
      );
      const url = data?.createDiscussion?.discussion?.url;
      if (!url) throw new Error('discussion create returned no url');
      return url;
    },

    async openPr(target, entry, probe, lang) {
      const mainRef = await (await api(`/repos/${env.GITHUB_REPO}/git/ref/heads/main`)).json();
      const suffix = crypto.getRandomValues(new Uint32Array(1))[0].toString(36);
      const slug = entry.name.toLowerCase().replace(/[^a-z0-9]+/g, '-').replace(/^-|-$/g, '').slice(0, 40);
      const branch = `submission/${slug || 'channel'}-${suffix}`;

      let r = await api(`/repos/${env.GITHUB_REPO}/git/refs`, {
        method: 'POST',
        body: JSON.stringify({ ref: `refs/heads/${branch}`, sha: mainRef.object.sha }),
      });
      if (!r.ok) throw new Error(`branch create ${r.status}`);

      const base = target.file?.json || { language: lang.code, updated: entry.added, entries: [] };
      const updated = { ...base, updated: entry.added, entries: [...(base.entries || []), entry] };
      await putFile(target.path, branch, `Directory suggestion: ${entry.name}`, updated, target.file?.sha);

      // First suggestion in a new language: list its file in index.json so the
      // site and app pick it up the moment the PR merges.
      if (target.index) {
        const idx = target.index.json;
        const languages = [...(idx.languages || []), { code: lang.code, name: lang.name, file: `${lang.code}.json` }];
        await putFile(
          `${(env.DIRECTORY_DIR || 'site/directory')}/index.json`,
          branch,
          `Directory: add ${lang.name}`,
          { ...idx, languages },
          target.index.sha
        );
      }

      const verification = probe.status === 'verified'
        ? `✅ ${entry.kind === 'playlist' ? 'Playlist' : 'Channel'} verified on YouTube${probe.channelId ? ` (\`${probe.channelId}\`)` : ''}.`
        : `⚠️ Could not verify the ${entry.kind} from the worker (possibly bot-walled) — the screening action will retry.`;

      // The name is whoever owns the YouTube channel talking (og:title), the
      // note is form free-text: defuse @mentions like /contact does, and keep
      // brackets out of the link text so a title can't rewrite where the
      // reviewer's click actually goes.
      const safeName = neutralizeMentions(entry.name).replace(/[\[\]]/g, '');
      const safeNote = entry.note ? neutralizeMentions(entry.note) : '';

      r = await api(`/repos/${env.GITHUB_REPO}/pulls`, {
        method: 'POST',
        body: JSON.stringify({
          title: `Directory suggestion: ${entry.name}`,
          head: branch,
          base: 'main',
          body: [
            `A parent suggested the ${entry.kind} **[${safeName}](${entry.url})** for the directory.`,
            '',
            `- Language: ${lang.name}${target.index ? ' — **first entry in this language**, adds its file and index.json listing' : ''}`,
            `- Ages: ${entry.ages.join(', ') || '(left blank — AI screening will propose)'}`,
            `- Topics: ${entry.topics.join(', ') || '(left blank — AI screening will propose)'}`,
            `- Note: ${safeNote ? `“${safeNote}”` : '(left blank — AI screening will propose)'}`,
            `- ${verification}`,
            '',
            'The AI screening workflow will comment with a verdict. **Merging publishes the channel** to the website directory and the in-app browser.',
          ].join('\n'),
        }),
      });
      if (!r.ok) throw new Error(`pr create ${r.status}`);
      const pr = await r.json();
      return pr.html_url;
    },
  };
}
