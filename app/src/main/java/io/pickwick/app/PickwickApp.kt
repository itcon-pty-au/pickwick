package io.pickwick.app

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.disk.DiskCache
import coil.memory.MemoryCache
import io.pickwick.app.data.OkHttpDownloader
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.localization.ContentCountry
import org.schabi.newpipe.extractor.localization.Localization

class PickwickApp : Application(), ImageLoaderFactory {

    override fun onCreate() {
        super.onCreate()
        NewPipe.init(OkHttpDownloader(), Localization("en", "US"), ContentCountry("US"))
        // Thumbnail/playback quality targets from connection + device type.
        io.pickwick.app.data.QualityTargets.configure(this)
    }

    /** Coil singleton: aggressive thumbnail caching — YouTube thumb URLs are immutable. */
    override fun newImageLoader(): ImageLoader =
        ImageLoader.Builder(this)
            .okHttpClient(io.pickwick.app.data.Http.client)
            .crossfade(true)
            .respectCacheHeaders(false)
            .memoryCache {
                MemoryCache.Builder(this).maxSizePercent(0.25).build()
            }
            .diskCache {
                DiskCache.Builder()
                    .directory(cacheDir.resolve("thumbnails"))
                    .maxSizeBytes(256L * 1024 * 1024)
                    .build()
            }
            .apply { if (BuildConfig.DEBUG) logger(coil.util.DebugLogger()) }
            .build()
}
