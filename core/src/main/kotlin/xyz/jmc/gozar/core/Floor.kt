package xyz.jmc.gozar.core

/**
 * Watches how much actually comes back, and says when a live tunnel has stopped being worth
 * having.
 *
 * 🚨 Why this exists, from a real device log rather than from theory. The health check asks a
 * small host for a two-hundred-and-four with no body. That request succeeds perfectly well over a
 * tunnel moving six hundred and seventy bytes a second, so every check passed, nothing was ever
 * written off, and the app sat there with a green dot and an elapsed timer while not one page
 * would load. The screen was honest about being connected and that was the whole problem: nobody
 * cares whether a tunnel is connected, they care whether it carries anything.
 *
 * So this reads the counters the tun is already keeping and applies the only test that matches
 * what a person experiences.
 *
 * Two things it deliberately will not do:
 *
 * It never judges an idle tunnel. A phone in a pocket sends almost nothing and receives almost
 * nothing, and that is not a fault — condemning it would tear down a perfectly good connection
 * every time its owner stopped using it. Something has to have been ASKED for before the answer
 * counts as evidence, which is what the sent side is read for.
 *
 * It never judges on one window. Rates are lumpy; a single quiet fifteen seconds in the middle of
 * a download is ordinary. The caller is given a verdict per window and decides how many in a row
 * it wants, exactly as it already does for failed probes.
 */
class Floor(
    /** Bytes that must have gone OUT in a window before the window says anything at all. */
    private val demandBytes: Long = DEMAND_BYTES,
    /** Bytes that must have come BACK in a window where there was demand. */
    private val minReceivedBytes: Long = MIN_RECEIVED_BYTES,
) {

    enum class Verdict {
        /** Enough came back, or the question is not being asked yet. */
        OK,

        /** Nobody asked for anything. Says nothing about the tunnel, in either direction. */
        IDLE,

        /** Plenty was asked for and almost nothing came back. This is the one worth acting on. */
        STARVED,
    }

    private var lastSent = UNSET
    private var lastReceived = UNSET

    /**
     * Feeds one reading of the running totals and returns what this window looked like.
     *
     * @param sent total bytes out since the tunnel came up
     * @param received total bytes in since the tunnel came up
     */
    fun sample(sent: Long, received: Long): Verdict {
        val previousSent = lastSent
        val previousReceived = lastReceived
        lastSent = sent
        lastReceived = received

        // First reading has nothing to compare against.
        if (previousSent == UNSET || previousReceived == UNSET) return Verdict.IDLE

        val sentDelta = sent - previousSent
        val receivedDelta = received - previousReceived

        // 🚨 A counter that went backwards means the tunnel underneath was replaced and its totals
        // started again from zero. Treating that arithmetic as a window would produce an enormous
        // negative "received" and condemn an engine that had just been successfully recovered —
        // the one moment it least deserves it. Re-baseline and say nothing.
        if (sentDelta < 0 || receivedDelta < 0) return Verdict.IDLE

        if (sentDelta < demandBytes) return Verdict.IDLE
        return if (receivedDelta < minReceivedBytes) Verdict.STARVED else Verdict.OK
    }

    /** Forgets the baseline. Called when the tunnel moves, so the first window after is free. */
    fun reset() {
        lastSent = UNSET
        lastReceived = UNSET
    }

    companion object {
        private const val UNSET = -1L

        /**
         * Roughly what a phone sends in fifteen seconds while somebody is actually using it:
         * requests, acknowledgements, a photo going up. Keepalives and push sockets on an idle
         * phone come in well under this, which is the line being drawn.
         */
        const val DEMAND_BYTES = 24L * 1024

        /**
         * Set against the measurement that prompted all of this. Six hundred and seventy bytes a
         * second is roughly ten kilobytes in a fifteen-second window; anything remotely usable is
         * several times this number. A tunnel that was asked for twenty-four kilobytes and
         * returned under forty-eight is not slow, it is broken, and moving off it is right even if
         * the next one takes a few seconds to find.
         */
        const val MIN_RECEIVED_BYTES = 48L * 1024
    }
}
