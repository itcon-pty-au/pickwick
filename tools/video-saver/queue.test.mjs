import test from "node:test";
import assert from "node:assert/strict";
import { DownloadQueue } from "./queue.mjs";

function setup(saved) {
  let storage = structuredClone(saved);
  const ports = [];
  const api = {
    storage: { local: {
      get: async () => ({ downloadQueue: structuredClone(storage) }),
      set: async value => { storage = structuredClone(value.downloadQueue); },
    } },
    runtime: { connectNative() {
      let message, disconnect;
      const port = {
        onMessage: { addListener: fn => { message = fn; } },
        onDisconnect: { addListener: fn => { disconnect = fn; } },
        postMessage(request) { this.request = request; },
        disconnect() { disconnect(); },
        emit(value) { message(value); },
      };
      ports.push(port);
      return port;
    } },
  };
  return { queue: new DownloadQueue(api), ports, saved: () => storage };
}
const request = n => ({ title: `Episode ${n}`, url: `https://example.com/${n}/master.m3u8`, candidates: [] });

test("three parallel jobs, FIFO promotion, duplicate prevention and retained history", async () => {
  const { queue, ports, saved } = setup();
  for (let n = 0; n < 5; n++) await queue.add(request(n));
  assert.equal(ports.length, 3);
  assert.equal(queue.jobs.filter(job => job.state === "queued").length, 2);
  assert.equal((await queue.add(request(0))).duplicate, true);
  assert.equal(queue.jobs.length, 5);
  ports[0].emit({ done: true, path: "C:/Downloads/episode.mkv" });
  await queue.pending;
  await queue.pending;
  assert.equal(ports.length, 4);
  assert.equal(ports[3].request.title, "Episode 3");
  assert.equal(saved().jobs[0].state, "completed");
  assert.equal(saved().jobs[0].path, "C:/Downloads/episode.mkv");
});

test("progress is independent per job; disconnect releases slot and retry works", async () => {
  const { queue, ports } = setup();
  await queue.setLimit(1);
  const { id: first } = await queue.add(request(1));
  await queue.add(request(2));
  ports[0].emit({ status: "Downloading 50%" });
  await queue.pending;
  assert.equal(queue.jobs[0].status, "Downloading 50%");
  assert.equal(queue.jobs[1].status, "Queued");
  ports[0].disconnect();
  await queue.pending;
  assert.equal(queue.jobs[0].state, "failed");
  assert.equal(ports.length, 2);
  await queue.update(first, "retry");
  assert.equal(queue.jobs[0].state, "queued");
  ports[1].emit({ done: true });
  await queue.pending;
  assert.equal(ports.length, 3);
});

test("restart preserves history, marks running interrupted and starts only queued jobs", async () => {
  const first = setup();
  await first.queue.setLimit(1);
  await first.queue.add(request(1));
  await first.queue.add(request(2));
  const restarted = setup(first.saved());
  await restarted.queue.pending;
  assert.equal(restarted.queue.jobs[0].state, "interrupted");
  assert.equal(restarted.ports.length, 1);
  assert.equal(restarted.ports[0].request.title, "Episode 2");
});

test("remove queued jobs and clear history never remove running jobs", async () => {
  const { queue } = setup();
  await queue.setLimit(1);
  const { id: running } = await queue.add(request(1));
  const { id: waiting } = await queue.add(request(2));
  await assert.rejects(queue.update(running, "remove"));
  await queue.update(waiting, "remove");
  await queue.clearFinished();
  assert.equal(queue.jobs.length, 1);
  await assert.rejects(queue.setLimit(0));
});

test("more than four jobs, separate modes, fragment settings and restart", async () => {
  const { queue, ports, saved } = setup();
  await queue.setLimit(12);
  await queue.setWorkers(4);
  for (let n = 0; n < 14; n++) await queue.add(request(n));
  assert.equal(ports.length, 12);
  assert.equal(ports[0].request.fragment_workers, 4);
  await queue.add({ ...request(0), mode: "audio" });
  assert.equal(queue.jobs.length, 15);
  const restarted = setup(saved());
  await restarted.queue.pending;
  assert.equal(restarted.queue.limit, 12);
  assert.equal(restarted.queue.workers, 4);
  await assert.rejects(queue.setLimit(Infinity));
  await assert.rejects(queue.setLimit(1.5));
  await assert.rejects(queue.setWorkers(17));
});

test("MP3, manual pairing and high fragment counts reach the helper unrestricted", async () => {
  const { queue, ports } = setup();
  await queue.setWorkers(8);
  await queue.add({ ...request(1), mode: "mp3" });
  await queue.add({ ...request(2), partner: "https://example.com/2/audio.m3u8" });
  assert.equal(ports.length, 2);
  assert.equal(ports[0].request.mode, "mp3");
  assert.equal(ports[0].request.fragment_workers, 8);
  assert.equal(ports[1].request.partner, "https://example.com/2/audio.m3u8");
});
