package xyz.jmc.gozar.core

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The numbers in the first test are copied off a real screenshot rather than invented, and that
 * is the point of it: the app sat there saying connected on exactly these counters, so a floor
 * that does not fire on them is a floor that would have changed nothing.
 */
class FloorTest {

    private val window = 15L  // seconds between health checks, matching the watcher

    @Test
    fun `the connection the user complained about is called starved`() {
        // Sixty-seven seconds of session: 402 KB out, 150 KB in — about 670 bytes a second down.
        val sentPerWindow = 402L * 1024 * window / 67
        val receivedPerWindow = 150L * 1024 * window / 67

        val floor = Floor()
        assertEquals(Floor.Verdict.IDLE, floor.sample(0, 0), "the first reading has no baseline")
        assertEquals(Floor.Verdict.STARVED, floor.sample(sentPerWindow, receivedPerWindow))
    }

    @Test
    fun `a phone nobody is using is never condemned`() {
        val floor = Floor()
        floor.sample(0, 0)
        // Keepalives and a push socket: a couple of kilobytes each way in fifteen seconds.
        assertEquals(Floor.Verdict.IDLE, floor.sample(2_000, 1_200))
        assertEquals(Floor.Verdict.IDLE, floor.sample(4_000, 2_400))
    }

    @Test
    fun `a working tunnel passes`() {
        val floor = Floor()
        floor.sample(0, 0)
        // Half a megabyte down in a window is an ordinary page load.
        assertEquals(Floor.Verdict.OK, floor.sample(60_000, 512_000))
    }

    @Test
    fun `asking for a lot and getting nothing is starved`() {
        val floor = Floor()
        floor.sample(0, 0)
        assertEquals(Floor.Verdict.STARVED, floor.sample(400_000, 4_000))
    }

    @Test
    fun `counters restarting under it is not evidence`() {
        val floor = Floor()
        floor.sample(1_000_000, 5_000_000)
        // The tunnel moved and the new bridge counts from zero. The deltas are hugely negative.
        assertEquals(Floor.Verdict.IDLE, floor.sample(500, 400))
        // And the window after that is judged against the new baseline, not the old one.
        assertEquals(Floor.Verdict.OK, floor.sample(60_000, 512_000))
    }

    @Test
    fun `a reset makes the next window free`() {
        val floor = Floor()
        floor.sample(0, 0)
        floor.reset()
        assertEquals(Floor.Verdict.IDLE, floor.sample(400_000, 1_000))
    }

    @Test
    fun `the boundary is where it says it is`() {
        val floor = Floor()

        floor.sample(0, 0)
        // Exactly at the demand line counts as demand, exactly at the received line counts as ok.
        assertEquals(
            Floor.Verdict.OK,
            floor.sample(Floor.DEMAND_BYTES, Floor.MIN_RECEIVED_BYTES),
        )

        val second = Floor()
        second.sample(0, 0)
        assertEquals(
            Floor.Verdict.STARVED,
            second.sample(Floor.DEMAND_BYTES, Floor.MIN_RECEIVED_BYTES - 1),
        )

        val third = Floor()
        third.sample(0, 0)
        assertEquals(
            Floor.Verdict.IDLE,
            third.sample(Floor.DEMAND_BYTES - 1, 0),
            "below the demand line nothing is claimed, however little came back",
        )
    }
}
