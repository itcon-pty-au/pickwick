/**
 * Pickwick channel-suggestion receiver — a stateless mail-slot, not a backend.
 *
 * POST { url, ages[], topics[], note, website, turnstile } from docs/suggest.html.
 * Gates, in cost order (everything before the PR is free):
 *   1. honeypot ("website" filled → pretend success, tell the bot nothing)
 *   2. Turnstile verification
 *   3. URL shape + channel existence on YouTube
 *   4. dedup against the published directory and open submission PRs
 *   5. open-queue cap (bill protection for the AI screening Action)
 * Survivors become a PR against docs/directory/en.json; the AI screening
 * workflow comments a verdict and a human merges. This worker never writes
 * to main directly.
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
    if (!note || !ages.length) return json({ status: 'invalid', message: 'Missing fields.' }, 422, cors);

    if (!(await verifyTurnstile(body.turnstile, request, env))) {
      return json({ status: 'error', message: 'Human check failed.' }, 403, cors);
    }

    const channel = parseChannelUrl(String(body.url || ''));
    if (!channel) {
      return json({ status: 'invalid', message: 'Not a recognizable YouTube channel link.' }, 422, cors);
    }

    const probe = await probeChannel(channel);
    if (probe.status === 'missing') {
      return json({ status: 'invalid', message: 'YouTube says that channel doesn’t exist.' }, 422, cors);
    }

    const gh = github(env);
    const [directory, openPrs] = await Promise.all([gh.getDirectory(), gh.openSubmissionPrs()]);
    if (openPrs.length >= Number(env.MAX_OPEN_SUBMISSIONS || 25)) {
      return json({ status: 'busy' }, 429, cors);
    }
    if (isDuplicate(channel, probe, directory.entries, openPrs)) {
      return json({ status: 'duplicate' }, 200, cors);
    }

    const entry = {
      url: channel.url,
      name: probe.title || channel.display,
      kind: 'channel',
      ages,
      topics,
      note,
      added: new Date().toISOString().slice(0, 10),
    };

    try {
      const prUrl = await gh.openPr(directory, entry, probe);
      return json({ status: 'ok', pr: prUrl }, 200, cors);
    } catch (e) {
      console.error('PR creation failed', e);
      return json({ status: 'error' }, 500, cors);
    }
  },
};

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
 * Channel URL forms the app's WhitelistParser also accepts: @handle,
 * /channel/UC…, /user/name, /c/name. Playlists stay hand-curated for now.
 */
function parseChannelUrl(raw) {
  const m = raw.trim().match(
    /^https?:\/\/(?:(?:www|m)\.)?youtube\.com\/(@[\w.-]{3,}|channel\/(UC[\w-]{22})|(?:user|c)\/[\w.-]+)\/?(?:[?#].*)?$/i
  );
  if (!m) return null;
  const path = m[1];
  return {
    url: `https://www.youtube.com/${path}`,
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
    const id = html.match(/"channelId":"(UC[\w-]{22})"/)?.[1]
      || html.match(/youtube\.com\/channel\/(UC[\w-]{22})/)?.[1] || null;
    const title = html.match(/<meta property="og:title" content="([^"]{1,120})"/)?.[1] || null;
    return { status: id ? 'verified' : 'unverified', channelId: id, title: decodeEntities(title) };
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

  return {
    async getDirectory() {
      const r = await api(`/repos/${env.GITHUB_REPO}/contents/${env.DIRECTORY_PATH}?ref=main`);
      if (!r.ok) throw new Error(`directory fetch ${r.status}`);
      const file = await r.json();
      const text = new TextDecoder().decode(
        Uint8Array.from(atob(file.content.replace(/\n/g, '')), (c) => c.charCodeAt(0))
      );
      return { sha: file.sha, json: JSON.parse(text), entries: JSON.parse(text).entries };
    },

    async openSubmissionPrs() {
      const r = await api(`/repos/${env.GITHUB_REPO}/pulls?state=open&per_page=100`);
      if (!r.ok) return [];
      const prs = await r.json();
      return prs.filter((pr) => pr.head?.ref?.startsWith('submission/'));
    },

    async openPr(directory, entry, probe) {
      const mainRef = await (await api(`/repos/${env.GITHUB_REPO}/git/ref/heads/main`)).json();
      const suffix = crypto.getRandomValues(new Uint32Array(1))[0].toString(36);
      const slug = entry.name.toLowerCase().replace(/[^a-z0-9]+/g, '-').replace(/^-|-$/g, '').slice(0, 40);
      const branch = `submission/${slug || 'channel'}-${suffix}`;

      let r = await api(`/repos/${env.GITHUB_REPO}/git/refs`, {
        method: 'POST',
        body: JSON.stringify({ ref: `refs/heads/${branch}`, sha: mainRef.object.sha }),
      });
      if (!r.ok) throw new Error(`branch create ${r.status}`);

      const updated = { ...directory.json, updated: entry.added };
      updated.entries = [...directory.entries, entry];
      const content = JSON.stringify(updated, null, 2) + '\n';
      const b64 = btoa(String.fromCharCode(...new TextEncoder().encode(content)));

      r = await api(`/repos/${env.GITHUB_REPO}/contents/${env.DIRECTORY_PATH}`, {
        method: 'PUT',
        body: JSON.stringify({
          message: `Directory suggestion: ${entry.name}`,
          content: b64,
          sha: directory.sha,
          branch,
        }),
      });
      if (!r.ok) throw new Error(`content put ${r.status}`);

      const verification = probe.status === 'verified'
        ? `✅ Channel verified on YouTube (\`${probe.channelId}\`).`
        : '⚠️ Could not verify the channel from the worker (possibly bot-walled) — the screening action will retry.';

      r = await api(`/repos/${env.GITHUB_REPO}/pulls`, {
        method: 'POST',
        body: JSON.stringify({
          title: `Directory suggestion: ${entry.name}`,
          head: branch,
          base: 'main',
          body: [
            `A parent suggested **[${entry.name}](${entry.url})** for the directory.`,
            '',
            `- Ages: ${entry.ages.join(', ')}`,
            `- Topics: ${entry.topics.join(', ') || '(none picked)'}`,
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
