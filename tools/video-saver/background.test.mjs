import test from "node:test";
import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import vm from "node:vm";
import { classify, filename, preferredMaster } from "./media.mjs";
import { DownloadQueue } from "./queue.mjs";

const source = (await readFile(new URL("./background.js", import.meta.url), "utf8"))
  .replace('import { classify, preferredMaster } from "./media.mjs";', "")
  .replace('import { DownloadQueue } from "./queue.mjs";', "");

function setup(initialGrant = false, tabUrl = "https://www.pokeflix.tv/v/episode") {
  let granted = initialGrant;
  let registrations = 0;
  const listeners = new Set();
  const changes = {};
  const stored = {};
  let onMessage;
  let onUpdated;
  const chrome = {
    permissions: {
      contains: async () => granted,
      onAdded: { addListener: fn => { changes.add = fn; } },
      onRemoved: { addListener: fn => { changes.remove = fn; } }
    },
    webRequest: { onHeadersReceived: {
      hasListener: fn => listeners.has(fn),
      addListener: fn => {
        assert.equal(granted, true, "must not register without host permission");
        listeners.add(fn);
        registrations++;
      },
      removeListener: fn => listeners.delete(fn)
    } },
    tabs: { onRemoved: { addListener() {} }, onUpdated: { addListener: fn => { onUpdated = fn; } }, get: async () => ({ url: tabUrl, title: "Episode" }) },
    storage: { local: { get: async () => ({}), set: async () => {} }, session: {
      get: async id => ({ [id]: stored[id] }),
      set: async value => Object.assign(stored, value),
      remove: async id => { delete stored[id]; }
    } },
    runtime: { id: "test", onMessage: { addListener: fn => { onMessage = fn; } } }
  };
  const context = vm.createContext({ chrome, classify, filename, preferredMaster, DownloadQueue, URL });
  vm.runInContext(source, context);
  return {
    flush: () => vm.runInContext("pending", context),
    grant(value) { granted = value; changes[value ? "add" : "remove"](); },
    count: () => listeners.size,
    registrations: () => registrations,
    navigate(url) { tabUrl = url; onUpdated(1, { url }); },
    list: () => new Promise(resolve => onMessage({ type: "list", tabId: 1 }, { id: "test" }, resolve)),
    media() { for (const fn of listeners) fn({ tabId: 1, method: "GET", statusCode: 200, url: "https://cdn.example.com/master.m3u8" }); }
  };
}

test("fresh install does not register webRequest without host permissions", async () => {
  const app = setup();
  await app.flush();
  assert.equal(app.count(), 0);
  assert.equal((await app.list()).state, null);
});

test("grant registers once, capture works, revoke removes listener", async () => {
  const app = setup();
  await app.flush();
  app.grant(true);
  await app.flush();
  assert.equal(app.count(), 1);
  app.media();
  await app.flush();
  assert.equal((await app.list()).state.title, "Episode");
  assert.equal((await app.list()).state.items[0].kind, "segmented");
  assert.equal(app.registrations(), 1);
  app.grant(false);
  await app.flush();
  assert.equal(app.count(), 0);
});

test("worker restart restores listener when permission already granted", async () => {
  const app = setup(true);
  await app.flush();
  assert.equal(app.count(), 1);
});

test("auto-capture ignores unrelated websites and lookalike hosts", async () => {
  for (const url of ["https://example.com", "https://pokeflix.tv.evil.example", "https://notpokeflix.tv"]) {
    const app = setup(true, url);
    await app.flush();
    app.media();
    await app.flush();
    assert.equal((await app.list()).state, null);
  }
});

test("navigation clears old episode sources and repeated ranges are deduplicated", async () => {
  const app = setup(true);
  await app.flush();
  app.media();
  app.media();
  await app.flush();
  assert.equal((await app.list()).state.items.length, 1);
  app.navigate("https://www.pokeflix.tv/v/next");
  await app.flush();
  assert.equal((await app.list()).state, null);
});
