package xyz.jmc.gozar

import hev.htproxy.TProxyService
import java.io.File

/**
 * Moves packets between the tun device and the winning engine's SOCKS port.
 *
 * Native C rather than Go, and not only for speed: IPtProxy is already a
 * gomobile library and an app can only carry one of those, so a Go tun2socks
 * would refuse to link.
 */
class Tun2Socks(private val filesDir: File) : TrafficSource {

    @Volatile private var running = false

    fun start(tunFd: Int, socksPort: Int): Boolean {
        if (running) stop()

        val config = File(filesDir, "tun2socks.yml")
        config.writeText(configFor(socksPort))

        running = runCatching { TProxyService.TProxyStartService(config.absolutePath, tunFd) }
            .getOrDefault(false)
        return running
    }

    fun stop() {
        if (!running) return
        running = false
        runCatching { TProxyService.TProxyStopService() }
    }

    /**
     * Counters straight out of the native side, so what the screen shows is
     * what actually crossed the tunnel rather than something the app inferred.
     */
    override fun sample(): Traffic {
        if (!running) return Traffic()
        val stats = runCatching { TProxyService.TProxyGetStats() }.getOrNull() ?: return Traffic()
        // tx packets, tx bytes, rx packets, rx bytes — tx is what left the
        // phone, so from the user's point of view tx is "up".
        if (stats.size < 4) return Traffic()
        return Traffic(down = stats[3], up = stats[1])
    }

    /**
     * The mapdns block is the difference between a tunnel that is up and a
     * tunnel that is usable.
     *
     * Every SOCKS5 proxy worth tunnelling through here answers CONNECT and
     * nothing else. Tor certainly does: it has no UDP at all. hev only has two
     * ways to carry UDP — command 0x03, plain UDP ASSOCIATE, and heiher's own
     * 0x05 — and a Tor SOCKS port rejects both, so whichever mode is chosen,
     * every DNS query the phone makes goes into a hole. The app looks
     * connected, the counters even move, and not one name resolves.
     *
     * mapdns sidesteps the negotiation entirely: hev answers DNS itself inside
     * the tunnel, hands the app an address out of 100.64.0.0/10, and turns it
     * back into the hostname when it opens the SOCKS request. So the name rides
     * inside the TCP request that already works — and as a bonus the name is
     * resolved at the far end, which is what you want from Tor anyway.
     *
     * The address here has to match the DNS server the VpnService advertises,
     * and the mapped range has to be routed into the tun. Both live in
     * [GozarVpnService], and getting one without the other is the quietest
     * possible failure: every lookup succeeds and nothing connects.
     */
    private fun configFor(socksPort: Int) = """
        tunnel:
          mtu: $MTU
        socks5:
          port: $socksPort
          address: 127.0.0.1
          udp: 'udp'
        mapdns:
          address: $DNS_ADDRESS
          port: 53
          network: $MAPPED_NETWORK
          netmask: $MAPPED_NETMASK
          cache-size: 10000
        misc:
          task-stack-size: 20480
          log-level: warn
    """.trimIndent() + "\n"

    companion object {
        const val MTU = 1500

        /** Where the phone is told to send DNS, and where hev answers it. */
        const val DNS_ADDRESS = "198.18.0.2"

        /** Handed back for a name, and turned into that name again on CONNECT. */
        const val MAPPED_NETWORK = "100.64.0.0"
        const val MAPPED_NETMASK = "255.192.0.0"
        const val MAPPED_PREFIX = 10
    }
}
