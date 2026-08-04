package io.santatube.app

import io.santatube.app.data.OkHttpDownloader
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.channel.ChannelInfo
import org.schabi.newpipe.extractor.channel.tabs.ChannelTabInfo
import org.schabi.newpipe.extractor.channel.tabs.ChannelTabs
import org.schabi.newpipe.extractor.localization.ContentCountry
import org.schabi.newpipe.extractor.localization.Localization
import org.schabi.newpipe.extractor.stream.StreamInfo

/** Runs the real extraction path on the JVM so failures are visible with full stack traces. */
class ExtractorSmokeTest {

    @Test
    fun channelUploads() = runBlocking {
        NewPipe.init(OkHttpDownloader(), Localization("en", "US"), ContentCountry("US"))
        val yt = ServiceList.YouTube

        val info = ChannelInfo.getInfo(yt, "https://www.youtube.com/channel/UC4a-Gbdw7vOaccHmFo40b9g")
        println("CHANNEL: ${info.name}")
        println("TABS: " + info.tabs.joinToString { it.contentFilters.toString() })

        val videosTab = info.tabs.firstOrNull { ChannelTabs.VIDEOS in it.contentFilters }
        checkNotNull(videosTab) { "No videos tab found — tabs were: ${info.tabs.map { it.contentFilters }}" }

        val tab = ChannelTabInfo.getInfo(yt, videosTab)
        println("ITEMS: ${tab.relatedItems.size}")
        tab.relatedItems.take(5).forEach { println(" - ${it.name}") }
        check(tab.relatedItems.isNotEmpty()) { "Videos tab resolved but returned 0 items" }
    }

    /** The URL forms parents will paste must actually resolve against YouTube. */
    @Test
    fun resolvesUrlForms() = runBlocking {
        NewPipe.init(OkHttpDownloader(), Localization("en", "US"), ContentCountry("US"))
        val yt = ServiceList.YouTube
        val urls = listOf(
            "https://www.youtube.com/user/crashcoursekids",
            "https://www.youtube.com/@veritasium",
            "https://www.youtube.com/channel/UC4a-Gbdw7vOaccHmFo40b9g"
        )
        val failures = mutableListOf<String>()
        urls.forEach { url ->
            runCatching { ChannelInfo.getInfo(yt, url) }
                .onSuccess { println("OK   $url -> ${it.name} (${it.id})") }
                .onFailure {
                    println("FAIL $url -> ${it.javaClass.simpleName}: ${it.message}")
                    failures += url
                }
        }
        check(failures.isEmpty()) { "Unresolvable URL forms: $failures" }
    }

    /**
     * The playback path — the first thing users feel when YouTube breaks, and the
     * regression check to run after an extractor version bump (see README runbook).
     * Uses "Me at the zoo", the oldest video on YouTube, as a stable target.
     */
    @Test
    fun resolvesStream() = runBlocking {
        NewPipe.init(OkHttpDownloader(), Localization("en", "US"), ContentCountry("US"))
        val yt = ServiceList.YouTube

        val info = StreamInfo.getInfo(yt, "https://www.youtube.com/watch?v=jNQXAC9IVRw")
        println("STREAM: ${info.name} (age limit ${info.ageLimit})")

        val muxed = info.videoStreams.filter { !it.isVideoOnly && it.content != null }
        val videoOnly = info.videoOnlyStreams.filter { it.content != null }
        val audio = info.audioStreams.filter { it.content != null }
        println("muxed=${muxed.size} videoOnly=${videoOnly.size} audio=${audio.size}")

        // Same combinations resolvePlayback accepts: video+audio merged, or a muxed fallback.
        check(muxed.isNotEmpty() || (videoOnly.isNotEmpty() && audio.isNotEmpty())) {
            "No playable stream combination returned"
        }
        val sample = (muxed + videoOnly + audio).first()
        check(sample.content.startsWith("http")) {
            "Stream content is not a URL: ${sample.content?.take(80)}"
        }
    }

    @Test
    fun searchUploaderUrls() = runBlocking {
        NewPipe.init(OkHttpDownloader(), Localization("en", "US"), ContentCountry("US"))
        val yt = ServiceList.YouTube
        val info = org.schabi.newpipe.extractor.search.SearchInfo.getInfo(
            yt, yt.searchQHFactory.fromQuery("ted")
        )
        val streams = info.relatedItems
            .filterIsInstance<org.schabi.newpipe.extractor.stream.StreamInfoItem>()
        println("RESULTS: ${streams.size}")
        streams.take(10).forEach { println("URL=${it.uploaderUrl} | ${it.uploaderName} | ${it.name}") }
        check(streams.isNotEmpty())
    }
}
