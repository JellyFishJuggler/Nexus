package dev.nexus.local.pairing

import java.security.MessageDigest

/**
 * Short Authentication String (SAS) for pairing — `protocol/pairing.md`
 * § "SAS verification code".
 *
 * ```text
 * certs = [own_der, peer_der]      # sorted by raw bytes, unsigned
 * h     = SHA-256( certs[0] || certs[1] )
 * code  = ( first 4 bytes of h as BE u32 ) % 1_000_000
 * ```
 *
 * Must produce byte-identical results to the Rust implementation
 * (`Identity::sas_code`), since both devices compare the displayed code
 * visually. Note the sort is over *unsigned* bytes, matching Rust's
 * `Vec<u8>` ordering — Kotlin bytes are signed, so this is done explicitly.
 */
object Sas {

    /** Six digits formatted as `"482 913"`. */
    fun code(ownCertDer: ByteArray, peerCertDer: ByteArray): String {
        val (first, second) = if (compareUnsigned(ownCertDer, peerCertDer) <= 0) {
            ownCertDer to peerCertDer
        } else {
            peerCertDer to ownCertDer
        }
        val digest = MessageDigest.getInstance("SHA-256").apply {
            update(first)
            update(second)
        }.digest()
        // Kotlin's `shl`/`or` are infix functions with equal, left-associative
        // precedence, so every step is parenthesized explicitly. Long (not Int)
        // because the first 4 digest bytes are an unsigned u32.
        val b0 = (digest[0].toLong() and 0xff) shl 24
        val b1 = (digest[1].toLong() and 0xff) shl 16
        val b2 = (digest[2].toLong() and 0xff) shl 8
        val b3 = digest[3].toLong() and 0xff
        val value = (b0 or b1 or b2 or b3) % 1_000_000L
        return "%03d %03d".format(value / 1000, value % 1000)
    }

    /** Lexicographic comparison treating bytes as unsigned, length tie-break. */
    private fun compareUnsigned(a: ByteArray, b: ByteArray): Int {
        val shared = minOf(a.size, b.size)
        for (i in 0 until shared) {
            val x = a[i].toInt() and 0xff
            val y = b[i].toInt() and 0xff
            if (x != y) return x - y
        }
        return a.size - b.size
    }
}
