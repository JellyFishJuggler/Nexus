package dev.nexus.local.ui.theme

import android.content.Context

/**
 * The user's opt-in choice of the dark theme.
 *
 * Light stays the default so a fresh install always matches the desktop
 * companion; this only records an explicit choice from Settings.
 */
object ThemePreference {
    private const val PREFS = "nexus_ui"
    private const val KEY_DARK = "dark_theme"

    fun isDark(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_DARK, false)

    fun setDark(context: Context, dark: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_DARK, dark)
            .apply()
    }
}
