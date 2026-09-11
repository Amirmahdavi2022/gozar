package xyz.jmc.gozar.core

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The vectors are copied from RFC 7748 rather than generated here, which is the whole point: a
 * subtly wrong curve still returns thirty-two tidy bytes, registration still succeeds, and the
 * tunnel simply never completes a handshake — with nothing anywhere saying why.
 */
class X25519Test {

    private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it) }

    private fun unhex(text: String) = ByteArray(text.length / 2) {
        ((Character.digit(text[it * 2], 16) shl 4) + Character.digit(text[it * 2 + 1], 16)).toByte()
    }

    @Test
    fun `rfc 7748 scalar multiplication vectors`() {
        assertEquals(
            "c3da55379de9c6908e94ea4df28d084f32eccf03491c71f754b4075577a28552",
            hex(
                X25519.scalarMult(
                    unhex("a546e36bf0527c9d3b16154b82465edd62144c0ac1fc5a18506a2244ba449ac4"),
                    unhex("e6db6867583030db3594c1a424b15f7c726624ec26b3353b10a903a6d0ab1c4c"),
                ),
            ),
        )
        assertEquals(
            "95cbde9476e8907d7aade45cb4b873f88b595a68799fa152e6f8f7647aac7957",
            hex(
                X25519.scalarMult(
                    unhex("4b66e9d4d1b4673c5ad22691957d6af5c11b6421e0ea01d42ca4169e7918ba0d"),
                    unhex("e5210f12786811d3f4b7959d0538ae2c31dbe7106fc03c3efc4cd549c715a493"),
                ),
            ),
        )
    }

    @Test
    fun `the public key derived from a private key matches the rfc`() {
        assertEquals(
            "8520f0098930a754748b7ddcb43ef75a0dbf3a0d26381af4eba4a98eaa9b4e6a",
            hex(X25519.publicKey(unhex("77076d0a7318a57d3c16c17251b26645df4c2f87ebc0992ab177fba51db92c2a"))),
        )
        assertEquals(
            "de9edb7d7b7dc1b4d35b61c2ece435373f8343c85b78674dadfc7e146f882b4f",
            hex(X25519.publicKey(unhex("5dab087e624a8a4b79e17f8b83800ee66f3bb1292618b6fd1c2f8b27ff88e0eb"))),
        )
    }

    @Test
    fun `both sides of a handshake reach the same secret`() {
        val alice = unhex("77076d0a7318a57d3c16c17251b26645df4c2f87ebc0992ab177fba51db92c2a")
        val bob = unhex("5dab087e624a8a4b79e17f8b83800ee66f3bb1292618b6fd1c2f8b27ff88e0eb")
        val expected = "4a5d9d5ba4ce2de1728e3bf480350f25e07e21c947d19e3376f09b3c1e161742"

        assertEquals(expected, hex(X25519.scalarMult(alice, X25519.publicKey(bob))))
        assertEquals(expected, hex(X25519.scalarMult(bob, X25519.publicKey(alice))))
    }

    @Test
    fun `clamping clears and sets the bits it is supposed to`() {
        val clamped = X25519.clamp(ByteArray(32) { 0xFF.toByte() })
        assertEquals("f8", "%02x".format(clamped[0]))
        assertEquals("7f", "%02x".format(clamped[31]))
    }
}
