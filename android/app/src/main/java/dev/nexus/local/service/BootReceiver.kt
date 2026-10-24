package dev.nexus.local.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import dev.nexus.local.NexusPrefs

/**
 * Starts the local link after a reboot (and after an app update), when the
 * user has left "Start on boot" enabled.
 *
 * Android permits a `BOOT_COMPLETED` receiver to start a foreground service,
 * so the same [NexusService] that runs while the app is open is used here.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED -> Unit

            else -> return
        }

        if (!NexusPrefs.startOnBoot(context)) {
            Log.i(TAG, "start on boot is disabled; not starting the service")
            return
        }

        Log.i(TAG, "starting Nexus after ${intent.action}")
        NexusService.start(context)
    }

    private companion object {
        const val TAG = "NexusBoot"
    }
}
