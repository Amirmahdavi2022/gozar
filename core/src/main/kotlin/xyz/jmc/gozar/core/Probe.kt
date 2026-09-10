package xyz.jmc.gozar.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.charset.StandardCharsets

/**
 * Asks a tunnel to fetch something tiny, and reports whether it came back.
 *
 * Starting an engine only means a local port is open. A dead endpoint and a live one both accept a
 * connection on loopback in about a millisecond, so nothing here believes an engine until real
 * bytes have made the round trip.
 *
 * <b>Why this speaks SOCKS itself instead of using the http stack.</b> Two separate failures were
 * traced to that stack and neither had anything to do with the tunnel:
 *
 * Android blocks cleartext traffic before a byte reaches the socket, so an http request failed
 * instantly on every network through every tunnel, with an error that read like a refusal from the
 * far end. Every engine looked dead because the ruler was broken.
 *
 * Switching those requests to https moved the problem rather than fixing it: through Tor the
 * handshake was cut mid-way — "unexpected end of stream" — after twenty or thirty seconds. A
 * connection arriving from a Tor exit is treated differently by the large providers than the same
 * connection from a phone, and a captive-portal endpoint is exactly the kind of address where that
 * shows up. The tunnel was fine. The test was being answered by something that had opinions about
 * where it came from.
 *
 * So: a raw socket, a SOCKS5 CONNECT written by hand, and a plain one-line request. No library in
 * the middle to have policies of its own, no TLS to be judged on, and the destination is sent as a
 * name so the far end resolves it — which both proves the tunnel's own resolution works and keeps
 * the lookup off this device.
 */
class HttpProbe(
    private val targets: List<Pair<String, String>> = TARGETS,
    private val log: (String) -> Unit = {},
) : Prober {

    override suspend fun through(socksPort: Int, timeoutMs: Int): Boolean =
        withContext(Dispatchers.IO) {
            for ((host, path) in targets) {
                val failure = exchange(socksPort, host, path, timeoutMs)
                if (failure == null) return@withContext true
                log("probe to $host failed: $failure")
            }
            false
        }

    /** @return null when the round trip succeeded, otherwise why it did not */
    private fun exchange(socksPort: Int, host: String, path: String, timeoutMs: Int): String? {
        var socket: Socket? = null
        try {
            socket = Socket()
            socket.connect(InetSocketAddress(LOOPBACK, socksPort), timeoutMs)
            socket.soTimeout = timeoutMs

            val out = socket.getOutputStream()
            val input = socket.getInputStream()

            // Greeting: version 5, one method, no authentication.
            out.write(byteArrayOf(0x05, 0x01, 0x00))
            out.flush()
            val greeting = readFully(input, 2)
            if (greeting[0] != 0x05.toByte() || greeting[1] != 0x00.toByte()) {
                return "the tunnel refused an unauthenticated session"
            }

            val name = host.toByteArray(StandardCharsets.UTF_8)
            val request = ByteArray(7 + name.size)
            request[0] = 0x05                      // version
            request[1] = 0x01                      // connect
            request[2] = 0x00                      // reserved
            request[3] = 0x03                      // the address is a name
            request[4] = name.size.toByte()
            System.arraycopy(name, 0, request, 5, name.size)
            request[5 + name.size] = ((PORT shr 8) and 0xFF).toByte()
            request[6 + name.size] = (PORT and 0xFF).toByte()
            out.write(request)
            out.flush()

            val reply = readFully(input, 4)
            if (reply[0] != 0x05.toByte()) return "the tunnel answered in an unknown dialect"
            if (reply[1] != 0x00.toByte()) {
                // Tor's codes are informative: 4 is host unreachable, 5 refused, 2 not allowed.
                return "the tunnel could not reach it (socks code ${reply[1].toInt() and 0xFF})"
            }
            // The bound address trails the reply and must be drained, or it would be read as the
            // first bytes of the response.
            when (reply[3]) {
                0x01.toByte() -> readFully(input, 4 + 2)
                0x04.toByte() -> readFully(input, 16 + 2)
                0x03.toByte() -> readFully(input, (readFully(input, 1)[0].toInt() and 0xFF) + 2)
                else -> return "the tunnel returned an address type nobody uses"
            }

            out.write(
                ("GET $path HTTP/1.1\r\n" +
                    "Host: $host\r\n" +
                    "User-Agent: Mozilla/5.0\r\n" +
                    "Connection: close\r\n\r\n").toByteArray(StandardCharsets.UTF_8)
            )
            out.flush()

            // Only the status line matters, and reading it a byte at a time avoids blocking on a
            // body that may never arrive.
            val line = StringBuilder(64)
            while (line.length < 64) {
                val c = input.read()
                if (c < 0 || c == '\n'.code) break
                if (c != '\r'.code) line.append(c.toChar())
            }
            val status = line.toString()
            return if (status.startsWith("HTTP/")) null else "nothing came back through it"
        } catch (e: Exception) {
            return "${e.javaClass.simpleName}: ${e.message}"
        } finally {
            runCatching { socket?.close() }
        }
    }

    private fun readFully(input: InputStream, count: Int): ByteArray {
        val buffer = ByteArray(count)
        var read = 0
        while (read < count) {
            val step = input.read(buffer, read, count - read)
            if (step < 0) throw IllegalStateException("the tunnel closed the connection early")
            read += step
        }
        return buffer
    }

    companion object {
        private const val LOOPBACK = "127.0.0.1"
        private const val PORT = 80

        /**
         * Small, dull, and hammered by every phone on earth, so asking for one is unremarkable.
         *
         * More than one, because a single unreachable host would condemn a working tunnel. The
         * order is not arbitrary:
         *
         * 🚨 Cloudflare's is last. A large share of the public endpoints this app dials are served
         * by Cloudflare Workers, and a Worker cannot open a connection to a Cloudflare address —
         * the platform blocks it by design so traffic cannot loop back through itself. An endpoint
         * carrying everything else perfectly fails a Cloudflare-aimed probe every single time, and
         * the app would condemn it, move to the next one, which is also a Worker, and so on
         * through the whole pool. Not a network problem, and no amount of retrying gets past it.
         */
        val TARGETS = listOf(
            "www.gstatic.com" to "/generate_204",
            "detectportal.firefox.com" to "/success.txt",
            "cp.cloudflare.com" to "/generate_204",
        )
    }
}
