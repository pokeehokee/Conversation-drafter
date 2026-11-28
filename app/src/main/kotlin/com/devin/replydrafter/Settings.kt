package com.devin.replydrafter

import android.content.Context
import android.content.SharedPreferences
import com.devin.replydrafter.core.StyleProfile
import com.devin.replydrafter.core.Tone

enum class BubbleMode(val label: String) {
    WHEN_TYPING("When I'm typing"),
    ALWAYS("Always"),
    OFF("Off (use the accessibility button or the tile)"),
}

/** Everything the app remembers. All of it stays on the phone. */
class Settings(context: Context) {
    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences("reply_drafter", Context.MODE_PRIVATE)

    var bubbleMode: BubbleMode
        get() = enumOr(prefs.getString(KEY_BUBBLE, null), BubbleMode.WHEN_TYPING)
        set(v) = prefs.edit().putString(KEY_BUBBLE, v.name).apply()

    var defaultTone: Tone
        get() = enumOr(prefs.getString(KEY_TONE, null), Tone.AUTO)
        set(v) = prefs.edit().putString(KEY_TONE, v.name).apply()

    /** 1 = a single ready-to-send reply, 3 = three options in separate copy boxes. */
    var options: Int
        get() = prefs.getInt(KEY_OPTIONS, 1)
        set(v) = prefs.edit().putInt(KEY_OPTIONS, v).apply()

    /** How many recent messages to gather, scrolling the chat back if needed. */
    var historyDepth: Int
        get() = prefs.getInt(KEY_DEPTH, 30)
        set(v) = prefs.edit().putInt(KEY_DEPTH, v).apply()

    var learnFromChats: Boolean
        get() = prefs.getBoolean(KEY_LEARN, true)
        set(v) = prefs.edit().putBoolean(KEY_LEARN, v).apply()

    var styleNotes: String
        get() = prefs.getString(KEY_NOTES, "").orEmpty()
        set(v) = prefs.edit().putString(KEY_NOTES, v).apply()

    /** Which AI app drafts the reply. */
    var aiTarget: AiTarget
        get() = enumOr(prefs.getString(KEY_AI, null), AiTarget.CLAUDE)
        set(v) = prefs.edit().putString(KEY_AI, v.name).apply()

    /** Long-press the bubble to draft at once with this chat's last tone, skipping the panel. */
    var longPressDrafts: Boolean
        get() = prefs.getBoolean(KEY_LONG_PRESS, true)
        set(v) = prefs.edit().putBoolean(KEY_LONG_PRESS, v).apply()

    /** The last tone used in one chat. [chatKey] is a hash from ChatKey, never a name. */
    fun toneFor(chatKey: String): Tone? =
        prefs.getString(KEY_TONE_PREFIX + chatKey, null)?.let { n -> Tone.entries.firstOrNull { it.name == n } }

    fun rememberTone(chatKey: String, tone: Tone) {
        prefs.edit().putString(KEY_TONE_PREFIX + chatKey, tone.name).apply()
    }

    var bubbleY: Int
        get() = prefs.getInt(KEY_BUBBLE_Y, -1)
        set(v) = prefs.edit().putInt(KEY_BUBBLE_Y, v).apply()

    fun loadStyle(): StyleProfile = StyleProfile.decode(prefs.getString(KEY_SAMPLES, null), styleNotes)

    fun saveStyle(profile: StyleProfile) {
        prefs.edit().putString(KEY_SAMPLES, profile.encode()).apply()
    }

    private inline fun <reified E : Enum<E>> enumOr(name: String?, fallback: E): E =
        enumValues<E>().firstOrNull { it.name == name } ?: fallback

    private companion object {
        const val KEY_BUBBLE = "bubble_mode"
        const val KEY_TONE = "default_tone"
        const val KEY_OPTIONS = "options"
        const val KEY_LEARN = "learn"
        const val KEY_NOTES = "style_notes"
        const val KEY_SAMPLES = "style_samples"
        const val KEY_BUBBLE_Y = "bubble_y"
        const val KEY_DEPTH = "history_depth"
        const val KEY_AI = "ai_target"
        const val KEY_LONG_PRESS = "long_press_drafts"
        const val KEY_TONE_PREFIX = "tone_"
    }
}
