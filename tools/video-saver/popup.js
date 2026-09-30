const $ = id => document.getElementById(id);
let tabId;
async function send(type, extra = {}) {
  const result = await chrome.runtime.sendMessage({ type, tabId, ...extra });
  if (result.error) throw new Error(result.error);
  return result;
}
function status(text) { $("status").textContent = text; }
function showQueue(queue) {
  $("parallel").value = String(queue?.limit || 3);
  const jobs = queue?.jobs || [];
  $("queue-status").textContent = jobs.length ? `${jobs.filter(job => job.state === "running").length} running, ${jobs.filter(job => job.state === "queued").length} queued` : "No downloads queued.";
  $("queue").replaceChildren();
  for (const job of jobs) {
    const row = document.createElement("li");
    const title = document.createElement("strong");
    title.textContent = job.title;
    const detail = document.createElement("small");
    detail.textContent = `${job.state}: ${job.status}`;
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
        catch (error) { status(error.message); }
      };
      row.append(button);
    }
    $("queue").append(row);
  }
}
async function refresh() {
  const { state, queue } = await send("list");
  showQueue(queue);
  $("items").replaceChildren();
  status(state?.items.length ? `${state.items.length} source(s) detected.` : "Waiting for video playback on Pokeflix.");
  for (const [index, item] of (state?.items || []).entries()) {
    const row = document.createElement("li");
    const title = document.createElement("strong");
    title.textContent = item.kind === "file" ? "Direct media" : item.kind === "audio" ? "Audio feed" : "HLS / DASH manifest";
    const detail = document.createElement("small");
    const url = new URL(item.url);
    detail.textContent = url.hostname + url.pathname;
    row.append(title, detail);
    const label = document.createElement("label");
    label.textContent = "Companion feed";
    const partner = document.createElement("select");
    partner.setAttribute("aria-label", `Companion feed for source ${index + 1}`);
    partner.append(new Option("Automatic pairing", ""));
    for (const [otherIndex, other] of state.items.entries()) {
      if (otherIndex === index) continue;
      const otherUrl = new URL(other.url);
      partner.append(new Option(`${otherIndex + 1}: ${otherUrl.pathname}`, String(otherIndex)));
    }
    label.append(partner);
    row.append(label);
    {
      const button = document.createElement("button");
      button.textContent = "Queue best audio + video";
      button.onclick = async () => {
        button.disabled = true;
        try {
          showQueue((await send("download", { index, ...(partner.value === "" ? {} : { partnerIndex: Number(partner.value) }) })).queue);
          status("Added to download queue.");
        }
        catch (error) { status(error.message); }
        finally { button.disabled = false; }
      };
      row.append(button);
    }
    $("items").append(row);
  }
}
$("refresh").onclick = () => refresh().catch(error => status(error.message));
$("parallel").onchange = async () => {
  try { showQueue((await send("parallel", { limit: Number($("parallel").value) })).queue); }
  catch (error) { status(error.message); }
};
$("clear").onclick = async () => {
  try { showQueue((await send("clearFinished")).queue); }
  catch (error) { status(error.message); }
};
chrome.storage.onChanged.addListener((changes, area) => {
  if (area === "local" && changes.downloadQueue) showQueue(changes.downloadQueue.newValue);
  if (area !== "session") return;
  if (changes[`tab:${tabId}`]) void refresh().catch(error => status(error.message));
});
try {
  const [tab] = await chrome.tabs.query({ active: true, currentWindow: true });
  tabId = tab.id;
  await refresh();
} catch (error) { status(error.message); }
