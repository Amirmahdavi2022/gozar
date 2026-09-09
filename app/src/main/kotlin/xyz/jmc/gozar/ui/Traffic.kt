package xyz.jmc.gozar

/** Bytes moved since the tunnel came up. */
data class Traffic(
    val down: Long = 0,
    val up: Long = 0,
    val downRate: Long = 0,
    val upRate: Long = 0,
)

/**
 * Where the counters come from.
 *
 * Once the tun is wired to a tun2socks this reads its counters. Until then the
 * screen shows zeroes rather than something invented, because a number that
 * moves when nothing is moving is worse than no number.
 */
interface TrafficSource {
    fun sample(): Traffic
}

object NoTraffic : TrafficSource {
    override fun sample() = Traffic()
}
