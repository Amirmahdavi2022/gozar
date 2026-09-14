package xyz.jmc.gozar.core

import kotlin.test.Test
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The lines here are copied out of a real device diary rather than invented, which is the point:
 * these exact forty-odd lines per connection are what made the last two logs unreadable.
 */
class NoiseTest {

    private val timeout = "2026-09-14T10:39:20Z\t\u001B[33mWARN\u001B[0m\tSOCKS5 TCP error\t" +
        "{\"addr\": \"127.0.0.1:45480\", \"error\": \"connect error: timeout: no recent network activity\"}"
    private val rejected = "2026-09-14T10:38:47Z\t\u001B[33mWARN\u001B[0m\tSOCKS5 TCP error\t" +
        "{\"addr\": \"127.0.0.1:35340\", \"error\": \"dial error: rejected\"}"
    private val reset = "2026-09-14T10:36:30Z\t\u001B[33mWARN\u001B[0m\tSOCKS5 TCP error\t" +
        "{\"addr\": \"127.0.0.1:57220\", \"error\": \"read: connection reset by peer\"}"
    private val real = "2026-09-14T10:39:14Z\t\u001B[31mERROR\u001B[0m\tfailed to initialize client\t" +
        "{\"error\": \"connect error\"}"

    @Test
    fun `one socket giving up says nothing and is not printed`() {
        val noise = Noise()
        assertNull(noise.consume(timeout, 1_000))
        assertNull(noise.consume(rejected, 2_000))
    }

    @Test
    fun `a failure that is not about one socket always gets through, cleaned up`() {
        val kept = Noise().consume(real, 1_000)
        assertTrue(kept != null && kept.contains("failed to initialize client"), "kept: $kept")
        assertTrue(!kept!!.contains("\u001B"), "escape codes stripped")
        assertTrue(!kept.contains("2026-09-14T"), "the core's own timestamp dropped")
    }

    @Test
    fun `the tally names how many and of what`() {
        val noise = Noise()
        noise.consume(timeout, 1_000)
        noise.consume(rejected, 2_000)
        val line = noise.consume(reset, 70_000)
        assertTrue(line != null, "a tally is due once the window is up")
        assertTrue(line!!.contains("3 connections gave up"), line)
        assertTrue(line.contains("1 timed out"), line)
        assertTrue(line.contains("1 refused by the endpoint"), line)
        assertTrue(line.contains("1 reset"), line)
    }

    @Test
    fun `a busy minute is reported early rather than hidden`() {
        val noise = Noise()
        var last: String? = null
        for (i in 1..40) last = noise.consume(timeout, 1_000L + i)
        assertTrue(last != null && last.contains("40 connections"), "last: $last")
    }

    @Test
    fun `what is left over is flushed when the core stops`() {
        val noise = Noise()
        noise.consume(timeout, 1_000)
        assertTrue(noise.flush(2_000)!!.contains("1 connections gave up"))
        assertNull(noise.flush(3_000), "nothing left the second time")
    }
}
