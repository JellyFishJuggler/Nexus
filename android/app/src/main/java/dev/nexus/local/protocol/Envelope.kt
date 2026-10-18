package dev.nexus.local.protocol

import org.json.JSONException
import org.json.JSONObject
import java.util.UUID

/**
 * Control message envelope per protocol/framing.md:
 * `{v, type, id, ts, payload}`.
 */
data class Envelope(
    val version: Int,
    val type: String,
    val id: String,
    val ts: Long,
    val payload: JSONObject
) {
    fun encode(): String = JSONObject().apply {
        put("v", version)
        put("type", type)
        put("id", id)
        put("ts", ts)
        put("payload", payload)
    }.toString()

    companion object {
        /** Protocol version we speak (see protocol/README.md). */
        const val PROTOCOL_VERSION = 1

        private val TYPE_PATTERN = Regex("[a-z]+\\.[a-z_]+")

        fun create(type: String, payload: JSONObject = JSONObject()): Envelope =
            Envelope(
                version = PROTOCOL_VERSION,
                type = type,
                id = UUID.randomUUID().toString(),
                ts = System.currentTimeMillis() / 1000,
                payload = payload
            )

        /**
         * Parse and validate a CONTROL body.
         * @throws EnvelopeException on any malformed/invalid envelope
         * (the caller answers `system.error` or closes per framing.md).
         */
        fun parse(body: ByteArray): Envelope {
            val text = try {
                String(body, Charsets.UTF_8)
            } catch (e: Exception) {
                throw EnvelopeException("control body is not valid UTF-8")
            }
            val json = try {
                JSONObject(text)
            } catch (e: JSONException) {
                throw EnvelopeException("control body is not valid JSON")
            }
            val version = json.optInt("v", -1)
            val type = json.optString("type", "")
            val id = json.optString("id", "")
            val ts = json.optLong("ts", -1)
            if (version != PROTOCOL_VERSION) {
                throw EnvelopeException("unsupported envelope version $version")
            }
            if (!TYPE_PATTERN.matches(type)) {
                throw EnvelopeException("invalid message type '$type'")
            }
            if (id.isEmpty() || ts < 0) {
                throw EnvelopeException("missing id/ts")
            }
            val payload = json.optJSONObject("payload")
                ?: throw EnvelopeException("missing payload object")
            return Envelope(version, type, id, ts, payload)
        }
    }
}

/** Malformed envelope (invalid_payload path, not a connection close). */
class EnvelopeException(message: String) : Exception(message)
