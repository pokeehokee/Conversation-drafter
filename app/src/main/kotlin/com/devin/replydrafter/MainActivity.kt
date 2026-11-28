package com.devin.replydrafter

import android.app.Activity
import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import android.widget.AdapterView
import com.devin.replydrafter.core.StyleProfile
import com.devin.replydrafter.core.Tone
import android.provider.Settings as SystemSettings

/** Setup and settings. Plain platform views, so the app has no library dependencies. */
class MainActivity : Activity() {

    private lateinit var settings: Settings
    private lateinit var serviceStatus: TextView
    private lateinit var aiStatus: TextView
    private val aiButtons = mutableListOf<Pair<AiTarget, RadioButton>>()
    private lateinit var sampleCount: TextView

    private val density by lazy { resources.displayMetrics.density }
    private fun dp(v: Int) = (v * density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settings = Settings(this)

        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(24), dp(20), dp(40))
        }
        val scroll = ScrollView(this).apply { addView(col); fitsSystemWindows = true }
        setContentView(scroll)

        col.addView(heading("Reply Drafter", 24f))
        col.addView(body("Reads the chat you're in, hands it to the AI app you already use (Claude, ChatGPT, Gemini and others) with your writing style, and pastes the reply back into the message box. You always press send yourself."))

        // 1. Turn it on
        col.addView(section("1. Turn it on"))
        serviceStatus = body("")
        col.addView(serviceStatus)
        col.addView(body(
            "Android blocks accessibility for apps installed outside the Play Store until you allow it once:\n" +
                "a) Tap Open app info below, then the ⋮ menu (top right), then Allow restricted settings, and confirm.\n" +
                "b) Tap Open Accessibility, find Reply Drafter under Downloaded apps, and turn it on.\n" +
                "If you don't see Allow restricted settings, try step b first; Android shows the menu item after one blocked try.",
        ))
        col.addView(row(
            button("Open app info") {
                startActivity(Intent(SystemSettings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
            },
            button("Open Accessibility") { startActivity(Intent(SystemSettings.ACTION_ACCESSIBILITY_SETTINGS)) },
        ))

        // 2. AI app
        col.addView(section("2. Your AI app"))
        col.addView(body("Pick the AI app you already pay for or trust. Drafts open in it, on your own account. Nothing goes through any other server."))
        val ais = RadioGroup(this)
        AiTarget.entries.forEachIndexed { i, t ->
            val rb = RadioButton(this).apply { id = 4000 + i; isChecked = settings.aiTarget == t }
            aiButtons += t to rb
            ais.addView(rb)
        }
        ais.setOnCheckedChangeListener { _, id ->
            settings.aiTarget = AiTarget.entries[id - 4000]
            refresh()
        }
        col.addView(ais)
        aiStatus = body("")
        col.addView(aiStatus)
        col.addView(Switch(this).apply {
            text = "Open the AI app in split screen, under the chat"
            isChecked = settings.splitScreen
            setOnCheckedChangeListener { _, on -> settings.splitScreen = on }
        })
        col.addView(body("Works on most phones with Android 12L or later, if the AI app allows split screen. Otherwise it opens full screen as before."))

        // 3. How to open it
        col.addView(section("3. How to open it"))
        col.addView(body("Bubble: tap the ✍ bubble on the right edge while you're in a chat (drag it up or down to move it). " +
            "Long-press it to draft at once with that chat's last tone.\n" +
            "Quick Settings: add the Draft reply tile, then tap it from any chat.\n" +
            "Accessibility button or gesture: Android adds one when the service is on, if your phone uses it."))
        val modes = RadioGroup(this)
        BubbleMode.entries.forEachIndexed { i, m ->
            modes.addView(RadioButton(this).apply { id = 1000 + i; text = "Bubble: ${m.label}"; isChecked = settings.bubbleMode == m })
        }
        modes.setOnCheckedChangeListener { _, id ->
            settings.bubbleMode = BubbleMode.entries[id - 1000]
            ReplyDrafterService.instance?.applyBubbleMode()
        }
        col.addView(modes)
        col.addView(Switch(this).apply {
            text = "Long-press the bubble to draft right away"
            isChecked = settings.longPressDrafts
            setOnCheckedChangeListener { _, on -> settings.longPressDrafts = on }
        })
        col.addView(body("When you come back to the chat, a Paste reply pill shows above the message box. Copy the reply in your AI app first, then tap it."))

        // 4. Replies
        col.addView(section("4. Replies"))
        col.addView(body("Default tone. Each chat remembers the last tone you used there:"))
        col.addView(Spinner(this).apply {
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, Tone.entries.map { it.label })
            setSelection(Tone.entries.indexOf(settings.defaultTone))
            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                    settings.defaultTone = Tone.entries[pos]
                }
                override fun onNothingSelected(p: AdapterView<*>?) = Unit
            }
        })
        val opts = RadioGroup(this)
        opts.addView(RadioButton(this).apply { id = 2001; text = "One reply, ready to copy"; isChecked = settings.options == 1 })
        opts.addView(RadioButton(this).apply { id = 2003; text = "Three options, each in its own copy box"; isChecked = settings.options == 3 })
        opts.setOnCheckedChangeListener { _, id -> settings.options = id - 2000 }
        col.addView(opts)

        col.addView(body("How far back to read. The chat scrolls up on its own to collect older messages, then scrolls back down:"))
        val depths = RadioGroup(this).apply { orientation = RadioGroup.HORIZONTAL }
        HISTORY_DEPTHS.forEach { n ->
            depths.addView(RadioButton(this).apply { id = 3000 + n; text = "$n"; isChecked = settings.historyDepth == n })
        }
        depths.setOnCheckedChangeListener { _, id -> settings.historyDepth = id - 3000 }
        col.addView(depths)
        col.addView(body("Messages are read only for the draft and then dropped. Only your own messages are kept, to learn your style."))

        // 5. Your style
        col.addView(section("5. Your writing style"))
        col.addView(Switch(this).apply {
            text = "Learn from my own messages in chats I draft from"
            isChecked = settings.learnFromChats
            setOnCheckedChangeListener { _, on -> settings.learnFromChats = on }
        })
        sampleCount = body("")
        col.addView(sampleCount)

        col.addView(body("Notes on how you write (e.g. \"all lowercase, few emojis, short, dry humor\"):"))
        val notes = edit(settings.styleNotes, lines = 3)
        col.addView(notes)
        col.addView(button("Save notes") {
            settings.styleNotes = notes.text.toString()
            toast("Saved.")
        })

        col.addView(body("Paste some messages you've sent, one per line, to teach your voice faster:"))
        val samples = edit("", lines = 5)
        col.addView(samples)
        col.addView(row(
            button("Add samples") {
                val p = settings.loadStyle()
                val n = p.learn(StyleProfile.splitPasted(samples.text.toString()))
                settings.saveStyle(p)
                samples.setText("")
                refresh()
                toast("Added $n.")
            },
            button("Forget learned style") {
                val p = settings.loadStyle().apply { clear() }
                settings.saveStyle(p)
                refresh()
                toast("Cleared.")
            },
        ))

        col.addView(section("Privacy"))
        col.addView(body("The app reads a chat only when you tap it. Your style samples and notes stay on this phone. " +
            "The chat and a few of your samples go to your AI app only when you press send there. " +
            "If an app hides its text, a screenshot of the chat is shared instead. " +
            "Settings, your learned style and per-chat tones (kept as codes, not names) are included in your phone's backup, so they come back after a reinstall. Chats are never backed up."))
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        serviceStatus.text = if (ReplyDrafterService.instance != null) "Status: on ✓" else "Status: off"
        for ((t, rb) in aiButtons) {
            val installed = AiHandoff.isInstalled(this, t)
            rb.text = if (t.packageName == null || installed) t.label else "${t.label} (not installed)"
            rb.isEnabled = installed || rb.isChecked
        }
        val ai = settings.aiTarget
        aiStatus.text = when {
            ai == AiTarget.ASK -> "Android's share sheet opens each time, so you can pick any app."
            AiHandoff.isInstalled(this, ai) -> "${ai.label} found ✓. Drafts open in it, on your own plan."
            else -> "${ai.label} isn't installed, so the share sheet opens instead."
        }
        sampleCount.text = "Learned samples: ${settings.loadStyle().samples.size}"
    }

    // ------------------------------------------------------------ view helpers

    private fun heading(t: String, size: Float) = TextView(this).apply {
        text = t; textSize = size; setTypeface(typeface, Typeface.BOLD)
    }

    private fun section(t: String) = heading(t, 18f).apply { setPadding(0, dp(24), 0, dp(6)) }

    private fun body(t: String) = TextView(this).apply {
        text = t; textSize = 15f; setPadding(0, dp(4), 0, dp(4))
    }

    private fun button(t: String, onClick: () -> Unit) = Button(this).apply {
        text = t
        isAllCaps = false
        setOnClickListener { onClick() }
    }

    private fun row(vararg views: View) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        views.forEach { addView(it, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)) }
    }

    private fun edit(initial: String, lines: Int) = EditText(this).apply {
        setText(initial)
        minLines = lines
        gravity = Gravity.TOP or Gravity.START
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    private companion object {
        val HISTORY_DEPTHS = listOf(15, 30, 60, 100)
    }
}
