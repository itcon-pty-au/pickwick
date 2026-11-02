package io.pickwick.app.ui

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import io.pickwick.app.data.ActiveProfileStore
import io.pickwick.app.data.ConfigEvents
import io.pickwick.app.data.ConfigStore
import io.pickwick.app.data.KidNotices
import io.pickwick.app.data.LanServer
import io.pickwick.app.data.PairingStore
import io.pickwick.app.data.ProfileNamespace
import io.pickwick.app.data.SessionGuard
import io.pickwick.app.data.WatchSync
import io.pickwick.app.data.Whitelist

/**
 * The process's one LAN server, owned by the process rather than an activity:
 * a parent's push, grant or index sync has to land whether or not Pickwick is
 * on screen, and a process woken by IndexCrawlWorker has no activity at all.
 */
object LanServerHolder {
    @Volatile
    var server: LanServer? = null
        private set

    /** Idempotent. Does disk reads and a socket bind — keep it off the main thread. */
    @Synchronized
    fun ensureStarted(context: Context) {
        if (server != null) return
        val appContext = context.applicationContext
        val configStore = ConfigStore(appContext)
        val pairingStore = PairingStore(appContext)
        val profileNs = ProfileNamespace(appContext)
        val activeProfiles = ActiveProfileStore(appContext)
        // Whose device this is: its dedicated assignment, else the last pick
        // that still names a real kid. A remembered pick can outlive its kid
        // (deleted on the phone, picker not used since) and suffixFor would
        // auto-register an orphan namespace, so it's validated against the
        // config it came with.
        fun kidHere(f: Whitelist): String? =
            if (f.profiles.isEmpty()) null
            else f.deviceProfiles[pairingStore.deviceToken()]
                ?: activeProfiles.activeId()?.takeIf { f.profile(it) != null }
        server = LanServer(
            configStore,
            grantHandler = { minutes, profileId ->
                // Route the grant to the named kid's guard; unnamed grants
                // (older admin phones) land on whoever this device shows.
                // An explicit id stays unvalidated on purpose — a grant can
                // arrive moments before the config push that introduces its
                // (new) kid.
                val here = kidHere(ConfigStore(appContext).load())
                val target = profileId ?: here
                SessionGuard(appContext, profileNs.suffixFor(target))
                    .grantExtraMinutes(minutes)
                // Only the kid the minutes belong to hears about them —
                // a grant aimed at their sibling is not their news.
                if (target == here) KidNotices.post(KidNotices.grant(minutes))
            },
            takeBackHandler = { minutes, profileId ->
                val here = kidHere(ConfigStore(appContext).load())
                val target = profileId ?: here
                SessionGuard(appContext, profileNs.suffixFor(target))
                    .takeBackExtraMinutes(minutes)
                if (target == here) KidNotices.post(KidNotices.takeBack(minutes))
            },
            pairingStore,
            statsProvider = { profileId -> io.pickwick.app.data.Stats.build(appContext, profileId) },
            watchStateProvider = { WatchSync.exportJson(appContext) },
            watchStateMerger = { json ->
                if (WatchSync.mergeJson(appContext, json)) {
                    // Refresh keep-watching / hearts on the TV right away.
                    ConfigEvents.onConfigChanged?.invoke()
                }
            },
            verdictsProvider = {
                io.pickwick.app.data.ScreeningStore(appContext)
                    .exportJson(ConfigStore(appContext).load().ai.rulesVersion)
            },
            verdictsMerger = { json ->
                val imported = io.pickwick.app.data.ScreeningStore(appContext)
                    .importJson(json, ConfigStore(appContext).load().ai.rulesVersion)
                // Imported ALLOWs reveal videos this device hadn't screened yet.
                if (imported > 0) ConfigEvents.onConfigChanged?.invoke()
            },
            indexStatusProvider = {
                io.pickwick.app.data.ChannelIndex(appContext).statusJson()
            },
            indexSourceProvider = { sourceId ->
                io.pickwick.app.data.ChannelIndex(appContext).exportSourceWithState(sourceId)
            },
            indexMerger = { sourceId, body ->
                io.pickwick.app.data.ChannelIndex(appContext).importSourceWithState(sourceId, body)
            },
            onConfigApplied = { before, after ->
                // The pill is the only thing that says a rule moved;
                // otherwise the kid finds out by hitting it. Judged on this
                // device's own kid, so a change to their sibling's rules
                // stays quiet here.
                val kid = kidHere(after)
                val fresh = after.limitsFor(kid)
                KidNotices.configChange(
                    before.limitsFor(kid), fresh,
                    SessionGuard(appContext, profileNs.suffixFor(kid)).remainingTodayMin(fresh)
                )?.let { KidNotices.post(it) }
            }
        ).also { it.start() }
    }
}

/**
 * Keeps a kid device's process out of the cached state, where Android 14's
 * freezer stops every thread — the accept loop included, so a parent's pushes
 * queued unanswered in the kernel backlog the moment the screensaver or the
 * Google TV home took over.
 *
 * Kid devices only: a parent phone is the one doing the pushing, and a
 * permanent notification there buys nothing. On a TV the notification is
 * effectively invisible. `specialUse` because no other type describes "LAN
 * endpoint for a paired admin device"; the app is sideloaded, so the Play
 * declaration the subtype property feeds never comes into it.
 */
class LanService : Service() {

    companion object {
        private const val CHANNEL_ID = "lan"
        private const val NOTIFICATION_ID = 43

        /** Caller must be allowed to start a foreground service (foreground, or boot/update). */
        fun startIfKid(context: Context) {
            if (PairingStore(context).role() != PairingStore.Role.KID) return
            runCatching {
                ContextCompat.startForegroundService(context, Intent(context, LanService::class.java))
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        if (Build.VERSION.SDK_INT >= 26) {
            val channel = NotificationChannel(
                CHANNEL_ID, "Parent connection", NotificationManager.IMPORTANCE_MIN
            ).apply {
                description = "Lets a parent's phone update this device while Pickwick is closed"
                setShowBadge(false)
            }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_sync_noanim)
            .setContentTitle("Pickwick")
            .setContentText("Ready for updates from a parent's phone")
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setOngoing(true)
            .setSilent(true)
            .build()
        val started = runCatching {
            ServiceCompat.startForeground(
                this, NOTIFICATION_ID, notification,
                if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
            )
        }.isSuccess
        // A sticky restart the system won't let promote (background-start
        // rules) must not linger as a plain started service; the next launch,
        // boot or update starts it properly.
        if (!started) {
            stopSelf()
            return START_NOT_STICKY
        }
        Thread({ LanServerHolder.ensureStarted(this) }, "lan-start").start()
        return START_STICKY
    }
}

/**
 * Boot and self-update both leave a kid TV with no Pickwick process until
 * someone opens the app — and no way for the parent's phone to reach it. Both
 * broadcasts are exempt from the background foreground-service start ban.
 */
class LanStartReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED -> {
                val pending = goAsync()
                Thread({
                    try {
                        LanService.startIfKid(context)
                    } finally {
                        pending.finish()
                    }
                }, "lan-boot").start()
            }
        }
    }
}
