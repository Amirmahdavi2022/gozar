package xyz.jmc.gozar.core

/**
 * Decides whether a tunnel that just came up is worth keeping, given where it comes out.
 *
 * 🚨 Why this exists at all, because it looks like a thing an app should not need to do. The
 * network behind the no-list way out is location preserving by design: a single hop through it
 * comes out in the country the phone is already in, every time, and no flag anywhere changes
 * that. Two of its four rungs build a second hop and can land abroad — but which edge the second
 * hop gets is drawn from a scan, so it is a roll of the dice rather than a setting, and a roll
 * can come up at home.
 *
 * 🔑 The shape here was not invented. The people who write the core's own reference client hit
 * exactly this and solved it the same way: connect, ask the other side of the tunnel which
 * country it thinks you are in, and if the answer is home, throw the tunnel away and roll again,
 * a small fixed number of times. There is no cleverer answer available to a client. Anyone who
 * comes back here looking for a flag that picks a country will not find one; this is the
 * mechanism.
 *
 * Three rules it holds to, each of which costs something to get wrong:
 *
 * A rung that cannot change the answer is never asked the question. Re-rolling a single hop is
 * pure delay: it will come up home again, because that is what it is built to do.
 *
 * An unreadable answer is accepted. The lookup goes through the tunnel being judged, so a failed
 * lookup is at least as likely to mean a slow first second as a bad exit — and throwing away a
 * working tunnel because a web request did not answer is a worse failure than an exit in the
 * wrong place.
 *
 * A re-roll is only offered while there is time left to pay for it, measured against the same
 * deadline the whole ladder runs under. Without that the gate quietly eats the rungs below it:
 * the walk spends its last minute rolling the dice on one rung and never reaches the one that
 * would have got out at all.
 */
object ExitGate {

    /**
     * The country a tunnel is not allowed to come out in, as an ISO code.
     *
     * Hard-coded rather than read from the SIM or the locale on purpose. Where the phone is and
     * where its owner wants to come out are different questions, and reading the first to answer
     * the second would break the app for anyone travelling — and, worse, would make this behave
     * differently on two phones sitting on the same table.
     */
    const val HOME = "IR"

    /** How many times one rung may be thrown away and started again over its exit country. */
    const val MAX_REROLLS = 2

    enum class Decision {
        /** Keep this tunnel. */
        ACCEPT,

        /** Home exit, and there is both an allowance and time to try the same rung again. */
        REROLL,

        /** Home exit and no more re-rolls worth paying for. Move down the ladder. */
        NEXT_RUNG,
    }

    /** True when the code names somewhere other than home, or names nowhere at all. */
    fun acceptable(code: String?): Boolean {
        val seen = code?.trim().orEmpty()
        if (seen.isEmpty()) return true
        return !seen.equals(HOME, ignoreCase = true)
    }

    /**
     * @param canChangeCountry whether this rung builds a second hop; a single hop is never re-rolled
     * @param code the ISO country the far side reported, or null/blank if nothing answered
     * @param rerollsUsed how many times this rung has already been thrown away this walk
     * @param msLeft what is left of the walk's deadline
     * @param rerollCostMs what another attempt at this rung would cost, window and check together
     */
    fun decide(
        canChangeCountry: Boolean,
        code: String?,
        rerollsUsed: Int,
        msLeft: Long,
        rerollCostMs: Long,
    ): Decision {
        if (acceptable(code)) return Decision.ACCEPT
        if (!canChangeCountry) return Decision.ACCEPT
        if (rerollsUsed >= MAX_REROLLS) return Decision.NEXT_RUNG
        if (msLeft < rerollCostMs) return Decision.NEXT_RUNG
        return Decision.REROLL
    }
}
