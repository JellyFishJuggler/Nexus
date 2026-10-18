package dev.nexus.local.pairing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * SAS algorithm — `protocol/pairing.md` § "SAS verification code".
 *
 * Must stay byte-identical to the Rust `sas_code_of`, since the two devices
 * compare the displayed code visually.
 */
class SasTest {

    @Test
    fun matchesTheRustGoldenVector() {
        // Same fixed arrays as the Rust test `identity::tests::sas_golden_vector`.
        // Includes bytes > 0x7f (negative as Kotlin Bytes) and a length
        // difference, so a signed comparison or a missing tiebreak would
        // produce a different code.
        val a = byteArrayOf(0x30, 0xff.toByte(), 0x01, 0x80.toByte())
        val b = byteArrayOf(0x30, 0x7f, 0x10, 0x80.toByte(), 0x00)
        assertEquals("022 451", Sas.code(a, b))
    }

    @Test
    fun isOrderIndependent() {
        val a = byteArrayOf(0x10, 0x20, 0x30)
        val b = byteArrayOf(0x40, 0x50)
        assertEquals(Sas.code(a, b), Sas.code(b, a))
    }

    @Test
    fun isFormattedAsTwoGroupsOfThreeDigits() {
        val code = Sas.code(byteArrayOf(0x01), byteArrayOf(0x02))
        assertEquals(7, code.length) // "NNN NNN"
        assertTrue(code.matches(Regex("\\d{3} \\d{3}")))
    }

    @Test
    fun differsForDifferentCertificates() {
        val a = byteArrayOf(0x01, 0x02)
        assertNotEquals(
            Sas.code(a, byteArrayOf(0x03, 0x04)),
            Sas.code(a, byteArrayOf(0x05, 0x06))
        )
    }

    @Test
    fun prefixDoesNotCollideWithLongerArray() {
        // The length tiebreak must make these distinct orderings.
        val short = byteArrayOf(0x01, 0x02)
        val long = byteArrayOf(0x01, 0x02, 0x03)
        val third = byteArrayOf(0x09)
        // (short vs long) and (short vs third) exercise the comparator.
        assertNotEquals(Sas.code(short, long), Sas.code(short, third))
    }
}
