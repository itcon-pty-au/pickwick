// Run: node panel.test.mjs ABSOLUTE_PLAYWRIGHT_CORE_PATH CHROME_EXE [SCREENSHOT_PATH]
// This exercises the real panel in Chrome with a deterministic extension API fixture.
// Native downloads are covered separately by Python tests.
import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import { createRequire } from "node:module";
const require = createRequire(import.meta.url);
const { chromium } = require(process.argv[2]);
const browser = await chromium.launch({ executablePath: process.argv[3], headless: true });
try {
  const page = await browser.newPage({ viewport: { width: 390, height: 960 } });
  const errors = [];
  page.on("pageerror", error => errors.push(error.message));
  await page.route("**/*", async route => {
    const name = new URL(route.request().url()).pathname.slice(1) || "popup.html";
    if (!["popup.html", "popup.css", "popup.js"].includes(name)) return route.abort();
    await route.fulfill({ body: await readFile(new URL(name, import.meta.url)), contentType: name.endsWith(".js") ? "text/javascript" : name.endsWith(".css") ? "text/css" : "text/html" });
  });
  await page.addInitScript(() => {
    let active = 1;
    const listeners = {};
    const queue = { limit: 1, workers: 2, jobs: [] };
    const state = { page: "https://example.com/lecture", title: "A lecture", items: [{ url: "https://media.example.com/master.m3u8?token=SECRET", kind: "segmented", frameId: 1 }] };
    window.chrome = {
      windows: { getCurrent: async () => ({ id: 1 }) },
      tabs: {
        query: async () => [{ id: active, title: active === 1 ? "A lecture" : "Another tab" }],
        onActivated: { addListener(fn) { listeners.activated = fn; } },
        onUpdated: { addListener(fn) { listeners.updated = fn; } }
      },
      storage: { onChanged: { addListener(fn) { listeners.storage = fn; } } },
      runtime: { async sendMessage(message) {
        let added;
        if (message.type === "download" || message.type === "downloadUrl") {
          const existing = queue.jobs.find(job => job.mode === message.mode);
          if (existing) added = { id: existing.id, duplicate: true };
          else {
            const running = queue.jobs.filter(job => job.state === "running").length;
            queue.jobs.push({ id: String(queue.jobs.length), title: "A lecture", mode: message.mode, state: running < queue.limit ? "running" : "queued", status: "Queued" });
            added = { id: String(queue.jobs.length - 1), duplicate: false };
          }
        }
        if (message.type === "parallel") queue.limit = message.limit;
        if (message.type === "fragments") queue.workers = message.workers;
        return { state: active === 1 ? state : null, queue: structuredClone(queue), added };
      } }
    };
    window.switchTestTab = () => { active = 2; listeners.activated({ tabId: 2, windowId: 1 }); };
  });
  await page.goto("http://video-saver.test/popup.html");
  await page.locator("#items li").waitFor();
  assert.equal(await page.locator("#items li").count(), 1);
  assert.equal((await page.locator("body").innerText()).includes("SECRET"), false);
  await page.locator("#items button").click();
  await page.locator("#queue li").waitFor();
  await page.getByRole("button", { name: "✓ Downloading" }).waitFor();
  await page.getByText("Downloading “A lecture” now.", { exact: false }).waitFor();
  assert.equal(await page.locator("#queue li.fresh").count(), 1);
  await page.getByRole("button", { name: "Add to queue" }).first().waitFor();
  await page.locator("#mode").selectOption("mp3");
  await page.locator("#items button").click();
  await page.locator("#queue li").nth(1).waitFor();
  await page.getByRole("button", { name: "✓ Queued #1" }).waitFor();
  await page.getByText("#1 in line", { exact: false }).waitFor();
  if (process.argv[4]) await page.screenshot({ path: process.argv[4].replace(/(\.png)?$/, "-added.png") });
  await page.getByRole("button", { name: "Add to queue" }).first().waitFor();
  await page.locator("#items button").click();
  await page.getByRole("button", { name: "✓ Already queued" }).waitFor();
  await page.getByText("is already in the queue (queued)", { exact: false }).waitFor();
  assert.equal(await page.locator("#queue li").count(), 2);
  await page.getByText("Queue settings", { exact: true }).click();
  await page.locator("#parallel").fill("12");
  await page.locator("#parallel").press("Tab");
  await page.getByText("Up to 24 fragment requests", { exact: false }).waitFor();
  await page.evaluate(() => window.switchTestTab());
  await page.getByText("Another tab", { exact: true }).waitFor();
  assert.equal(await page.locator("#items li").count(), 0);
  assert.equal(await page.locator("#queue li").count(), 2);
  if (process.argv[4]) await page.screenshot({ path: process.argv[4], fullPage: true });
  await page.setViewportSize({ width: 280, height: 900 });
  assert.equal(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth), true, "narrow panel must not scroll horizontally");
  assert.deepEqual(errors, []);
  console.log("Panel checks passed: queue, add feedback/duplicates, MP3, concurrency, tab switching, hidden tokens, narrow layout, no browser errors.");
} finally { await browser.close(); }
