/**
 * Pickwick channel-suggestion receiver — a stateless mail-slot, not a backend.
 *
 * POST { url, ages[], topics[], note, lang, website, turnstile } from site/suggest.html.
 * Gates, in cost order (everything before the PR is free):
 *   1. honeypot ("website" filled → pretend success, tell the bot nothing)
 *   2. Turnstile verification
 *   3. URL shape + channel/playlist existence on YouTube
 *   4. dedup against the published directory and open submission PRs
 *   5. open-queue cap (bill protection for the AI screening Action)
 * Survivors become a PR against site/directory/<lang>.json — created along
 * with its index.json listing when it's the first suggestion in that
 * language. The AI screening workflow comments a verdict and a human merges.
 * This worker never writes to main directly.
 *
 * Secrets:  TURNSTILE_SECRET, GITHUB_TOKEN (fine-grained: Contents RW + Pull requests RW)
 * Vars:     see wrangler.toml
 */

const KNOWN_AGES = ['2-4', '5-7', '8-10', '11+'];

export default {
  async fetch(request, env) {
    const cors = corsHeaders(request, env);
    if (request.method === 'OPTIONS') return new Response(null, { status: 204, headers: cors });
    if (request.method !== 'POST') return json({ status: 'error' }, 405, cors);

    let body;
    try {
      body = await request.json();
    } catch {
      return json({ status: 'error' }, 400, cors);
    }

    // Honeypot: report success so the bot has nothing to learn from.
    if (body.website) return json({ status: 'ok' }, 200, cors);

    const note = String(body.note || '').trim().slice(0, 140);
    const ages = (Array.isArray(body.ages) ? body.ages : []).filter((a) => KNOWN_AGES.includes(a));
    const topics = (Array.isArray(body.topics) ? body.topics : [])
      .map((t) => String(t).slice(0, 40)).slice(0, 3);
    // Ages and topics may be empty — the screening workflow fills blanks with
    // AI-suggested values on the PR, where the reviewer sees them pre-merge.
    if (!note) return json({ status: 'invalid', message: 'Missing fields.' }, 422, cors);

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
  },
};

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
      key: `list=${list.toLowerCase()}`,
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
  const seen = (text) => {
    const t = text.toLowerCase();
    return t.includes(channel.key) || ids.some((id) => text.includes(id));
  };
  return entries.some((e) => seen(e.url)) || openPrs.some((pr) => seen(pr.body || ''));
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

  const putFile = async (path, branch, message, json, sha) => {
    const content = JSON.stringify(json, null, 2) + '\n';
    const b64 = btoa(String.fromCharCode(...new TextEncoder().encode(content)));
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

      r = await api(`/repos/${env.GITHUB_REPO}/pulls`, {
        method: 'POST',
        body: JSON.stringify({
          title: `Directory suggestion: ${entry.name}`,
          head: branch,
          base: 'main',
          body: [
            `A parent suggested the ${entry.kind} **[${entry.name}](${entry.url})** for the directory.`,
            '',
            `- Language: ${lang.name}${target.index ? ' — **first entry in this language**, adds its file and index.json listing' : ''}`,
            `- Ages: ${entry.ages.join(', ') || '(left blank — AI screening will propose)'}`,
            `- Topics: ${entry.topics.join(', ') || '(left blank — AI screening will propose)'}`,
            `- Note: “${entry.note}”`,
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
