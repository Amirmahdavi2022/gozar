package xyz.jmc.gozar.core

import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertNotEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private class FakeEngine(
    override val name: String,
    override val shape: Shape,
    private val startDelayMs: Long = 0,
    private val failToStart: Boolean = false,
    override val launchDelayMs: Long = 0,
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

        // Waiting on virtual time rather than advanceUntilIdle, because the health watcher never
        // goes idle by design.
        delay(10_000)

        // It is not merely stopped, it is never started: the race was already won by the time its
        // turn came round, and nothing that shares the winner's shape would have been kept anyway.
        assertEquals(0, sameShape.port, "a same-shape runner-up should not be started at all")
        assertNotEquals("same", racer.activeSession()?.engine)
    }

    @Test
    fun `a proven engine is handed over without waiting for a slower one`() = runTest {
        // The failure this guards against cost two and a half minutes on a real device: the fast
        // engine was up and verified, and the app sat on it until the other engine finished
        // timing out.
        val quick = FakeEngine("quick", Shape.HTTPS)
        val crawler = FakeEngine("crawler", Shape.WEBRTC, startDelayMs = 120_000)

        val racer = Racer(listOf(quick, crawler), Scoreboard(MemoryStore()),
            FakeProbe(listOf(quick, crawler)), backgroundScope)

        val before = testScheduler.currentTime
        val session = racer.connect(NetworkId.UNKNOWN)
        val waited = testScheduler.currentTime - before

        assertNotNull(session)
        assertEquals("quick", session.engine)
        assertTrue(waited < 60_000, "handed over after ${waited}ms, which means it waited for the slow engine")
    }

    @Test
    fun `nothing working returns nothing rather than a fake session`() = runTest {
        val a = FakeEngine("a", Shape.WEBRTC, failToStart = true)
        val b = FakeEngine("b", Shape.HTTPS, carriesTraffic = false)

        val racer = Racer(listOf(a, b), Scoreboard(MemoryStore()), FakeProbe(listOf(a, b)), backgroundScope)
        assertNull(racer.connect(NetworkId.UNKNOWN))
    }

    @Test
    fun `the slot a promotion empties is filled again`() = runTest {
        // The failure being guarded against: the standby saves the user once, and from then on
        // the app is back to a single way out with nothing behind it, which is exactly the state
        // the standby exists to avoid.
        val winner = FakeEngine("winner", Shape.HTTPS)
        val reserve = FakeEngine("reserve", Shape.WEBRTC, startDelayMs = 2_000)
        val third = FakeEngine("third", Shape.RANDOM, startDelayMs = 4_000)
        val probe = FakeProbe(listOf(winner, reserve, third))

        val racer = Racer(
            listOf(winner, reserve, third), Scoreboard(MemoryStore()), probe, backgroundScope,
        )
        racer.connect(NetworkId.UNKNOWN)
        delay(10_000)

        // The winner dies. The reserve should be promoted, and something new held behind it.
        winner.carriesTraffic = false
        delay(90_000)

        assertEquals("reserve", racer.activeSession()?.engine)
        assertTrue(third.port != 0, "a replacement standby should have been started")
    }

    @Test
    fun `the tunnel moves back off a fallback once something better is warm`() = runTest {
        // The failure this guards against, seen on a real device: the good path was throttled,
        // the floor moved the tunnel onto the fallback exactly as designed, the good path came
        // back and was held warm a few seconds later - and the session stayed on the fallback for
        // good, because nothing here reacts to the live tunnel merely being the worse of the two.
        val fallback = FakeEngine("fallback", Shape.WIREGUARD, launchDelayMs = 6_000)
        val preferred = FakeEngine("preferred", Shape.RANDOM, startDelayMs = 8_000)
        val probe = FakeProbe(listOf(fallback, preferred))

        val racer = Racer(
            listOf(fallback, preferred), Scoreboard(MemoryStore()), probe, backgroundScope,
        )
        // The fallback wins, because the one that should win takes eight seconds to come up.
        assertEquals("fallback", racer.connect(NetworkId.UNKNOWN)?.engine)

        delay(30_000)
        assertEquals("preferred", racer.activeSession()?.engine, "should have moved off the fallback")
    }

    @Test
    fun `a reserve that died while waiting is replaced`() = runTest {
        // A standby is started once and then sits for hours. Nothing about having started keeps
        // it alive, and discovering that at the moment of promotion is the worst possible time:
        // the tun has already been pointed at it and the user is already offline.
        val winner = FakeEngine("winner", Shape.HTTPS)
        val reserve = FakeEngine("reserve", Shape.WEBRTC, startDelayMs = 2_000)
        val third = FakeEngine("third", Shape.RANDOM, startDelayMs = 4_000)
        val probe = FakeProbe(listOf(winner, reserve, third))

        val racer = Racer(
            listOf(winner, reserve, third), Scoreboard(MemoryStore()), probe, backgroundScope,
        )
        racer.connect(NetworkId.UNKNOWN)
        delay(10_000)

        reserve.carriesTraffic = false
        delay(120_000)

        assertEquals("winner", racer.activeSession()?.engine, "the live tunnel is untouched")
        assertTrue(third.port != 0, "the dead reserve should have been swapped out")
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
