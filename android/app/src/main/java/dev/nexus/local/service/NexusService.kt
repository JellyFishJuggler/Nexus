package dev.nexus.local.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import dev.nexus.local.MainActivity
import dev.nexus.local.NexusRuntime
import dev.nexus.local.R
import dev.nexus.local.discovery.DiscoveryController
import dev.nexus.local.pairing.PairingManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * Keeps the local link alive while Nexus is not on screen.
 *
 * The service owns the discovery/TLS lifetime (through the process-wide
 * [DiscoveryController]) and shows the low-priority status notification that
 * Android requires for a `connectedDevice` foreground service. Clipboard
 * *reads* remain activity-gated: only [MainActivity] starts/stops the
 * clipboard sync when it resumes/pauses, so this service never reads the
 * clipboard in the background.
 *
 * Notification mirroring is handled by
 * [dev.nexus.local.notifications.NexusNotificationListener]; the service only
 * provides the trusted session it forwards through.
 */
class NexusService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var controller: DiscoveryController

    /** Last pairing prompt we alerted for, so one prompt alerts at most once. */
    private var alertedPromptPeer: String? = null

    override fun onCreate() {
        super.onCreate()
        createChannels()
        controller = NexusRuntime.controller(this)

        promoteToForeground(buildStatusNotification(null, null, emptyList()))
        controller.startNetwork()

        scope.launch {
            combine(
                controller.sessionUi,
                controller.reconnectUi,
                controller.trustedDevices
            ) { session, reconnect, trusted -> Triple(session, reconnect, trusted) }
                .collect { (session, reconnect, trusted) ->
                    updateStatusNotification(session, reconnect, trusted)
                }
        }

        scope.launch {
            controller.pairingUi.collect { ui ->
                if (ui is PairingManager.Ui.Prompt) {
                    alertPairingRequest(ui)
                } else {
                    alertedPromptPeer = null
                    NotificationManagerCompat.from(this@NexusService)
                        .cancel(PAIRING_NOTIFICATION_ID)
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopLink()
            return START_NOT_STICKY
        }
        // Starting again (activity open, boot receiver, system restart) is a
        // no-op beyond keeping the notification current.
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        scope.cancel()
        controller.stopNetwork()
        NotificationManagerCompat.from(this).cancel(PAIRING_NOTIFICATION_ID)
        super.onDestroy()
    }

    private fun stopLink() {
        controller.stopNetwork()
        scope.cancel()
        NotificationManagerCompat.from(this).cancel(PAIRING_NOTIFICATION_ID)
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    // -- status notification ------------------------------------------------

    private fun promoteToForeground(notification: Notification) {
        ServiceCompat.startForeground(
            this,
            STATUS_NOTIFICATION_ID,
            notification,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            } else {
                0
            }
        )
    }

    private fun updateStatusNotification(
        session: DiscoveryController.SessionUi,
        reconnect: DiscoveryController.ReconnectUi,
        trusted: List<dev.nexus.local.trust.TrustedDevice>
    ) {
        val notification = buildStatusNotification(session, reconnect, trusted)
        runCatching {
            NotificationManagerCompat.from(this)
                .notify(STATUS_NOTIFICATION_ID, notification)
        }.onFailure { Log.w(TAG, "couldn't update the status notification") }
    }

    private fun buildStatusNotification(
        session: DiscoveryController.SessionUi?,
        reconnect: DiscoveryController.ReconnectUi?,
        trusted: List<dev.nexus.local.trust.TrustedDevice>
    ): Notification {
        val title: String
        val text: String
        when {
            session?.ready == true -> {
                title = "Connected to ${session.peerName ?: "your device"}"
                text = "Clipboard sync is ready while Nexus is open."
            }

            reconnect is DiscoveryController.ReconnectUi.Connecting -> {
                title = "Reconnecting to ${reconnect.peerName}…"
                text = "Nexus is restoring the local link."
            }

            trusted.isNotEmpty() -> {
                title = "Waiting for a trusted device"
                text = "Nexus reconnects automatically when it appears."
            }

            else -> {
                title = "Nexus is running"
                text = "Open Nexus to pair a device."
            }
        }

        return NotificationCompat.Builder(this, CHANNEL_STATUS)
            .setSmallIcon(R.drawable.ic_stat_nexus)
            .setContentTitle(title)
            .setContentText(text)
            .setSubText("Nexus")
            .setContentIntent(openAppIntent())
            .setOngoing(true)
            .setSilent(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .addAction(0, "Open", openAppIntent())
            .addAction(0, "Stop", stopIntent())
            .build()
    }

    // -- pairing alert ------------------------------------------------------

    /**
     * A pairing prompt needs the user, and it exists only while the pairing
     * window is open. If the app is not on screen, say so once — never include
     * the verification code itself.
     */
    private fun alertPairingRequest(prompt: PairingManager.Ui.Prompt) {
        if (NexusRuntime.activityForeground) return
        if (alertedPromptPeer == prompt.peerDeviceId) return
        alertedPromptPeer = prompt.peerDeviceId

        val notification = NotificationCompat.Builder(this, CHANNEL_ALERTS)
            .setSmallIcon(R.drawable.ic_stat_nexus)
            .setContentTitle("Pairing request")
            .setContentText("Open Nexus to check the code from ${prompt.peerName}.")
            .setContentIntent(openAppIntent())
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_EVENT)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()

        runCatching {
            NotificationManagerCompat.from(this).notify(PAIRING_NOTIFICATION_ID, notification)
        }.onFailure { Log.w(TAG, "couldn't post the pairing notification") }
    }

    // -- plumbing -----------------------------------------------------------

    private fun createChannels() {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_STATUS,
                "Connection status",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows whether Nexus is connected, reconnecting or waiting."
                setShowBadge(false)
            }
        )
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ALERTS,
                "Pairing requests",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "Alerts you when a device asks to pair."
                setShowBadge(false)
            }
        )
    }

    private fun openAppIntent(): PendingIntent {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        return PendingIntent.getActivity(
            this,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun stopIntent(): PendingIntent {
        val intent = Intent(this, NexusService::class.java).setAction(ACTION_STOP)
        return PendingIntent.getService(
            this,
            1,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    companion object {
        private const val TAG = "NexusService"

        const val CHANNEL_STATUS = "nexus_status"
        const val CHANNEL_ALERTS = "nexus_alerts"

        const val STATUS_NOTIFICATION_ID = 1001
        const val PAIRING_NOTIFICATION_ID = 1002

        const val ACTION_STOP = "dev.nexus.local.action.STOP"

        /** Start (or ensure) the foreground service. Safe to call repeatedly. */
        fun start(context: Context) {
            runCatching {
                ContextCompat.startForegroundService(
                    context,
                    Intent(context, NexusService::class.java)
                )
            }.onFailure {
                Log.w(TAG, "couldn't start the foreground service: ${it.javaClass.simpleName}")
            }
        }
    }
}
