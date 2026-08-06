package io.pickwick.app.data

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import io.pickwick.app.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONObject
import java.io.File

/** Live "a newer build exists" signal, for the dot on the settings gear. */
object UpdateEvents {
    val pending = kotlinx.coroutines.flow.MutableStateFlow<Updater.UpdateInfo?>(null)
}

/**
 * Self-update over GitHub: `version.json` in the repo names the latest build and
 * where its APK lives (a GitHub Release asset). If it's newer than this install,
 * the parent can download it and the system installer takes over.
 */
class Updater(private val context: Context) {

    private val prefs =
        context.applicationContext.getSharedPreferences("updater", Context.MODE_PRIVATE)

    data class UpdateInfo(
        val versionCode: Int,
        val versionName: String,
        val apkUrl: String
    )

    /** Null when up to date (or the update URL isn't configured / reachable). */
    suspend fun check(): UpdateInfo? = withContext(Dispatchers.IO) {
        val url = BuildConfig.UPDATE_MANIFEST_URL
        if (url.isBlank() || "CHANGE_ME" in url) return@withContext null
        runCatching {
            val busted = url + (if ('?' in url) "&" else "?") + "cb=" + System.currentTimeMillis()
            val body = Http.client.newCall(Request.Builder().url(busted).build())
                .execute().use { resp ->
                    if (!resp.isSuccessful) return@withContext null
                    resp.body?.string().orEmpty()
                }
            val json = JSONObject(body)
            val code = json.getInt("versionCode")
            // Remember what the manifest said whether newer or not: [pending]
            // filters against the running build, so a cached "update available"
            // clears itself on the first check after installing it. A failed
            // fetch never reaches here — the cached answer survives to retry.
            prefs.edit()
                .putLong("last_check_at", System.currentTimeMillis())
                .putInt("manifest_code", code)
                .putString("manifest_name", json.optString("versionName", code.toString()))
                .putString("manifest_url", json.optString("apkUrl"))
                .apply()
            UpdateEvents.pending.value = pending()
            if (code <= BuildConfig.VERSION_CODE) null
            else UpdateInfo(
                versionCode = code,
                versionName = json.optString("versionName", code.toString()),
                apkUrl = json.getString("apkUrl")
            )
        }.getOrNull()
    }

    /** The cached manifest answer, if it's still newer than this install. */
    fun pending(): UpdateInfo? {
        val code = prefs.getInt("manifest_code", 0)
        if (code <= BuildConfig.VERSION_CODE) return null
        val apkUrl = prefs.getString("manifest_url", null)?.ifBlank { null } ?: return null
        return UpdateInfo(code, prefs.getString("manifest_name", null) ?: code.toString(), apkUrl)
    }

    /**
     * Launch-time path: at most one real fetch a day, then publish the cached
     * answer so the settings-gear dot shows without any network at all.
     */
    suspend fun autoCheck() {
        val stale = System.currentTimeMillis() - prefs.getLong("last_check_at", 0L) >= CHECK_EVERY_MS
        if (stale) check() else UpdateEvents.pending.value = pending()
    }

    /** Downloads the APK, then hands it to the system installer (user confirms). */
    suspend fun downloadAndInstall(info: UpdateInfo) = withContext(Dispatchers.IO) {
        val dir = File(context.cacheDir, "updates").apply { mkdirs() }
        val apk = File(dir, "Pickwick-update.apk")
        Http.client.newCall(Request.Builder().url(info.apkUrl).build()).execute().use { resp ->
            check(resp.isSuccessful) { "Download failed: HTTP ${resp.code}" }
            apk.outputStream().use { out ->
                resp.body?.byteStream()?.copyTo(out) ?: error("Empty download")
            }
        }
        val uri = FileProvider.getUriForFile(
            context, "${context.packageName}.fileprovider", apk
        )
        context.startActivity(
            Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        )
    }

    private companion object {
        const val CHECK_EVERY_MS = 24 * 60 * 60 * 1000L
    }
}
