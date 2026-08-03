package io.santatube.app

import io.santatube.app.data.OkHttpDownloader
import io.santatube.app.data.WhitelistParser
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.channel.ChannelInfo
import org.schabi.newpipe.extractor.channel.tabs.ChannelTabInfo
import org.schabi.newpipe.extractor.localization.ContentCountry
import org.schabi.newpipe.extractor.localization.Localization
import org.schabi.newpipe.extractor.stream.StreamInfoItem

/** Probe one problematic channel end-to-end: parse → info → tabs → videos. */
class SingleChannelProbeTest {

    @Test
    fun probe() = runBlocking<Unit> {
        val url = "https://www.youtube.com/channel/UCkd-BQZOkCVYk10rM_IUEcw"

        val parsed = WhitelistParser.parse(url)
        println("PARSED: ${parsed.sources}")
        check(parsed.sources.isNotEmpty()) { "Parser rejected the URL" }

        NewPipe.init(OkHttpDownloader(), Localization("en", "US"), ContentCountry("US"))
        val yt = ServiceList.YouTube
        val info = ChannelInfo.getInfo(yt, parsed.sources[0].url)
        println("CHANNEL: ${info.name}")
        println("TABS: " + info.tabs.joinToString { it.contentFilters.toString() })

        info.tabs.forEach { tab ->
            runCatching {
                val t = ChannelTabInfo.getInfo(yt, tab)
                val streams = t.relatedItems.filterIsInstance<StreamInfoItem>()
                println("TAB ${tab.contentFilters}: ${t.relatedItems.size} items (${streams.size} streams)")
                streams.take(3).forEach { println("   - ${it.name}") }
            }.onFailure { println("TAB ${tab.contentFilters} FAILED: ${it.javaClass.simpleName}: ${it.message}") }
        }

        // Fallback probe: the auto-generated uploads playlist (UC → UU).
        val uploadsPlaylist = "UU" + "UCkd-BQZOkCVYk10rM_IUEcw".removePrefix("UC")
        runCatching {
            val pl = org.schabi.newpipe.extractor.playlist.PlaylistInfo.getInfo(
                yt, "https://www.youtube.com/playlist?list=$uploadsPlaylist"
            )
            val streams = pl.relatedItems.filterIsInstance<StreamInfoItem>()
            println("UPLOADS-PLAYLIST $uploadsPlaylist: ${streams.size} streams")
            streams.take(3).forEach { println("   - ${it.name}") }
        }.onFailure { println("UPLOADS-PLAYLIST FAILED: ${it.javaClass.simpleName}: ${it.message}") }
    }
}
