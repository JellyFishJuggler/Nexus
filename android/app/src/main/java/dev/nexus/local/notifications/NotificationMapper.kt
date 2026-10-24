package dev.nexus.local.notifications

/**
 * One notification reduced to what the computer needs: which app, a title and
 * the body. Nothing else from the phone (keys, extras, images, actions) leaves
 * the device, and the body is stripped entirely when the user turns on
 * "Hide notification text".
 */
data class MirroredNotification(
    val app: String,
    val title: String,
    val text: String
) {
    /** Identity used for in-memory duplicate suppression. */
    val fingerprint: String get() = "$app\u0000$title\u0000$text"
}

/**
 * Pure mapping/filtering rules for notification mirroring. Kept free of
 * Android framework types so the behaviour is unit-testable.
 */
object NotificationMapper {
    const val MAX_APP = 64
    const val MAX_TITLE = 200
    const val MAX_TEXT = 500

    /**
     * Reduce a posted notification to a [MirroredNotification], or `null` when
     * there is nothing worth mirroring.
     *
     * [title] / [text] / [bigText] are the raw `Notification.EXTRA_*` values.
     * `bigText` wins over `text` when present because it is the full message
     * the user sees when expanding the notification.
     */
    fun map(
        appLabel: String,
        title: CharSequence?,
        text: CharSequence?,
        bigText: CharSequence?,
        hideText: Boolean
    ): MirroredNotification? {
        val app = clean(appLabel, MAX_APP)
        if (app.isEmpty()) return null

        val heading = clean(title?.toString().orEmpty(), MAX_TITLE)
        val bodySource = bigText?.toString()?.takeIf { it.isNotBlank() } ?: text?.toString().orEmpty()
        val body = if (hideText) "" else clean(bodySource, MAX_TEXT)

        if (heading.isEmpty() && body.isEmpty()) return null
        return MirroredNotification(app = app, title = heading, text = body)
    }

    /**
     * Structural filters: never mirror our own notifications, ongoing items
     * (music players, charging, navigation) or group summaries (the individual
     * messages are mirrored instead).
     */
    fun shouldMirror(
        packageName: String,
        localPackage: String,
        ongoing: Boolean,
        groupSummary: Boolean
    ): Boolean = packageName != localPackage && !ongoing && !groupSummary

    private fun clean(raw: String, limit: Int): String =
        raw.replace('\u0000', ' ')
            .replace("\r\n", " ")
            .replace('\n', ' ')
            .replace('\r', ' ')
            .trim()
            .take(limit)
}
