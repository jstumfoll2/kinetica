package com.kinetica.keyboard.settings

import android.content.SharedPreferences
import java.nio.file.Files
import java.nio.file.Paths
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A fresh install behaves like the maintainer's own keyboard: every preference row of their
 * settings backup is what an untouched install reads, in code and in the settings screen.
 *
 * To move the defaults, paste the `pref` rows of a new backup into [SHIPPED] (key, type, value),
 * leaving out the internal state rows (synced language, chord seeding, the remembered one-handed
 * side) and never the `word` or `chord` rows.
 */
class ShippedDefaultsTest {

    @Test
    fun anUntouchedKeyboardReadsTheShippedValues() {
        val prefs = RecordingPrefs(HUE_SET)
        val config = KeyboardConfig.from(prefs)
        val checked = SHIPPED.filter { (key, _) -> prefs.defaults[key] != null && key != Prefs.THEME_HUE }
        for ((key, value) in checked) {
            assertEquals(key, value, render(prefs.defaults[key]))
        }
        assertTrue("too few keys read", checked.size > 40)
        // Read through a null default, so not recorded above.
        assertEquals(listOf("en", "es"), config.enabledLanguages)
        assertEquals(20, config.dragHandleDp)
    }

    @Test
    fun theSettingsScreenShowsTheShippedValues() {
        // Files.readString is missing from the Android compile classpath.
        val path = listOf(Paths.get("src/main/res/xml/keyboard_prefs.xml"), Paths.get("app/src/main/res/xml/keyboard_prefs.xml"))
            .first { Files.exists(it) }
        val xml = Files.newBufferedReader(path).use { it.readText() }
        val missing = ArrayList<String>()
        for ((key, value) in SHIPPED) {
            val shown = Regex("""android:key="$key"[^>]*?android:defaultValue="([^"]*)"""", RegexOption.DOT_MATCHES_ALL)
                .find(xml)?.groupValues?.get(1)
            when {
                shown == null -> missing.add(key)
                shown.startsWith("@") -> Unit // a resource; enabled languages has its own test
                else -> assertEquals(key, value, shown)
            }
        }
        // Rows with no settings-screen default must all have been checked in code.
        val prefs = RecordingPrefs(HUE_SET)
        KeyboardConfig.from(prefs)
        assertEquals(emptyList<String>(), missing.filter { prefs.defaults[it] == null })
    }

    private fun render(value: Any?): String =
        if (value is Collection<*>) value.joinToString(",") else value.toString()

    private companion object {
        // A stored hue keeps KeyboardConfig off the colour-string migration, which calls
        // android.graphics.Color; the hue default is checked against the settings screen instead.
        val HUE_SET: Map<String, Any> = mapOf(Prefs.THEME_HUE to 233)

        /** The `pref` rows of the maintainer's settings backup of 2026-10-07. */
        val SHIPPED: List<Pair<String, String>> = """
        pref_alternate_swipes bool true
        pref_apostrophe_key bool true
        pref_auto_capitalize bool true
        pref_auto_detect_language bool true
        pref_autocorrect_level string normal
        pref_autospace bool false
        pref_autospace_delay_ms int 300
        pref_autospace_retract_ms int 600
        pref_autospace_tap_delay_ms int 300
        pref_autospace_tapped_words bool false
        pref_backspace_char_slide bool false
        pref_badges_while_adjusting bool true
        pref_british_spelling bool false
        pref_comma_mode string keep
        pref_double_space_period bool false
        pref_drag_handle_dp int 20
        pref_edit_from_menu bool false
        pref_emoji_key bool true
        pref_enabled_languages set en,es
        pref_enter_action bool true
        pref_enter_alternates string ? ! ,
        pref_key_arrangement string qwerty
        pref_keyboard_height_pct int 20
        pref_keyboard_height_pct_landscape int 19
        pref_landscape_arrangement string split
        pref_landscape_split_gap_pct int 30
        pref_language string en
        pref_layout_mode string full
        pref_learn_phrases bool false
        pref_long_press_ms int 500
        pref_next_word bool true
        pref_no_primary bool false
        pref_number_priority bool true
        pref_number_row bool true
        pref_peck_mode bool false
        pref_period_mode string keep
        pref_plain_letter_alternates bool false
        pref_popup_shape string row
        pref_recent_words bool false
        pref_reinforce_increment string medium
        pref_reinforce_step_dp int 24
        pref_retype_avoids_rejected bool true
        pref_retype_button bool false
        pref_retype_button_dp int 34
        pref_spacebar_step_dp int 20
        pref_spacebar_word_slide bool false
        pref_spaceless_space bool false
        pref_suggestion_bar_dp int 44
        pref_sync_system_language bool true
        pref_theme_brightness string dark
        pref_theme_hue int 233
        pref_theme_mode string default
        pref_tidy_spaces bool false
        pref_trail_color string rainbow
        pref_trail_color_custom_hue int 200
        pref_typing_speed bool false
        pref_vibration bool true
        pref_vibration_intensity int 2
        pref_word_ends_on_space bool true
        pref_zen_mode bool false
        """.trimIndent().lines().filter { it.isNotBlank() }.map { line ->
            val (key, _, value) = line.split(" ", limit = 3)
            key to value
        }
    }

    /** Read-only preferences that answer every unset key with its default and remember it. */
    private class RecordingPrefs(private val values: Map<String, Any>) : SharedPreferences {
        val defaults = HashMap<String, Any?>()

        private fun <T> read(key: String?, defValue: T): T {
            @Suppress("UNCHECKED_CAST")
            val stored = values[key] as T?
            if (key != null && key !in defaults) defaults[key] = defValue
            return stored ?: defValue
        }

        override fun getAll(): Map<String, *> = values
        override fun getString(key: String?, defValue: String?): String? = read(key, defValue)
        override fun getStringSet(key: String?, defValues: Set<String>?): Set<String>? = read(key, defValues)
        override fun getInt(key: String?, defValue: Int): Int = read(key, defValue)
        override fun getLong(key: String?, defValue: Long): Long = read(key, defValue)
        override fun getFloat(key: String?, defValue: Float): Float = read(key, defValue)
        override fun getBoolean(key: String?, defValue: Boolean): Boolean = read(key, defValue)
        override fun contains(key: String?): Boolean = values.containsKey(key)
        override fun edit(): SharedPreferences.Editor = throw UnsupportedOperationException()
        override fun registerOnSharedPreferenceChangeListener(
            listener: SharedPreferences.OnSharedPreferenceChangeListener?,
        ) = Unit
        override fun unregisterOnSharedPreferenceChangeListener(
            listener: SharedPreferences.OnSharedPreferenceChangeListener?,
        ) = Unit
    }
}
