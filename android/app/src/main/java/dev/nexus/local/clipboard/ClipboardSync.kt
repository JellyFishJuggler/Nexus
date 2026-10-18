package dev.nexus.local.clipboard

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.Executors

/**
 * Plain-text clipboard synchronization — `protocol/clipboard.md`.
 *
 * ## Foreground-only by design
 *
 * Since Android 10 (API 29) only the app that currently has input focus — or
 * the default IME — may read the clipboard; every other read returns `null`.
 * There is no supported way around this (and no attempt is made here to find
 * one). So this class syncs only while Nexus is in the foreground:
 *
 *  - reads happen on window resume, on the framework's clip-changed callback
 *    (delivered only to the focused app), and on a slow poll that catches a
 *    value copied in another app before we regained focus;
 *  - *writes* are unrestricted, so an update from the peer is applied even
 *    when Nexus is in the background.
 *
 * The result is the documented prototype limitation: a copy made on the
 * desktop reaches the phone's clipboard immediately, but a copy made *on the
 * phone* is only picked up and sent while Nexus is open and focused.
 *
 * Pure decision logic (echo/dedup/loop prevention) lives in
 * [ClipboardProtocol]; this class only touches the OS clipboard.
 */
class ClipboardSync(
    context: Context,
    /** Deliver one outbound update; returns false when there is no session. */
    private val send: (text: String, hash: String) -> Boolean
) : ClipboardManager.OnPrimaryClipChangedListener {

    /** UI-facing snapshot. */
    data class Status(
        /** Foreground sync is running (activity resumed). */
        val active: Boolean = false,
        /** A trusted session is ready to carry updates. */
        val connected: Boolean = false,
        /** "sent" / "received" / null — what happened last. */
        val lastDirection: String? = null,
        /** Short preview of the last synced text. */
        val lastPreview: String? = null,
        /** Explanation shown in the UI while running. */
        val note: String = FOREGROUND_NOTE
    )

    private val appContext = context.applicationContext
    private val manager =
        appContext.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    private val handler = Handler(Looper.getMainLooper())
    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "nexus-clipboard").apply { isDaemon = true }
    }
    private val sync = ClipboardProtocol.SyncState()

    private val _status = MutableStateFlow(Status())
    val status: StateFlow<Status> = _status.asStateFlow()

    @Volatile
    private var running = false

    /** Last value we saw locally, so an unchanged poll is a no-op. */
    @Volatile
    private var lastLocal: String? = null

    private val poll = object : Runnable {
        override fun run() {
            if (!running) return
            executor.execute { captureLocal(force = false) }
            handler.postDelayed(this, POLL_INTERVAL_MS)
        }
    }

    // -- lifecycle --------------------------------------------------------

    /** Start foreground syncing (called when the activity is resumed). */
    fun start() {
        if (running) {
            executor.execute { captureLocal(force = false) }
            return
        }
        running = true
        runCatching { manager.addPrimaryClipChangedListener(this) }
        handler.removeCallbacks(poll)
        handler.postDelayed(poll, POLL_INTERVAL_MS)
        // Catch a value copied elsewhere before Nexus regained focus.
        handler.postDelayed({ if (running) executor.execute { captureLocal(force = false) } }, 300)
        _status.value = _status.value.copy(active = true, note = FOREGROUND_NOTE)
        Log.i(TAG, "clipboard sync started (foreground only)")
    }

    /** Stop syncing (called when the activity is paused/stopped). */
    fun stop() {
        if (!running) return
        running = false
        handler.removeCallbacks(poll)
        runCatching { manager.removePrimaryClipChangedListener(this) }
        _status.value = _status.value.copy(active = false)
        Log.i(TAG, "clipboard sync stopped")
    }

    fun close() {
        stop()
        executor.shutdownNow()
    }

    // -- session wiring ---------------------------------------------------

    /** A trusted session became ready: offer the current clipboard to it. */
    fun beginSession() {
        sync.resetSent()
        lastLocal = null
        _status.value = _status.value.copy(connected = true)
        executor.execute { captureLocal(force = false) }
    }

    /** The session ended. */
    fun endSession() {
        _status.value = _status.value.copy(connected = false)
    }

    /** Send the current clipboard right now (UI button). */
    fun pushNow() {
        executor.execute { captureLocal(force = true) }
    }

    /**
     * Test harness only: write [text] to the system clipboard and run it
     * through the normal outbound path, exactly as if the user had copied it
     * in another app. Needed because Android 10+ gives `adb` no supported way
     * to set the clipboard, so scripted Android→desktop tests have no other
     * legitimate trigger.
     */
    fun setLocalAndSend(text: String) {
        executor.execute {
            lastLocal = text
            val ok = runCatching {
                manager.setPrimaryClip(ClipData.newPlainText(CLIP_LABEL, text))
            }.isSuccess
            if (ok) captureLocal(force = true)
        }
    }

    /**
     * Test harness only: log what is actually on the system clipboard now, so
     * a scripted test can assert the applied content (adb cannot read it).
     */
    fun logCurrent() {
        executor.execute {
            val text = readClipboardText()
            Log.i(TAG, "clipboard current: ${text?.let { "\"$it\"" } ?: "<empty/unreadable>"}")
        }
    }

    // -- inbound ----------------------------------------------------------

    /**
     * Handle a `clipboard.update` from the peer.
     *
     * Returns the decision so the caller can answer protocol errors; applies
     * the text locally for [ClipboardProtocol.Inbound.Apply] and
     * [ClipboardProtocol.Inbound.SameContent].
     */
    fun handleRemote(text: String, hash: String): ClipboardProtocol.Inbound {
        val decision = sync.inbound(text, hash, readClipboardText())
        when (decision) {
            is ClipboardProtocol.Inbound.Apply -> {
                if (applyRemote(text)) {
                    sync.noteApplied(hash)
                    publish("received", text)
                }
            }
            is ClipboardProtocol.Inbound.SameContent -> sync.noteApplied(hash)
            else -> Unit
        }
        return decision
    }

    // -- outbound ---------------------------------------------------------

    override fun onPrimaryClipChanged() {
        if (!running) return
        executor.execute { captureLocal(force = true) }
    }

    private fun captureLocal(force: Boolean) {
        if (!running) return
        val text = readClipboardText() ?: return
        if (!force && text == lastLocal) return
        lastLocal = text
        when (val decision = sync.outbound(text)) {
            is ClipboardProtocol.Outbound.Send ->
                if (send(decision.text, decision.hash)) {
                    sync.noteSent(decision.hash)
                    publish("sent", decision.text)
                }
            is ClipboardProtocol.Outbound.TooLarge ->
                Log.i(TAG, "not syncing: ${decision.bytes} bytes exceeds the limit")
            ClipboardProtocol.Outbound.Unchanged -> Unit
        }
    }

    private fun readClipboardText(): String? {
        val clip = runCatching { manager.primaryClip }.getOrNull() ?: return null
        if (clip.itemCount == 0) return null
        // Plain text only: anything else (URI, intent, image) is ignored.
        return clip.getItemAt(0).text?.toString()
    }

    private fun applyRemote(text: String): Boolean {
        lastLocal = text
        val ok = runCatching {
            manager.setPrimaryClip(ClipData.newPlainText(CLIP_LABEL, text))
        }.isSuccess
        if (!ok) Log.w(TAG, "couldn't write the clipboard")
        return ok
    }

    private fun publish(direction: String, text: String) {
        _status.value = _status.value.copy(
            lastDirection = direction,
            lastPreview = preview(text)
        )
    }

    private fun preview(text: String): String {
        val flat = text.replace(Regex("\\s+"), " ").trim()
        return if (flat.length <= 80) flat else flat.take(77) + "…"
    }

    companion object {
        private const val TAG = "NexusClipboard"
        private const val CLIP_LABEL = "Nexus"

        /** Clipboard read/poll interval while the app is in the foreground. */
        const val POLL_INTERVAL_MS = 800L

        /** Shown in the UI to make the prototype limitation explicit. */
        const val FOREGROUND_NOTE =
            "Clipboard sync runs while Nexus is open (Android only lets the " +
                "focused app read the clipboard)."
    }
}
