package dev.nexus.local.connection

import dev.nexus.local.protocol.Envelope

/**
 * The application layer above a trusted [Connection].
 *
 * [PairingManager] owns trust policy and the pairing state machine; once a
 * connection is trusted it hands it here for the connection-level handshake
 * and application traffic (`protocol/connection.md`).
 */
interface TrustedSessionHandler {
    /** A trusted connection is ready for the `system.hello` exchange. */
    fun onTrustedSession(conn: Connection)

    /** A non-`pairing.*` message arrived on a trusted connection. */
    fun onTrustedMessage(conn: Connection, env: Envelope)

    /** A trusted connection ended (or moved to a new one). */
    fun onTrustedClosed(conn: Connection, reason: String)
}
