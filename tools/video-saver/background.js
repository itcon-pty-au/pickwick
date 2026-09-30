import { classify, preferredMaster } from "./media.mjs";
import { DownloadQueue } from "./queue.mjs";

// Serialize session updates so concurrent range requests do not lose candidates.
let pending = Promise.resolve();
function serial(task) {
  const result = pending.then(task);
  pending = result.catch(() => {});
  return result;
}
const key = id => `tab:${id}`;
const autoSite = url => {
  try { return /(^|\.)pokeflix\.tv$/.test(new URL(url).hostname); } catch { return false; }
};
const downloads = new DownloadQueue(chrome);

function onMediaHeaders(details) {
  if (details.tabId < 0 || details.method !== "GET" || details.statusCode < 200 || details.statusCode >= 300) return;
  const mime = details.responseHeaders?.find(h => h.name.toLowerCase() === "content-type")?.value || "";
  const kind = classify(details.url, mime);
  if (!kind) return;
  void serial(async () => {
    const id = key(details.tabId);
    const tab = await chrome.tabs.get(details.tabId).catch(() => null);
    if (!tab || !autoSite(tab.url)) return;
    let state = (await chrome.storage.session.get(id))[id];
    if (!state || state.page !== tab.url) state = { page: tab.url, title: tab.title || "video", items: [] };
    if (state.items.some(item => item.url === details.url)) return;
    if (state.items.length >= 60) return;
    state.items.push({ url: details.url, mime, kind, frameId: details.frameId });
    await chrome.storage.session.set({ [id]: state });
  });
}

const mediaOrigins = ["http://*/*", "https://*/*"];
async function updateMediaListener() {
  // Also handles browser-level site-access restrictions and extension upgrades.
  const granted = await chrome.permissions.contains({ origins: mediaOrigins });
  const event = chrome.webRequest.onHeadersReceived;
  if (granted && !event.hasListener(onMediaHeaders)) {
    event.addListener(onMediaHeaders, { urls: mediaOrigins }, ["responseHeaders"]);
  } else if (!granted && event.hasListener(onMediaHeaders)) {
    event.removeListener(onMediaHeaders);
  }
  return granted;
}
const refreshPermissions = () => { void serial(updateMediaListener); };
chrome.permissions.onAdded.addListener(refreshPermissions);
chrome.permissions.onRemoved.addListener(refreshPermissions);
refreshPermissions();

chrome.tabs.onRemoved.addListener(id => { void serial(() => chrome.storage.session.remove(key(id))); });
chrome.tabs.onUpdated.addListener((tabId, change) => {
  if (change.url || change.status === "loading") void serial(() => chrome.storage.session.remove(key(tabId)));
});

function download(state, item, partner) {
  if (!partner) item = preferredMaster(item, state.items);
  const candidates = state.items.filter(other => other.frameId === item.frameId && other.url !== item.url).slice(0, 12).map(other => other.url);
  return downloads.add({ url: item.url, title: state.title, partner: partner?.url, candidates });
}
chrome.runtime.onMessage.addListener((message, sender, respond) => {
  if (sender.id !== chrome.runtime.id || (sender.tab && sender.url !== chrome.runtime.getURL("popup.html"))) return;
  serial(async () => {
    const id = key(message.tabId);
    if (message.type === "download") {
      const state = (await chrome.storage.session.get(id))[id];
      const item = state?.items[message.index];
      if (!item) throw new Error("No media selected.");
      const partner = message.partnerIndex === undefined ? undefined : state?.items[message.partnerIndex];
      if (message.partnerIndex !== undefined && (!partner || partner === item)) throw new Error("Select a different companion feed.");
      await download(state, item, partner);
    } else if (message.type === "queueAction") {
      await downloads.update(message.id, message.action);
    } else if (message.type === "parallel") {
      await downloads.setLimit(message.limit);
    } else if (message.type === "clearFinished") {
      await downloads.clearFinished();
    } else if (message.type !== "list") {
      throw new Error("Unknown request.");
    }
    await downloads.pending;
    return { state: (await chrome.storage.session.get(id))[id] || null, queue: downloads.snapshot() };
  }).then(respond, error => respond({ error: error.message }));
  return true;
});
