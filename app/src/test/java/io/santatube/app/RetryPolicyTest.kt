package io.santatube.app

import io.santatube.app.data.YouTubeRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.schabi.newpipe.extractor.exceptions.ContentNotAvailableException
import org.schabi.newpipe.extractor.exceptions.PaidContentException
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException
import java.io.IOException

/** Pure-JVM tests for the extractor retry policy — no network, delays injected. */
class RetryPolicyTest {

    private val sleeps = mutableListOf<Long>()
    private val noSleep: suspend (Long) -> Unit = { sleeps += it }

    @Test
    fun transientErrorRetriedWithEscalatingBackoff() = runBlocking {
        var calls = 0
        try {
            YouTubeRepository.retrying<Unit>("t", noSleep) {
                calls++
                throw IOException("flaky")
            }
            fail("Expected IOException")
        } catch (e: IOException) {
            // expected after retries are exhausted
        }
        assertEquals(3, calls)
        assertEquals(2, sleeps.size)
        assertTrue("first backoff ${sleeps[0]} outside 800ms ±20%", sleeps[0] in 640..960)
        assertTrue("second backoff ${sleeps[1]} outside 2500ms ±20%", sleeps[1] in 2000..3000)
    }

    @Test
    fun permanentErrorFailsFast() = runBlocking {
        var calls = 0
        try {
            YouTubeRepository.retrying<Unit>("t", noSleep) {
                calls++
                throw ContentNotAvailableException("gone")
            }
            fail("Expected ContentNotAvailableException")
        } catch (e: ContentNotAvailableException) {
        }
        assertEquals(1, calls)
        assertEquals(0, sleeps.size)
    }

    @Test
    fun permanentSubclassFailsFast() = runBlocking {
        var calls = 0
        try {
            YouTubeRepository.retrying<Unit>("t", noSleep) {
                calls++
                throw PaidContentException("members only")
            }
            fail("Expected PaidContentException")
        } catch (e: PaidContentException) {
        }
        assertEquals(1, calls)
        assertEquals(0, sleeps.size)
    }

    @Test
    fun recaptchaRetriedOnceAfterLongPause() = runBlocking {
        var calls = 0
        try {
            YouTubeRepository.retrying<Unit>("t", noSleep) {
                calls++
                throw ReCaptchaException("rate limited", "https://youtube.com")
            }
            fail("Expected ReCaptchaException")
        } catch (e: ReCaptchaException) {
        }
        assertEquals(2, calls)
        assertEquals(1, sleeps.size)
        assertTrue("recaptcha backoff ${sleeps[0]} outside 4000ms ±20%", sleeps[0] in 3200..4800)
    }

    @Test
    fun cancellationPropagatesImmediately() = runBlocking {
        var calls = 0
        try {
            YouTubeRepository.retrying<Unit>("t", noSleep) {
                calls++
                throw CancellationException("scope left")
            }
            fail("Expected CancellationException")
        } catch (e: CancellationException) {
        }
        assertEquals(1, calls)
        assertEquals(0, sleeps.size)
    }

    @Test
    fun successOnSecondAttemptReturnsValue() = runBlocking {
        var calls = 0
        val result = YouTubeRepository.retrying("t", noSleep) {
            calls++
            if (calls == 1) throw IOException("flaky") else "ok"
        }
        assertEquals("ok", result)
        assertEquals(2, calls)
        assertEquals(1, sleeps.size)
    }
}
