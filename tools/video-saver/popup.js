const $ = id => document.getElementById(id);
let tabId, windowId, refreshGeneration = 0, lastSources = "", statusTimer, freshJob, freshAt = 0;
async function send(type, extra = {}) {
  const result = await chrome.runtime.sendMessage({ type, tabId, ...extra });
  if (!result || result.error) throw new Error(result?.error || "The extension did not respond. Reload it and try again.");
  return result;
}
// The toast floats over the panel because the add buttons are often scrolled far from the top.
function status(text, tone = "info") {
  clearTimeout(statusTimer);
  $("status").textContent = text;
  $("status").dataset.tone = tone;
  if (text) statusTimer = setTimeout(() => { $("status").textContent = ""; }, tone === "error" ? 8000 : 4000);
}
// Describe where the job landed; a duplicate is reported rather than silently ignored.
function outcome(result) {
  const jobs = result.queue?.jobs || [];
  const job = jobs.find(entry => entry.id === result.added?.id);
  if (!job) return { short: "Added", long: "Added to the download queue." };
  if (result.added.duplicate) return { short: "Already queued", long: "“" + job.title + "” is already in the queue (" + job.state + ")." };
  if (job.state === "running") return { short: "Downloading", long: "Downloading “" + job.title + "” now. Progress is in the queue below." };
  if (job.state === "failed") return { short: "Failed", long: job.status, error: true };
  const position = jobs.filter(entry => entry.state === "queued").indexOf(job) + 1;
  return { short: "Queued #" + position, long: "Queued “" + job.title + "” — #" + position + " in line, starts when a slot frees up." };
}
async function enqueue(button, type, payload) {
  const label = button.textContent;
  button.disabled = true;
  let shown;
  try {
    const result = await send(type, payload);
    shown = outcome(result);
    freshJob = result.added?.id;
    freshAt = performance.now();
    showQueue(result.queue);
    status(shown.long, shown.error ? "error" : "success");
    return !shown.error;
  } catch (error) {
    shown = { short: "Not added", error: true };
    status(error.message, "error");
    return false;
  } finally {
    button.textContent = (shown.error ? "✕ " : "✓ ") + shown.short;
    button.dataset.result = shown.error ? "error" : "success";
    setTimeout(() => { button.textContent = label; delete button.dataset.result; button.disabled = false; }, 1800);
  }
}
function showQueue(queue) {
  if (document.activeElement !== $("parallel")) $("parallel").value = String(queue?.limit || 3);
  if (document.activeElement !== $("fragments")) $("fragments").value = String(queue?.workers || 2);
  $("connections").textContent = "Up to " + Number($("parallel").value) * Number($("fragments").value) + " fragment requests across running downloads, plus media probes.";
  const jobs = queue?.jobs || [];
  const running = jobs.filter(job => job.state === "running").length;
  const waiting = jobs.filter(job => job.state === "queued").length;
  $("queue-status").textContent = jobs.length ? running + " running · " + waiting + " queued" : "Your downloads will appear here.";
  $("queue").replaceChildren();
  for (const job of jobs) {
    const row = document.createElement("li");
    row.dataset.state = job.state;
    // Progress updates rebuild the list; offset the highlight so it plays once, not per rebuild.
    const freshFor = performance.now() - freshAt;
    if (job.id === freshJob && freshFor < 2400) {
      row.classList.add("fresh");
      row.style.animationDelay = -freshFor + "ms";
    }
    const title = document.createElement("strong");
    title.textContent = job.title;
    const detail = document.createElement("small");
    detail.textContent = job.state + " · " + job.status;
    row.append(title, detail);
    if (job.path) {
      const path = document.createElement("small");
      path.textContent = job.path;
      row.append(path);
    }
    for (const action of job.state === "running" ? [] : ["failed", "interrupted"].includes(job.state) ? ["retry", "remove"] : ["remove"]) {
      const button = document.createElement("button");
      button.textContent = action === "retry" ? "Retry" : "Remove";
      button.onclick = async () => {
        try { showQueue((await send("queueAction", { id: job.id, action })).queue); }
        catch (error) { status(error.message, "error"); }
      };
      row.append(button);
    }
    $("queue").append(row);
  }
}

function showSources(state) {
  const identity = JSON.stringify([tabId, state]);
  if (identity === lastSources) return;
  lastSources = identity;
  $("items").replaceChildren();
  $("empty").hidden = !!state?.items.length;
  const sourceTab = tabId;
  for (const [index, item] of (state?.items || []).entries()) {
    const row = document.createElement("li");
    const title = document.createElement("strong");
    title.textContent = item.kind === "file" ? "Video file" : item.kind === "audio" ? "Audio source" : "HLS / DASH stream";
    const detail = document.createElement("small");
    const url = new URL(item.url);
    detail.textContent = url.hostname + url.pathname;
    row.append(title, detail);
    const advanced = document.createElement("details");
    const summary = document.createElement("summary");
    summary.textContent = "Pair a separate track";
    const partner = document.createElement("select");
    partner.setAttribute("aria-label", "Companion feed for source " + (index + 1));
    partner.append(new Option("Automatic pairing", ""));
    for (const [otherIndex, other] of state.items.entries()) {
      if (otherIndex === index) continue;
      partner.append(new Option((otherIndex + 1) + ": " + new URL(other.url).pathname, String(otherIndex)));
    }
    advanced.append(summary, partner);
    const button = document.createElement("button");
    button.className = "primary";
    button.textContent = "Add to queue";
    button.onclick = () => {
      const mode = item.kind === "audio" && $("mode").value === "video" ? "audio" : $("mode").value;
      void enqueue(button, "download", { tabId: sourceTab, index, url: item.url, mode, ...(partner.value === "" ? {} : { partnerIndex: Number(partner.value) }) });
    };
    row.append(button, advanced);
    $("items").append(row);
  }
}

async function refresh() {
  const generation = ++refreshGeneration;
  const [tab] = await chrome.tabs.query({ active: true, windowId });
  if (generation !== refreshGeneration) return;
  tabId = tab?.id;
  $("tab-title").textContent = tab?.title || "No active website";
  const result = await send("list");
  if (generation !== refreshGeneration) return;
  showQueue(result.queue);
  showSources(result.state);
}
$("refresh").onclick = () => refresh().catch(error => status(error.message));
for (const [id, type, key] of [["parallel", "parallel", "limit"], ["fragments", "fragments", "workers"]]) {
  $(id).onchange = async () => {
    try { showQueue((await send(type, { [key]: Number($(id).value) })).queue); status(""); }
    catch (error) { status(error.message); await refresh(); }
  };
}
$("clear").onclick = async () => {
  try { showQueue((await send("clearFinished")).queue); }
  catch (error) { status(error.message); }
};
$("url-form").onsubmit = async event => {
  event.preventDefault();
  if (await enqueue(event.submitter || $("url-form").querySelector("button"), "downloadUrl", { url: $("url").value.trim(), mode: $("mode").value })) $("url").value = "";
};
chrome.storage.onChanged.addListener((changes, area) => {
  if (area === "local" && changes.downloadQueue) showQueue(changes.downloadQueue.newValue);
  if (area === "session" && changes["tab:" + tabId]) void refresh().catch(error => status(error.message));
});
chrome.tabs.onActivated.addListener(info => {
  if (info.windowId === windowId) void refresh().catch(error => status(error.message));
});
chrome.tabs.onUpdated.addListener((id, change) => {
  if (id === tabId && (change.url || change.title)) void refresh().catch(error => status(error.message));
});
try {
  windowId = (await chrome.windows.getCurrent()).id;
  await refresh();
} catch (error) { status(error.message); }
