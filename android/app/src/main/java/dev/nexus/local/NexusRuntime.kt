package dev.nexus.local

import android.content.Context
import dev.nexus.local.discovery.DiscoveryController

/**
 * Process-wide owner of the [DiscoveryController].
 *
 * The controller used to be created by the activity, which meant a rotation
 * created a second discovery/server instance and the link died with the
 * window. The foreground service (and therefore the local link) now outlives
 * the activity, so the controller is created exactly once per process and
 * shared by both.
 */
object NexusRuntime {
    @Volatile
    private var instance: DiscoveryController? = null

    /**
     * True while a Nexus activity is resumed. Used to avoid posting a pairing
     * alert on top of the full-screen pairing prompt the user is already
     * looking at.
     */
    @Volatile
    var activityForeground: Boolean = false
        private set

    fun setActivityForeground(value: Boolean) {
        activityForeground = value
    }

    /** The process-wide controller, creating it on first use. */
    fun controller(context: Context): DiscoveryController =
        instance ?: synchronized(this) {
            instance ?: DiscoveryController(context.applicationContext).also { instance = it }
        }

    /**
     * The controller if this process already has one. Used by the notification
     * listener, which must never create a controller just to observe.
     */
    fun existing(): DiscoveryController? = instance
}
