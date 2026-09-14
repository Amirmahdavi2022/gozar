package xyz.jmc.gozar.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The cases here are the ones the device logs actually produced: a two-hop rung that never
 * finished, a one-hop rung that finished and came out at home, and a lookup that answered
 * nothing at all through a tunnel that was working.
 */
class ExitGateTest {

    private val cost = 60_000L

    @Test
    fun `home is refused and anywhere else is kept`() {
        assertFalse(ExitGate.acceptable("IR"))
        assertFalse(ExitGate.acceptable(" ir "))
        assertTrue(ExitGate.acceptable("DE"))
        assertTrue(ExitGate.acceptable("NL"))
    }

    @Test
    fun `a lookup that answered nothing keeps the tunnel`() {
        assertTrue(ExitGate.acceptable(null))
        assertTrue(ExitGate.acceptable(""))
        assertEquals(
            ExitGate.Decision.ACCEPT,
            ExitGate.decide(canChangeCountry = true, code = null, rerollsUsed = 0, msLeft = 300_000, rerollCostMs = cost),
        )
    }

    @Test
    fun `a single hop at home is kept rather than re-rolled`() {
        // It is location preserving by design, so another go at it lands in the same place.
        assertEquals(
            ExitGate.Decision.ACCEPT,
            ExitGate.decide(canChangeCountry = false, code = "IR", rerollsUsed = 0, msLeft = 300_000, rerollCostMs = cost),
        )
    }

    @Test
    fun `a second hop at home is rolled again, but only twice`() {
        fun at(used: Int) =
            ExitGate.decide(canChangeCountry = true, code = "IR", rerollsUsed = used, msLeft = 300_000, rerollCostMs = cost)

        assertEquals(ExitGate.Decision.REROLL, at(0))
        assertEquals(ExitGate.Decision.REROLL, at(1))
        assertEquals(ExitGate.Decision.NEXT_RUNG, at(2), "the allowance is spent")
        assertEquals(ExitGate.Decision.NEXT_RUNG, at(9))
    }

    @Test
    fun `the last minute of the walk is left for the rungs below`() {
        assertEquals(
            ExitGate.Decision.NEXT_RUNG,
            ExitGate.decide(canChangeCountry = true, code = "IR", rerollsUsed = 0, msLeft = 30_000, rerollCostMs = cost),
            "no time to pay for another attempt at this rung",
        )
        assertEquals(
            ExitGate.Decision.REROLL,
            ExitGate.decide(canChangeCountry = true, code = "IR", rerollsUsed = 0, msLeft = 61_000, rerollCostMs = cost),
        )
    }
}
