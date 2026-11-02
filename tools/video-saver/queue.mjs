export class DownloadQueue {
  constructor(api) {
    this.api = api;
    this.jobs = [];
    this.limit = 3;
    this.workers = 2;
    this.ports = new Map();
    this.pending = this.initialize();
  }

  async initialize() {
    const saved = (await this.api.storage.local.get("downloadQueue")).downloadQueue;
    this.jobs = saved?.jobs || [];
    this.limit = Number.isSafeInteger(saved?.limit) && saved.limit > 0 ? saved.limit : 3;
    this.workers = Number.isInteger(saved?.workers) && saved.workers >= 1 && saved.workers <= 16 ? saved.workers : 2;
    for (const job of this.jobs) {
      if (job.state === "running") {
        job.state = "interrupted";
        job.status = "Browser or extension restarted. Retry to start again.";
      }
    }
    await this.pump();
  }

  serial(task) {
    const result = this.pending.then(task);
    this.pending = result.catch(() => {});
    return result;
  }

  snapshot() { return { jobs: this.jobs, limit: this.limit, workers: this.workers }; }
  persist() { return this.api.storage.local.set({ downloadQueue: this.snapshot() }); }

  add(request) {
    return this.serial(async () => {
      const existing = this.jobs.find(job => ["queued", "running"].includes(job.state)
        && job.request.url === request.url && job.request.partner === request.partner && (job.request.mode || "video") === (request.mode || "video"));
      if (existing) return { id: existing.id, duplicate: true };
      if (this.jobs.length >= 200) throw new Error("Queue history is full. Clear finished downloads first.");
      const job = { id: crypto.randomUUID(), title: request.title || new URL(request.url).hostname, request: structuredClone(request), state: "queued", status: "Queued", createdAt: Date.now() };
      this.jobs.push(job);
      await this.pump();
      return { id: job.id, duplicate: false };
    });
  }

  update(id, action) {
    return this.serial(async () => {
      const job = this.jobs.find(entry => entry.id === id);
      if (!job) throw new Error("Download no longer exists.");
      if (action === "retry" && ["failed", "interrupted"].includes(job.state)) {
        job.state = "queued";
        job.status = "Queued";
        delete job.path;
      } else if (action === "remove" && job.state !== "running") {
        this.jobs = this.jobs.filter(entry => entry.id !== id);
      } else throw new Error("That action is unavailable for this download.");
      await this.pump();
    });
  }

  setLimit(limit) {
    return this.serial(async () => {
      if (!Number.isSafeInteger(limit) || limit < 1) throw new Error("Choose a positive whole number of parallel downloads.");
      this.limit = limit;
      await this.pump();
    });
  }

  setWorkers(workers) {
    return this.serial(async () => {
      if (!Number.isInteger(workers) || workers < 1 || workers > 16) throw new Error("Choose 1 to 16 fragment connections per download.");
      this.workers = workers;
      await this.persist();
    });
  }

  clearFinished() {
    return this.serial(async () => {
      this.jobs = this.jobs.filter(job => ["queued", "running"].includes(job.state));
      await this.persist();
    });
  }

  async pump() {
    for (const job of this.jobs) {
      if (this.ports.size >= this.limit) break;
      if (job.state !== "queued") continue;
      job.state = "running";
      job.status = "Connecting to local merger...";
      // Persist ownership before launching; never silently restart an interrupted job.
      await this.persist();
      try {
        const port = this.api.runtime.connectNative("io.pickwick.video_saver");
        this.ports.set(job.id, port);
        port.onMessage.addListener(message => {
          void this.serial(async () => {
            if (this.ports.get(job.id) !== port) return;
            if (message.done || message.error) {
              job.state = message.done ? "completed" : "failed";
              job.status = message.error || "Completed";
              if (message.path) job.path = message.path;
              this.ports.delete(job.id);
              port.disconnect();
              await this.pump();
            } else {
              job.status = message.status || job.status;
              await this.persist();
            }
          });
        });
        port.onDisconnect.addListener(() => {
          const error = this.api.runtime.lastError?.message;
          void this.serial(async () => {
            if (this.ports.get(job.id) !== port) return;
            this.ports.delete(job.id);
            job.state = "failed";
            job.status = error || "Local merger disconnected. Retry this download.";
            await this.pump();
          });
        });
        port.postMessage({ ...job.request, fragment_workers: this.workers });
      } catch (error) {
        const port = this.ports.get(job.id);
        this.ports.delete(job.id);
        port?.disconnect();
        job.state = "failed";
        job.status = error.message;
      }
    }
    await this.persist();
  }
}
