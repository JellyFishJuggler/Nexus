package dev.nexus.local.transfer

import android.content.Context
import android.net.Uri
import android.util.Log
import dev.nexus.local.connection.Connection
import dev.nexus.local.protocol.Envelope
import dev.nexus.local.protocol.Frames
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * File transfer (protocol/transfer.md) on Android.
 *
 * Receiving works anywhere the foreground service is alive; sending requires
 * an established session. Files land in the app's own `Downloads` folder so no
 * storage permission is needed on any supported Android version.
 */
class FileTransferManager(
    private val context: Context,
    private val sender: (Envelope) -> Boolean,
    private val incomingDir: () -> File
) {
    interface Listener {
        fun onTransferUpdate(state: TransferState)
    }

    var listener: Listener? = null

    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "nexus-transfer").apply { isDaemon = true }
    }
    private val transfers = ConcurrentHashMap<String, TransferState>()
    private val outSlotBusy = AtomicBoolean(false)
    private val inSlotBusy = AtomicBoolean(false)

    data class TransferState(
        val id: String,
        val name: String,
        val size: Long,
        val sha256: String,
        val direction: Direction,
        val uri: Uri? = null,
        val destFile: File? = null,
        val partFile: File? = null,
        var received: Long = 0,
        var state: State = State.Pending
    ) {
        val progress: Float
            get() = if (size <= 0) 0f else (received.toFloat() / size).coerceIn(0f, 1f)
    }

    enum class Direction { OUTGOING, INCOMING }

    enum class State { Pending, Accepted, Transferring, Completed, Rejected, Cancelled, Failed }

    /** Handle a `file.*` control message. Returns true when it was consumed. */
    fun handleMessage(env: Envelope, conn: Connection): Boolean = when (env.type) {
        "file.offer" -> { handleOffer(env, conn); true }
        "file.accept" -> { handleAccept(env); true }
        "file.reject" -> { handleReject(env); true }
        "file.cancel" -> { handleCancel(env); true }
        "file.complete" -> { handleComplete(env); true }
        "file.progress" -> true // UI only
        else -> false
    }

    /** Handle a DATA frame for an accepted transfer (framing.md). */
    fun handleDataFrame(transferId: UUID, chunk: ByteArray, conn: Connection) {
        val t = transfers[transferId.toString()]
        if (t == null || t.direction != Direction.INCOMING || t.state != State.Transferring) {
            // DATA for an unknown/not-accepted transfer is a protocol violation.
            Log.w(TAG, "DATA frame for an unknown transfer; closing")
            conn.close()
            return
        }
        val part = t.partFile ?: return
        try {
            FileOutputStream(part, true).use { it.write(chunk) }
            t.received += chunk.size
            if (t.received > t.size) {
                failIncoming(t, part, "integrity")
                return
            }
            notifyUpdate(t)
            if (t.received == t.size) finishIncoming(t, part)
        } catch (e: Exception) {
            Log.w(TAG, "write failed: ${e.javaClass.simpleName}")
            failIncoming(t, part, "io")
        }
    }

    /** Offer a local file to the peer. Returns the transfer id, or null. */
    fun offerFile(uri: Uri): String? {
        if (outSlotBusy.getAndSet(true)) return null
        val id = UUID.randomUUID().toString()
        val name = displayName(uri) ?: "file"
        val size = fileSize(uri) ?: 0L
        if (size <= 0) {
            outSlotBusy.set(false)
            return null
        }
        val hash = sha256OfUri(uri)
        if (hash == null) {
            outSlotBusy.set(false)
            return null
        }
        val t = TransferState(
            id = id,
            name = name,
            size = size,
            sha256 = hash,
            direction = Direction.OUTGOING,
            uri = uri
        )
        transfers[id] = t
        val offer = Envelope.create(
            "file.offer",
            JSONObject()
                .put("transfer_id", id)
                .put("name", name)
                .put("size", size)
                .put("sha256", hash)
        )
        if (!sender(offer)) {
            transfers.remove(id)
            outSlotBusy.set(false)
            return null
        }
        notifyUpdate(t)
        return id
    }

    fun transfer(id: String): TransferState? = transfers[id]

    fun recent(): List<TransferState> = transfers.values.sortedByDescending { it.received }

    // -- inbound -----------------------------------------------------------

    private fun handleOffer(env: Envelope, conn: Connection) {
        val p = env.payload
        val id = p.optString("transfer_id")
        val name = p.optString("name")
        val size = p.optLong("size")
        val sha = p.optString("sha256")
        if (id.isEmpty() || name.isEmpty() || size <= 0 || sha.isEmpty()) {
            conn.sendSystemError("invalid_payload", "invalid file.offer")
            return
        }
        if (inSlotBusy.getAndSet(true)) {
            sender(
                Envelope.create(
                    "file.reject",
                    JSONObject().put("transfer_id", id).put("code", "busy")
                )
            )
            return
        }
        val dir = incomingDir()
        if (!dir.exists() && !dir.mkdirs()) {
            inSlotBusy.set(false)
            sender(
                Envelope.create(
                    "file.reject",
                    JSONObject().put("transfer_id", id).put("code", "io")
                )
            )
            return
        }
        val safe = sanitize(name)
        val dest = uniqueFile(dir, safe)
        val part = File(dir, ".${dest.name}.part")
        part.delete()
        val t = TransferState(
            id = id,
            name = dest.name,
            size = size,
            sha256 = sha.lowercase(),
            direction = Direction.INCOMING,
            destFile = dest,
            partFile = part,
            state = State.Transferring
        )
        transfers[id] = t
        notifyUpdate(t)
        sender(Envelope.create("file.accept", JSONObject().put("transfer_id", id)))
    }

    private fun finishIncoming(t: TransferState, part: File) {
        val actual = sha256OfFile(part)
        if (actual != t.sha256) {
            failIncoming(t, part, "integrity")
            return
        }
        val dest = t.destFile
        if (dest == null || !part.renameTo(dest)) {
            failIncoming(t, part, "io")
            return
        }
        inSlotBusy.set(false)
        t.state = State.Completed
        notifyUpdate(t)
        sender(
            Envelope.create(
                "file.complete",
                JSONObject().put("transfer_id", t.id).put("sha256", t.sha256)
            )
        )
    }

    private fun failIncoming(t: TransferState, part: File, code: String) {
        part.delete()
        inSlotBusy.set(false)
        t.state = if (code == "integrity") State.Failed else State.Failed
        notifyUpdate(t)
        sender(
            Envelope.create(
                "file.cancel",
                JSONObject().put("transfer_id", t.id).put("code", code)
            )
        )
    }

    // -- outbound ----------------------------------------------------------

    private fun handleAccept(env: Envelope) {
        val id = env.payload.optString("transfer_id")
        val t = transfers[id] ?: return
        if (t.direction != Direction.OUTGOING) return
        t.state = State.Transferring
        notifyUpdate(t)
        executor.execute { sendFile(t) }
    }

    private fun sendFile(t: TransferState) {
        val uri = t.uri
        val conn = activeConnection
        if (uri == null || conn == null) {
            outSlotBusy.set(false)
            t.state = State.Failed
            notifyUpdate(t)
            return
        }
        try {
            val out = conn.transferOutputStream()
            val tid = UUID.fromString(t.id)
            context.contentResolver.openInputStream(uri)?.use { input ->
                val buf = ByteArray(65536)
                while (true) {
                    val n = input.read(buf)
                    if (n <= 0) break
                    val chunk = if (n == buf.size) buf else buf.copyOf(n)
                    Frames.writeData(out, tid, chunk)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "send failed: ${e.javaClass.simpleName}")
            outSlotBusy.set(false)
            t.state = State.Failed
            notifyUpdate(t)
            runCatching {
                sender(
                    Envelope.create(
                        "file.cancel",
                        JSONObject().put("transfer_id", t.id).put("code", "io")
                    )
                )
            }
        }
    }

    private fun handleReject(env: Envelope) {
        val id = env.payload.optString("transfer_id")
        val t = transfers[id] ?: return
        outSlotBusy.set(false)
        t.state = State.Rejected
        notifyUpdate(t)
    }

    private fun handleCancel(env: Envelope) {
        val id = env.payload.optString("transfer_id")
        val t = transfers[id] ?: return
        if (t.direction == Direction.INCOMING) {
            t.partFile?.delete()
            inSlotBusy.set(false)
        } else {
            outSlotBusy.set(false)
        }
        t.state = State.Cancelled
        notifyUpdate(t)
    }

    private fun handleComplete(env: Envelope) {
        val id = env.payload.optString("transfer_id")
        val t = transfers[id] ?: return
        if (t.direction != Direction.OUTGOING) return
        outSlotBusy.set(false)
        t.state = State.Completed
        notifyUpdate(t)
    }

    // -- helpers -----------------------------------------------------------

    private fun notifyUpdate(t: TransferState) {
        listener?.onTransferUpdate(t.copy())
    }

    private fun sanitize(name: String): String {
        var n = name.substringAfterLast('/').substringAfterLast('\\')
        n = n.replace(Regex("[\\u0000-\\u001f\\u007f]"), "_")
        n = n.trim().trimEnd('.', ' ')
        if (n.isEmpty() || n == "." || n == "..") n = "file"
        return n
    }

    private fun uniqueFile(dir: File, name: String): File {
        var candidate = File(dir, name)
        if (!candidate.exists()) return candidate
        val base = name.substringBeforeLast('.', name)
        val ext = if (name.contains('.')) "." + name.substringAfterLast('.') else ""
        var i = 1
        while (candidate.exists()) {
            candidate = File(dir, "$base ($i)$ext")
            i++
        }
        return candidate
    }

    private fun displayName(uri: Uri): String? {
        if (uri.scheme == "file") return uri.path?.let { File(it).name }
        runCatching {
            context.contentResolver.query(
                uri,
                arrayOf(android.provider.OpenableColumns.DISPLAY_NAME),
                null,
                null,
                null
            )?.use { c ->
                if (c.moveToFirst()) {
                    val idx = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                    if (idx >= 0) return c.getString(idx)
                }
            }
        }
        return uri.lastPathSegment
    }

    private fun fileSize(uri: Uri): Long? {
        if (uri.scheme == "file") return uri.path?.let { File(it).length() }
        runCatching {
            context.contentResolver.query(
                uri,
                arrayOf(android.provider.OpenableColumns.SIZE),
                null,
                null,
                null
            )?.use { c ->
                if (c.moveToFirst()) {
                    val idx = c.getColumnIndex(android.provider.OpenableColumns.SIZE)
                    if (idx >= 0 && !c.isNull(idx)) return c.getLong(idx)
                }
            }
        }
        return null
    }

    private fun sha256OfUri(uri: Uri): String? = runCatching {
        val md = MessageDigest.getInstance("SHA-256")
        context.contentResolver.openInputStream(uri)?.use { input ->
            val buf = ByteArray(8192)
            while (true) {
                val n = input.read(buf)
                if (n <= 0) break
                md.update(buf, 0, n)
            }
        } ?: return null
        md.digest().toHex()
    }.getOrNull()

    private fun sha256OfFile(file: File): String = runCatching {
        val md = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input ->
            val buf = ByteArray(8192)
            while (true) {
                val n = input.read(buf)
                if (n <= 0) break
                md.update(buf, 0, n)
            }
        }
        md.digest().toHex()
    }.getOrDefault("")

    private fun ByteArray.toHex(): String {
        val sb = StringBuilder(size * 2)
        for (b in this) sb.append(String.format("%02x", b.toInt() and 0xff))
        return sb.toString()
    }

    companion object {
        private const val TAG = "FileTransferManager"

        /** The session the transfer layer sends on; set by the runtime. */
        @Volatile
        var activeConnection: Connection? = null
    }
}
