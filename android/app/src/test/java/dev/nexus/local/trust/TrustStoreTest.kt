package dev.nexus.local.trust

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Trust store serialization must match the Rust `trusted_devices.json`
 * (`protocol/pairing.md` § "Trust store"): `{version, devices:[...]}` with
 * the fields `device_id, name, platform, cert_sha256, cert_pem, paired_at`.
 */
class TrustStoreTest {

    private val sample = TrustedDevice(
        deviceId = "6f1e0a5e-2a1b-4c3d-8e9f-0123456789ab",
        name = "DESKTOP-7F3",
        platform = "windows",
        certSha256 = "a".repeat(64),
        certPem = "-----BEGIN CERTIFICATE-----\nAAAA\n-----END CERTIFICATE-----\n",
        pairedAt = 1_700_000_000L
    )

    @Test
    fun roundTripsThroughJson() {
        val decoded = TrustStore.decode(TrustStore.encode(listOf(sample)))
        assertEquals(listOf(sample), decoded)
    }

    @Test
    fun writesTheDocumentedShape() {
        val root = JSONObject(TrustStore.encode(listOf(sample)))
        assertEquals(1, root.getInt("version"))
        val device = root.getJSONArray("devices").getJSONObject(0)
        assertEquals(sample.deviceId, device.getString("device_id"))
        assertEquals(sample.name, device.getString("name"))
        assertEquals(sample.platform, device.getString("platform"))
        assertEquals(sample.certSha256, device.getString("cert_sha256"))
        assertEquals(sample.certPem, device.getString("cert_pem"))
        assertEquals(sample.pairedAt, device.getLong("paired_at"))
    }

    @Test
    fun emptyStoreEncodesAndDecodes() {
        assertEquals(emptyList<TrustedDevice>(), TrustStore.decode(TrustStore.encode(emptyList())))
    }

    @Test
    fun malformedDocumentThrowsRatherThanTrustingNothing() {
        // A missing `devices` array must not be silently read as "no devices";
        // decode throws so the caller can fail safe (trust nothing) knowingly.
        assertThrows(Exception::class.java) { TrustStore.decode("{\"version\":1}") }
        assertThrows(Exception::class.java) { TrustStore.decode("not json at all") }
    }

    @Test
    fun skipsEntriesMissingRequiredFields() {
        val document = """
            {"version":1,"devices":[
              {"device_id":"ok","name":"n","platform":"p","cert_sha256":"${"b".repeat(64)}","cert_pem":"x","paired_at":1},
              {"name":"no id","cert_sha256":"y"},
              {"device_id":"no fp"}
            ]}
        """.trimIndent()
        val decoded = TrustStore.decode(document)
        assertEquals(1, decoded.size)
        assertEquals("ok", decoded[0].deviceId)
    }

    @Test
    fun fingerprintIsLowercaseHexSha256() {
        val fingerprint = TrustStore.fingerprintOf(byteArrayOf(0x00, 0x01, 0x02))
        assertEquals(64, fingerprint.length)
        assertTrue(fingerprint.matches(Regex("[0-9a-f]{64}")))
        // Known SHA-256 of bytes {0x00,0x01,0x02}.
        assertEquals(
            "ae4b3280e56e2faf83f414a6e3dabe9d5fbe18976544c05fed121accb85b53fc",
            fingerprint
        )
    }

    @Test
    fun fromJsonRejectsMissingIdentity() {
        assertNull(TrustedDevice.fromJson(JSONObject("{}")))
        assertNull(TrustedDevice.fromJson(JSONObject("{\"device_id\":\"x\"}")))
    }
}
