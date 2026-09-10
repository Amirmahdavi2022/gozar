package xyz.jmc.gozar.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Every input here is a real line out of a log from a real phone, not something invented to pass.
 * That matters: the shapes the core prints are nothing like what you would guess.
 */
class RedactTest {

    @Test
    fun `an endpoint host is removed`() {
        val line = "[Error] [3679515138] transport/internet: failed to resolve ip > " +
            "app/dns: returning nil for domain c.mrtbkhpointspeed.ir > rcode: 3"
        val out = Redact.line(line)
        assertFalse(out.contains("mrtbkhpointspeed"))
        assertTrue(out.contains("rcode: 3"), "the useful half has to survive: $out")
    }

    @Test
    fun `a whole uri goes, credential and all`() {
        val out = Redact.line("up on vless://d0f8a1b2@203.0.113.9:443?security=reality directly in 812ms")
        assertFalse(out.contains("203.0.113.9"))
        assertFalse(out.contains("d0f8a1b2"))
        assertTrue(out.contains("812ms"))
    }

    @Test
    fun `our own probe targets stay readable`() {
        assertEquals(
            "probe to www.gstatic.com failed: SocketTimeoutException: Read timed out",
            Redact.line("probe to www.gstatic.com failed: SocketTimeoutException: Read timed out"),
        )
        assertTrue(Redact.line("from DNS accepted tcp:1.1.1.1:53").contains("1.1.1.1"))
    }

    @Test
    fun `timestamps and versions are not addresses`() {
        val line = "core: 2026/09/10 16:48:17.463753 [Warning] core: Xray 26.3.27 started"
        assertEquals(line, Redact.line(line))
    }

    @Test
    fun `a file path keeps its package name`() {
        val out = Redact.line("Reading config: /data/user/0/xyz.jmc.gozar/files/round.json")
        assertTrue(out.contains("xyz.jmc.gozar"), out)
    }

    @Test
    fun `an internal session id is noise and goes`() {
        val out = Redact.line("[xray.system.a702f4f5-7cd5-4312-a79e-36443b28b698 -> direct]")
        assertFalse(out.contains("a702f4f5"))
    }

    @Test
    fun `an address with no letters after it is still caught`() {
        assertFalse(Redact.line("connect 84.32.61.87:443 failed").contains("84.32.61.87"))
    }

    @Test
    fun `a block keeps its line count`() {
        val block = "one 10.20.30.40\ntwo example.org\nthree"
        val out = Redact.block(block)
        assertEquals(3, out.lines().size)
        assertFalse(out.contains("example.org"))
    }
}
