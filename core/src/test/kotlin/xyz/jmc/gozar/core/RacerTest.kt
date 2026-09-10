package xyz.jmc.gozar.core

import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private class FakeEngine(
    override val name: String,
    override val shape: Shape,
    private val startDelayMs: Long = 0,
    private val failToStart: Boolean = false,
    override val needsBootstrap: Boolean = false,
    var carriesTraffic: Boolean = true,
) : Engine {
    var stopped = false
    var port = 0

    override suspend fun start(): Session {
        delay(startDelayMs)
        if (failToStart) error("$name refused to start")
        port = name.hashCode().and(0xffff).coerceAtLeast(1024)
        return Session(port, name, shape)
    }

    override fun stop() { stopped = true }
}

private class FakeProbe(private val engines: List<FakeEngine>) : Prober {
    override suspend fun through(socksPort: Int, timeoutMs: Int): Boolean =
        engines.firstOrNull { it.port == socksPort }?.carriesTraffic ?: false
}

class RacerTest {

    @Test
    fun `an engine that starts but carries nothing does not win`() = runTest {
        // The failure this is guarding against is the one users actually hit:
        // the app says connected and then nothing loads.
        val dead = FakeEngine("dead", Shape.WEBRTC, carriesTraffic = false)
        val alive = FakeEngine("alive", Shape.HTTPS, startDelayMs = 3_000)
        val probe = FakeProbe(listOf(dead, alive))

        val racer = Racer(listOf(dead, alive), Scoreboard(MemoryStore()), probe, backgroundScope)
        val session = racer.connect(NetworkId.UNKNOWN)

        assertNotNull(session)
        assertEquals("alive", session.engine)
        assertTrue(dead.stopped, "a dead engine should be torn down, not left running")
    }

    @Test
    fun `a standby is only kept when it fails differently`() = runTest {
        // Two engines of the same shape die to the same DPI rule, so holding one
        // behind the other buys nothing but battery.
        val first = FakeEngine("first", Shape.WEBRTC)
        val sameShape = FakeEngine("same", Shape.WEBRTC, startDelayMs = 2_000)
        val probe = FakeProbe(listOf(first, sameShape))

        val racer = Racer(listOf(first, sameShape), Scoreboard(MemoryStore()), probe, backgroundScope)
        racer.connect(NetworkId.UNKNOWN)

        assertTrue(sameShape.stopped, "same-shape runner-up should not be held warm")
    }

    @Test
    fun `nothing working returns nothing rather than a fake session`() = runTest {
        val a = FakeEngine("a", Shape.WEBRTC, failToStart = true)
        val b = FakeEngine("b", Shape.HTTPS, carriesTraffic = false)

        val racer = Racer(listOf(a, b), Scoreboard(MemoryStore()), FakeProbe(listOf(a, b)), backgroundScope)
        assertNull(racer.connect(NetworkId.UNKNOWN))
    }
}

class ScoreboardTest {

    @Test
    fun `what worked here recently goes first next time`() = runTest {
        var now = 1_000_000L
        val board = Scoreboard(MemoryStore(), clock = { now })

        val slow = FakeEngine("slow", Shape.WEBRTC)
        val fast = FakeEngine("fast", Shape.HTTPS)
        val untried = FakeEngine("untried", Shape.RANDOM)
        val broken = FakeEngine("broken", Shape.DNS)

        val net = NetworkId.mobile("MCI")
        board.record(net, "slow", ok = true, tookMs = 9_000)
        board.record(net, "fast", ok = true, tookMs = 900)
        board.record(net, "broken", ok = false, tookMs = 0)

        val order = board.order(net, listOf(broken, untried, slow, fast)).map { it.name }
        assertEquals(listOf("fast", "slow", "untried", "broken"), order)
    }

    @Test
    fun `a win on one network says nothing about another`() = runTest {
        val board = Scoreboard(MemoryStore())
        val a = FakeEngine("a", Shape.WEBRTC)
        val b = FakeEngine("b", Shape.HTTPS)

        board.record(NetworkId.wifi(), "a", ok = true, tookMs = 500)

        // On wifi, a leads. On mobile data neither has history, so the caller's
        // order is preserved and nothing is assumed.
        assertEquals(listOf("a", "b"), board.order(NetworkId.wifi(), listOf(b, a)).map { it.name })
        assertEquals(listOf("b", "a"), board.order(NetworkId.mobile("MCI"), listOf(b, a)).map { it.name })
    }
}
