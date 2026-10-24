package dev.nexus.local.notifications

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Notification mirroring rules — `protocol/notifications.md`.
 *
 * These are the privacy-critical decisions: what leaves the phone, and what is
 * stripped before it does.
 */
class NotificationMapperTest {

    @Test
    fun mapsAppTitleAndBody() {
        val mapped = NotificationMapper.map(
            appLabel = "Messages",
            title = "Alex",
            text = "See you at 6",
            bigText = null,
            hideText = false
        )
        assertEquals(MirroredNotification("Messages", "Alex", "See you at 6"), mapped)
    }

    @Test
    fun expandedTextWinsOverShortText() {
        val mapped = NotificationMapper.map(
            appLabel = "Messages",
            title = "Alex",
            text = "See you…",
            bigText = "See you at 6 by the station",
            hideText = false
        )
        assertEquals("See you at 6 by the station", mapped?.text)
    }

    @Test
    fun hideTextForwardsOnlyAppAndTitle() {
        val mapped = NotificationMapper.map(
            appLabel = "Messages",
            title = "Alex",
            text = "See you at 6",
            bigText = "See you at 6 by the station",
            hideText = true
        )
        assertEquals(MirroredNotification("Messages", "Alex", ""), mapped)
    }

    @Test
    fun emptyNotificationIsDropped() {
        assertNull(
            NotificationMapper.map(
                appLabel = "Messages",
                title = null,
                text = null,
                bigText = null,
                hideText = false
            )
        )
    }

    @Test
    fun titleOnlyIsKeptWhenTextIsHidden() {
        val mapped = NotificationMapper.map(
            appLabel = "Calendar",
            title = "Standup in 5 minutes",
            text = null,
            bigText = null,
            hideText = true
        )
        assertEquals("Standup in 5 minutes", mapped?.title)
        assertEquals("", mapped?.text)
    }

    @Test
    fun newlinesCollapseAndFieldsAreCapped() {
        val mapped = NotificationMapper.map(
            appLabel = "Mail",
            title = "Subject\r\nline",
            text = "a".repeat(NotificationMapper.MAX_TEXT + 50),
            bigText = null,
            hideText = false
        )!!
        assertEquals("Subject line", mapped.title)
        assertEquals(NotificationMapper.MAX_TEXT, mapped.text.length)
    }

    @Test
    fun ownOngoingAndSummaryNotificationsAreNotMirrored() {
        val local = "dev.nexus.local"
        assertFalse(NotificationMapper.shouldMirror(local, local, false, false))
        assertFalse(NotificationMapper.shouldMirror("com.example", local, true, false))
        assertFalse(NotificationMapper.shouldMirror("com.example", local, false, true))
        assertTrue(NotificationMapper.shouldMirror("com.example", local, false, false))
    }
}
