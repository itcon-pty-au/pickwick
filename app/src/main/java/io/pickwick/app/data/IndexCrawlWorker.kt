package io.pickwick.app.data

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

/**
 * Background channel-index crawl, so the catalog fills in without the app
 * being open. WorkManager guarantees execution across process death and Doze;
 * each run does a bounded batch of pages and reschedules, keeping the per-run
 * network cost small and well under YouTube's throttling threshold.
 *
 * Only the master device does useful work here — every other device returns
 * success immediately (the master pushes the index to them over the LAN).
 */
class IndexCrawlWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val pairingStore = PairingStore(applicationContext)
        val config = ConfigStore(applicationContext).load()
        val me = pairingStore.deviceToken()

        // Master-only: a kid device or co-parent must never crawl YouTube.
        if (config.masterDeviceToken != me) return Result.success()

        val index = ChannelIndex(applicationContext)
        val crawler = IndexCrawler(YouTubeRepository(), index)
        val yt = YouTubeRepository()

        // Resolve the current whitelist into Sources (labels/avatars may be
        // absent — the index only needs id/url/kind, so a bare Source is fine).
        val sources = config.sources.map { e ->
            Source(e.id, e.url, e.label ?: e.id, null, e.kind, e.timeMultiplierPercent)
        }
        // Drop sources the whitelist no longer lists.
        val wanted = sources.map { it.id }.toSet()
        index.allStates().keys.filter { it !in wanted }.forEach { crawler.dropSource(it) }

        // Repair: builds before the exhaustion fix marked channels complete
        // after a single full page (the NewPipe no-continuation quirk). A
        // "complete" source sitting at roughly one page is suspect — unstick it
        // so the crawl resumes. Deliberately conservative: only channels, and
        // only when the count is at/below a couple of pages.
        sources.forEach { s ->
            val st = index.state(s.id)
            if (st != null && st.complete && st.count in 1..(2 * IndexCrawler.FULL_PAGE)) {
                android.util.Log.i("Pickwick", "un-sticking suspect source ${s.id} (${st.count} videos)")
                index.unmarkComplete(s.id)
            }
        }

        val incomplete = sources.filter { index.state(it.id)?.complete != true }
        if (incomplete.isEmpty()) return Result.success()

        // Bounded batch per run: PAGES_PER_RUN pages, round-robin from the
        // first incomplete source. ~17 pages per 500-video channel, so one
        // 15-minute run finishes a channel and starts the next.
        var pages = 0
        var failures = 0
        for (source in incomplete) {
            while (pages < PAGES_PER_RUN) {
                val more = runCatching { crawler.crawlOnce(source) }
                    .getOrElse {
                        android.util.Log.w("Pickwick", "index crawl failed", it)
                        failures++
                        false
                    }
                if (!more) break
                pages++
            }
            if (pages >= PAGES_PER_RUN) break
        }
        // Visible in logcat: confirms the worker ran, how much it did, and how
        // far along the catalog is — the "is it stuck?" answer without a debugger.
        android.util.Log.i(
            "Pickwick",
            "index crawl: $pages pages this run, " +
                "${sources.size - incomplete.size}/${sources.size} sources complete"
        )
        // Failed = we attempted a source and it threw without yielding a page —
        // the red dot in settings. A run that simply had nothing to do is green.
        index.recordRun(pages, failed = failures > 0 && pages == 0)
        return Result.success()
    }

    companion object {
        private const val WORK_NAME = "channel-index-crawl"

        /** Next scheduled run in epoch millis, or null if not scheduled. */
        suspend fun nextRunAt(context: Context): Long? {
            val infos = WorkManager.getInstance(context)
                .getWorkInfosForUniqueWork(WORK_NAME).get()
            return infos.firstOrNull()?.nextScheduleTimeMillis
                ?.takeIf { it > 0 && it != Long.MAX_VALUE }
        }

        /**
         * Pages per 15-minute run. At ~30/page, one run indexes ~600 videos.
         * 40 channels × 500 videos ≈ 34 runs ≈ a working day of background
         * time — versus ~12 hours of *app-open* time with the old in-app loop.
         */
        private const val PAGES_PER_RUN = 20

        /** 15 minutes is WorkManager's minimum periodic interval. */
        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<IndexCrawlWorker>(15, TimeUnit.MINUTES)
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build()
                )
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                // KEEP: rescheduling on every launch must not reset the period.
                ExistingPeriodicWorkPolicy.KEEP,
                request
            )
        }
    }
}
