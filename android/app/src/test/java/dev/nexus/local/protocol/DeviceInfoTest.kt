package dev.nexus.local.protocol

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Peer identity payload shared by `system.hello`, `pairing.request` and
 * `pairing.accept` (protocol/connection.md, protocol/pairing.md).
 */
class DeviceInfoTest {

    private val sample = DeviceInfo(
        deviceId = "6f1e0a5e-2a1b-4c3d-8e9f-0123456789ab",
        name = "Test Phone",
        platform = "android",
        pv = 1,
        features = listOf("clipboard")
    )

    @Test
    fun roundTripsThroughJson() {
        val parsed = DeviceInfo.parse(JSONObject(sample.toJson().toString()))
        assertEquals(sample, parsed)
    }

    @Test
    fun writesTheDocumentedKeys() {
        val json = sample.toJson()
        assertEquals(sample.deviceId, json.getString("device_id"))
        assertEquals(sample.name, json.getString("name"))
        assertEquals(sample.platform, json.getString("platform"))
        assertEquals(1, json.getInt("pv"))
        assertEquals("clipboard", json.getJSONArray("features").getString(0))
    }

    @Test
    fun parsesAMissingFeaturesArrayAsEmpty() {
        val json = JSONObject()
            .put("device_id", "d")
            .put("name", "n")
            .put("platform", "linux")
            .put("pv", 1)
        assertEquals(emptyList<String>(), DeviceInfo.parse(json)!!.features)
    }

    @Test
    fun rejectsMalformedPayloads() {
        fun json(deviceId: String, name: String, platform: String, pv: Int) = JSONObject()
            .put("device_id", deviceId)
            .put("name", name)
            .put("platform", platform)
            .put("pv", pv)

        assertNull("missing device_id", DeviceInfo.parse(json("", "n", "p", 1)))
        assertNull("missing name", DeviceInfo.parse(json("d", "", "p", 1)))
        assertNull("missing platform", DeviceInfo.parse(json("d", "n", "", 1)))
        assertNull("bad pv", DeviceInfo.parse(json("d", "n", "p", 0)))
        assertNull("negative pv", DeviceInfo.parse(json("d", "n", "p", -1)))
    }

    @Test
    fun thisBuildAdvertisesExactlyTheImplementedFeatures() {
        // The list must stay accurate so peers don't offer features we can't
        // honour. Clipboard is implemented (foreground-only), notification
        // mirroring is implemented (opt-in, phone→computer only); transfers
        // and media control are not.
        val features = DeviceInfo.localFeatures()
        assertEquals(listOf("clipboard", "notifications"), features)
        assertTrue("transfer must not be advertised yet", "transfer" !in features)
        assertTrue("media must not be advertised yet", "media" !in features)
    }
}
