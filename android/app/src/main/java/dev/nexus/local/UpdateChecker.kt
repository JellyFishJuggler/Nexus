package dev.nexus.local

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/** Checks the project's public GitHub tags and notifies once per newer version. */
object UpdateChecker {
    private const val REPO = "JellyFishJuggler/Nexus"
    private const val RELEASES_URL = "https://github.com/$REPO/releases"
    private const val TAGS_URL = "https://api.github.com/repos/$REPO/tags?per_page=100"
    private const val CHANNEL_ID = "updates"
    private const val NOTIFICATION_ID = 4102
    private const val PREFS = "nexus_updates"
    private const val LAST_NOTIFIED = "last_notified_version"
    private val worker = Executors.newSingleThreadExecutor()

    fun check(context: Context) {
        val app = context.applicationContext
        worker.execute {
            val newest = runCatching { fetchNewestVersion() }.getOrNull() ?: return@execute
            val current = BuildConfig.VERSION_NAME
            if (compareVersions(newest, current) <= 0) return@execute

            val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            if (prefs.getString(LAST_NOTIFIED, null) == newest) return@execute
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                ContextCompat.checkSelfPermission(app, android.Manifest.permission.POST_NOTIFICATIONS) !=
                PackageManager.PERMISSION_GRANTED
            ) return@execute

            createChannel(app)
            val openRelease = PendingIntent.getActivity(
                app,
                0,
                Intent(Intent.ACTION_VIEW, Uri.parse(RELEASES_URL)),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val notification = NotificationCompat.Builder(app, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setContentTitle("Nexus update available")
                .setContentText("Version $newest is available. Tap to view version tags.")
                .setStyle(NotificationCompat.BigTextStyle().bigText(
                    "Nexus $newest is available. Tap to view the version tags and update instructions."
                ))
                .setContentIntent(openRelease)
                .setAutoCancel(true)
                .build()
            runCatching {
                NotificationManagerCompat.from(app).notify(NOTIFICATION_ID, notification)
                prefs.edit().putString(LAST_NOTIFIED, newest).apply()
            }
        }
    }

    private fun fetchNewestVersion(): String {
        val connection = URL(TAGS_URL).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 6000
            connection.readTimeout = 6000
            connection.setRequestProperty("Accept", "application/vnd.github+json")
            connection.setRequestProperty("User-Agent", "Nexus-Android-Update-Checker")
            if (connection.responseCode != HttpURLConnection.HTTP_OK) return ""
            val body = connection.inputStream.bufferedReader().use { it.readText() }
            val tags = JSONArray(body)
            return (0 until tags.length())
                .mapNotNull { tags.optJSONObject(it)?.optString("name") }
                .filter { parseVersion(it) != null }
                .maxWithOrNull { a, b -> compareVersions(a, b) }
                .orEmpty()
        } finally {
            connection.disconnect()
        }
    }

    private fun createChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = context.getSystemService(android.app.NotificationManager::class.java)
            manager.createNotificationChannel(
                android.app.NotificationChannel(
                    CHANNEL_ID,
                    "App updates",
                    android.app.NotificationManager.IMPORTANCE_DEFAULT
                ).apply { description = "Notifies when a newer Nexus version is available." }
            )
        }
    }

    private fun parseVersion(value: String): List<Int>? =
        value.trim().removePrefix("v").split('.').takeIf { it.size >= 2 }?.map { part ->
            part.takeWhile(Char::isDigit).takeIf(String::isNotEmpty)?.toIntOrNull() ?: return null
        }

    private fun compareVersions(a: String, b: String): Int {
        val left = parseVersion(a) ?: return -1
        val right = parseVersion(b) ?: return 1
        for (i in 0 until maxOf(left.size, right.size)) {
            val comparison = (left.getOrElse(i) { 0 }).compareTo(right.getOrElse(i) { 0 })
            if (comparison != 0) return comparison
        }
        return 0
    }
}
