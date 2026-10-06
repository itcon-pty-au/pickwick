package io.pickwick.app

import io.pickwick.app.data.PodcastFeed
import io.pickwick.app.data.PodcastParser
import io.pickwick.app.data.PodcastPaths
import io.pickwick.app.data.Video
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PodcastParserTest {

    private val feed = """
        <?xml version="1.0" encoding="UTF-8"?>
        <rss version="2.0" xmlns:itunes="http://www.itunes.com/dtds/podcast-1.0.dtd">
          <channel>
            <title>Story Time</title>
            <image><url>https://example.com/rss-cover.png</url><title>Not the show title</title></image>
            <itunes:image href="https://example.com/cover.jpg"/>
            <item>
              <title>Episode 2: The Fox</title>
              <guid isPermaLink="false">ep-2</guid>
              <pubDate>Tue, 01 Sep 2026 08:00:00 +0000</pubDate>
              <itunes:duration>1:02:03</itunes:duration>
              <description><![CDATA[<p>A fox &amp; a <b>hen</b>.</p>]]></description>
              <enclosure url="https://example.com/cover2.jpg" type="image/jpeg" length="1"/>
              <enclosure url="https://cdn.example.com/ep2.mp3?track=1" type="audio/mpeg" length="123"/>
            </item>
            <item>
              <title>Episode 1</title>
              <itunes:image href="https://example.com/ep1.jpg"/>
              <itunes:duration>754</itunes:duration>
              <enclosure url="http://cdn.example.com/ep1.m4a" type="audio/x-m4a" length="123"/>
            </item>
            <item>
              <title>Trailer without audio</title>
            </item>
          </channel>
        </rss>
    """.trimIndent()

    @Test fun parsesChannelAndEpisodes() {
        val c = PodcastParser.parse(feed.byteInputStream())
        assertEquals("Story Time", c.title)
        assertEquals("https://example.com/cover.jpg", c.imageUrl)
        assertEquals(2, c.episodes.size)
        val ep2 = c.episodes[0]
        assertEquals("Episode 2: The Fox", ep2.title)
        assertEquals("https://cdn.example.com/ep2.mp3?track=1", ep2.audioUrl)
        assertEquals(3723L, ep2.durationSeconds)
        assertEquals("A fox & a hen .", ep2.description)
        assertEquals("https://example.com/cover.jpg", ep2.imageUrl)
        assertTrue(ep2.publishedMillis > 0)
        assertEquals(754L, c.episodes[1].durationSeconds)
        assertEquals("https://example.com/ep1.jpg", c.episodes[1].imageUrl)
    }

    @Test fun episodeKeyFollowsGuidNotAudioUrl() {
        val a = PodcastParser.parse(feed.byteInputStream()).episodes[0]
        val moved = feed.replace("ep2.mp3?track=1", "ep2.mp3?track=2")
        val b = PodcastParser.parse(moved.byteInputStream()).episodes[0]
        assertEquals(a.key, b.key)
        assertTrue(a.key.matches(Regex("[0-9a-f]{16}")))
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsNonFeeds() {
        PodcastParser.parse("<html><body>hi</body></html>".byteInputStream())
    }

    @Test fun refusesDoctypes() {
        val xxe = """<?xml version="1.0"?><!DOCTYPE rss [<!ENTITY x SYSTEM "file:///etc/passwd">]>
            <rss><channel><title>&x;</title></channel></rss>"""
        assertTrue(runCatching { PodcastParser.parse(xxe.byteInputStream()) }.isFailure)
    }

    @Test fun durations() {
        assertEquals(90L, PodcastParser.parseDuration("1:30"))
        assertEquals(42L, PodcastParser.parseDuration("42"))
        assertEquals(0L, PodcastParser.parseDuration("about an hour"))
    }

    @Test fun pathsRoundTrip() {
        val url = PodcastPaths.url("feed-1", "0123456789abcdef")
        assertEquals("feed-1" to "0123456789abcdef", PodcastPaths.parse(url))
        assertEquals("pod-0123456789abcdef", Video(url, "t", "c", null, 0).videoId)
        assertNull(PodcastPaths.parse("pickwick://podcast/feed-1/../x"))
    }

    @Test fun feedUrlValidation() {
        assertTrue(PodcastFeed.isFeedUrl("https://feeds.example.com/show.xml"))
        assertFalse(PodcastFeed.isFeedUrl("file:///sdcard/show.xml"))
        assertFalse(PodcastFeed.isFeedUrl("not a url"))
    }

    @Test fun configRoundTripAndFingerprint() {
        val plain = io.pickwick.app.data.Whitelist(emptyList(), emptySet())
        val withPod = plain.copy(podcasts = listOf(PodcastFeed("feed-1", "https://example.com/rss", "Story Time", setOf("kid"))))
        val json = io.pickwick.app.data.ConfigStore.toJson(withPod)
        assertEquals(withPod.podcasts, io.pickwick.app.data.ConfigStore.fromJson(json).podcasts)
        // Absent key: configs without podcasts keep their pre-podcast hash.
        assertFalse(io.pickwick.app.data.ConfigStore.toJson(plain).contains("podcasts"))
        assertTrue(io.pickwick.app.data.ConfigStore.fingerprint(plain) != io.pickwick.app.data.ConfigStore.fingerprint(withPod))
    }

    @Test fun queuedPodcastsDrainNothing() {
        val episode = Video(PodcastPaths.url("feed-1", "0123456789abcdef"), "Ep", "Story Time", null, 600)
        val video = Video("https://www.youtube.com/watch?v=abcdefghijk", "Vid", "Some Channel", null, 600)
        assertEquals(listOf(0, 100), io.pickwick.app.data.queuePercents(listOf(episode, video), emptyList()))
    }
}
