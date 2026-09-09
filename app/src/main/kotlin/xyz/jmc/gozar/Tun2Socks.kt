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

    private fun configFor(socksPort: Int) = """
        tunnel:
          mtu: 1500
        socks5:
          port: $socksPort
          address: 127.0.0.1
          udp: 'tcp'
        misc:
          task-stack-size: 20480
          log-level: warn
    """.trimIndent() + "\n"
}
