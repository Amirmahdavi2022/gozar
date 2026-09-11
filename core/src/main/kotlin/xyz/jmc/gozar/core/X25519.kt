package xyz.jmc.gozar.core

import java.math.BigInteger

/**
 * Curve25519 scalar multiplication, written out here rather than taken from the platform.
 *
 * 🚨 This exists to work around a trap that cost an entire engine, so the reason is worth keeping.
 * A Go program running on Android has no `/etc/resolv.conf`, because Android does not ship one. Go's
 * own resolver, finding no file, falls back to asking 127.0.0.1 and [::1] on port 53 — where nothing
 * is listening. Every hostname lookup then fails instantly. The same device log that prompted this
 * shows it happening to a different Go program in plain sight: `lookup … on [::1]:53`.
 *
 * The edge program's very first act is to register an account over HTTPS, which needs a hostname.
 * So it died in under a second, three times over, and the app reported that nothing answered — when
 * in truth nothing had been asked. The fix is to do that registration up here, in the app, where
 * Android's own resolver and TLS are used and none of this applies, then hand the program a
 * finished account so it never makes the call.
 *
 * Registration needs a key pair, hence this. The platform does expose X25519, but only from
 * Android 13 onwards, and an engine that silently disappears on older phones is exactly the class
 * of defect being fixed here. Forty lines of arithmetic that run everywhere is the better trade.
 *
 * 🔑 Correctness is checked against the published test vectors rather than against itself, which is
 * the only check worth anything for a thing like this: a subtly wrong curve still returns 32 neat
 * bytes, the registration still succeeds, and the tunnel simply never handshakes.
 *
 * Follows RFC 7748. Not written for constant time — it runs once per install, on a key that is
 * about to be sent to the other end anyway, with no attacker in a position to time it.
 */
object X25519 {

    private val P: BigInteger = BigInteger.TWO.pow(255).subtract(BigInteger.valueOf(19))
    private val A24: BigInteger = BigInteger.valueOf(121665)

    /** The u-coordinate of the base point, which is simply 9. */
    private val BASE_POINT: ByteArray = ByteArray(32).also { it[0] = 9 }

    /** Turns 32 random bytes into a usable private key, per the clamping rules in the RFC. */
    fun clamp(random: ByteArray): ByteArray {
        require(random.size == 32) { "a private key is 32 bytes" }
        val k = random.copyOf()
        k[0] = (k[0].toInt() and 248).toByte()
        k[31] = (k[31].toInt() and 127).toByte()
        k[31] = (k[31].toInt() or 64).toByte()
        return k
    }

    /** The public key matching a (clamped) private key. */
    fun publicKey(privateKey: ByteArray): ByteArray = scalarMult(privateKey, BASE_POINT)

    fun scalarMult(scalar: ByteArray, point: ByteArray): ByteArray {
        require(scalar.size == 32 && point.size == 32) { "both inputs are 32 bytes" }

        val k = clamp(scalar)
        val u = point.copyOf().also { it[31] = (it[31].toInt() and 127).toByte() }

        val x1 = fromLittleEndian(u)
        var x2 = BigInteger.ONE
        var z2 = BigInteger.ZERO
        var x3 = x1
        var z3 = BigInteger.ONE
        var swap = 0

        for (t in 254 downTo 0) {
            val bit = (k[t ushr 3].toInt() ushr (t and 7)) and 1
            if (swap xor bit == 1) {
                val tx = x2; x2 = x3; x3 = tx
                val tz = z2; z2 = z3; z3 = tz
            }
            swap = bit

            val a = (x2 + z2).mod(P)
            val aa = a.multiply(a).mod(P)
            val b = (x2 - z2).mod(P)
            val bb = b.multiply(b).mod(P)
            val e = (aa - bb).mod(P)
            val c = (x3 + z3).mod(P)
            val d = (x3 - z3).mod(P)
            val da = d.multiply(a).mod(P)
            val cb = c.multiply(b).mod(P)

            x3 = (da + cb).mod(P).let { it.multiply(it).mod(P) }
            z3 = (da - cb).mod(P).let { it.multiply(it).mod(P) }.multiply(x1).mod(P)
            x2 = aa.multiply(bb).mod(P)
            z2 = e.multiply(aa + A24.multiply(e).mod(P)).mod(P)
        }

        if (swap == 1) {
            val tx = x2; x2 = x3; x3 = tx
            val tz = z2; z2 = z3; z3 = tz
        }

        // z2^(p-2) is the inverse of z2, because p is prime.
        val inverse = z2.modPow(P.subtract(BigInteger.TWO), P)
        return toLittleEndian(x2.multiply(inverse).mod(P))
    }

    private fun fromLittleEndian(bytes: ByteArray): BigInteger {
        val reversed = ByteArray(bytes.size + 1)
        for (i in bytes.indices) reversed[bytes.size - i] = bytes[i]
        return BigInteger(reversed)
    }

    private fun toLittleEndian(value: BigInteger): ByteArray {
        val out = ByteArray(32)
        val raw = value.toByteArray()
        for (i in raw.indices) {
            val target = raw.size - 1 - i
            if (target < 32) out[target] = raw[i]
        }
        return out
    }
}
