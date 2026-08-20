package io.pickwick.app.data

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
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

        val index = ChannelIndex(applicationContext)
        val crawler = IndexCrawler(YouTubeRepository(), index)

        // Resolve the current whitelist into Sources (labels/avatars may be
        // absent — the index only needs id/url/kind, so a bare Source is fine).
        val sources = config.sources.map { e ->
            Source(e.id, e.url, e.label ?: e.id, null, e.kind, e.timeMultiplierPercent)
        }
        // Drop sources the whitelist no longer lists. On EVERY device, before
        // the master gate: pushes never propagate deletions, so a kid device
        // that skipped this would keep a removed channel's index forever.
        val wanted = sources.map { it.id }.toSet()
        index.allStates().keys.filter { it !in wanted }.forEach { crawler.dropSource(it) }

        // Master-only past here: a kid device or co-parent must never crawl.
        if (config.masterDeviceToken != me) return Result.success()

        val incomplete = sources.filter { index.state(it.id)?.complete != true }
        if (incomplete.isEmpty()) {
            // Still stamp the diagnostics line: a fully-crawled catalog should
            // read "ran, nothing to do", not "hasn't run since the last page".
            index.recordRun(0, failed = false)
            return Result.success()
        }

        // Bounded batch per run: PAGES_PER_RUN pages, round-robin from the
        // first incomplete source. ~17 pages per 500-video channel, so one
        // 15-minute run finishes a channel and starts the next.
        var pages = 0
        var attempts = 0
        var failures = 0
        for (source in incomplete) {
            while (pages < PAGES_PER_RUN) {
                // Spread the budget out instead of firing it as one burst —
                // see CRAWL_DELAY_MS. Paced per fetch ATTEMPT, not per stored
                // page: a parked source's probe returns false without counting
                // a page, and N parked sources would otherwise fire N
                // back-to-back page-1 fetches at the head of every run. First
                // attempt of the run goes immediately.
                if (attempts > 0) kotlinx.coroutines.delay(IndexCrawler.CRAWL_DELAY_MS)
                attempts++
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

        /** Running now, or waiting for [nextRunAt] (null = not scheduled). */
        data class RunSchedule(val running: Boolean, val nextRunAt: Long?)

        /**
         * Live schedule for the diagnostics row. A flow, not a one-shot read:
         * a crawl runs 5-6 minutes of every 15, so a snapshot taken while
         * expanded goes stale within the visit. WorkManager also reports
         * nextScheduleTimeMillis as Long.MAX_VALUE for every non-ENQUEUED
         * state — RUNNING included — so the time alone can't distinguish
         * "crawling right now" from "never scheduled"; the state can.
         */
        fun runSchedule(context: Context): Flow<RunSchedule> =
            WorkManager.getInstance(context)
                .getWorkInfosForUniqueWorkFlow(WORK_NAME)
                .map { infos ->
                    val info = infos.firstOrNull()
                    RunSchedule(
                        running = info?.state == WorkInfo.State.RUNNING,
                        nextRunAt = info?.nextScheduleTimeMillis
                            ?.takeIf { it > 0 && it != Long.MAX_VALUE }
                    )
                }

        /**
         * Pages per run. Sized against the system's ~10-minute cap on a single
         * worker execution, NOT the 15-minute period: at CRAWL_DELAY_MS spacing
         * plus fetch time this lands near 5-6 minutes, leaving real headroom
         * before the platform kills the run mid-page.
         *
         * The 15-minute period is WorkManager's floor and can't be shortened,
         * so throughput has to come from doing more per window rather than
         * more windows. Averaged over the period this is still only ~4
         * requests/minute — the burst is paced, and it uses the background
         * fetch lane, so the kid's browsing never queues behind it.
         */
        private const val PAGES_PER_RUN = 60

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
