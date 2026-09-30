const { chromium } = require('playwright');
const path = require('node:path');
const os = require('node:os');
const fs = require('node:fs');

(async () => {
  const extension = path.resolve(__dirname, '..');
  const profile = fs.mkdtempSync(path.join(os.tmpdir(), 'pickwick-brave-test-'));
  const context = await chromium.launchPersistentContext(profile, {
    executablePath: 'C:\\Program Files\\BraveSoftware\\Brave-Browser\\Application\\brave.exe',
    headless: true,
    args: [`--disable-extensions-except=${extension}`, `--load-extension=${extension}`],
  });
  try {
    const worker = context.serviceWorkers()[0] || await context.waitForEvent('serviceworker', { timeout: 20000 });
    console.log('Worker:', worker.url());
    const result = await worker.evaluate(() => new Promise(resolve => {
      const port = chrome.runtime.connectNative('io.pickwick.video_saver');
      const timeout = setTimeout(() => { port.disconnect(); resolve({ error: 'Timed out' }); }, 15000);
      port.onMessage.addListener(message => { clearTimeout(timeout); resolve(message); port.disconnect(); });
      port.onDisconnect.addListener(() => { clearTimeout(timeout); resolve({ connectionError: chrome.runtime.lastError?.message }); });
      port.postMessage({ url: 'file:///not-allowed' });
    }));
    console.log(JSON.stringify(result));
    if (!result.error?.includes('Only HTTP(S)')) process.exitCode = 1;
    const popupUrl = worker.url().replace('background.js', 'popup.html');
    const page = await context.newPage();
    await page.goto(popupUrl);
    const added = await page.evaluate(async () => {
      const [tab] = await chrome.tabs.query({ active: true, currentWindow: true });
      const responses = [];
      // Invalid local URLs exercise the queue/native error path without remote downloads.
      for (let n = 1; n <= 3; n++) {
        await chrome.storage.session.set({ [`tab:${tab.id}`]: { title: `Queue test episode ${n}`, items: [{ url: `file:///episode${n}.mp4`, frameId: 0 }] } });
        responses.push(await chrome.runtime.sendMessage({ type: 'download', tabId: tab.id, index: 0 }));
      }
      return responses.map(response => response.error || 'queued');
    });
    console.log('Queue adds:', added);
    await page.waitForFunction(() => document.querySelectorAll('#queue li').length === 3 && document.querySelector('#queue').textContent.includes('Only HTTP(S)'));
    const anotherTab = await context.newPage();
    await anotherTab.goto(popupUrl);
    await anotherTab.waitForFunction(() => document.querySelectorAll('#queue li').length === 3);
    await anotherTab.setViewportSize({ width: 360, height: 760 });
    await anotherTab.screenshot({ path: path.join(profile, 'queue-popup.png'), fullPage: true });
    const overflow = await anotherTab.evaluate(() => document.documentElement.scrollWidth > innerWidth);
    if (overflow) throw new Error('Popup overflows horizontally');
    console.log('Queue visible in second tab; screenshot:', path.join(profile, 'queue-popup.png'));
  } finally {
    await context.close();
  }
})().catch(error => { console.error(error); process.exitCode = 1; });
