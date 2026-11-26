package com.devin.replydrafter

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import com.devin.replydrafter.core.Tone
import kotlin.math.abs

/**
 * The floating bubble and the small panel it opens. Both are accessibility overlays, so they need
 * no "draw over other apps" permission, and neither takes keyboard focus away from the chat.
 */
class Overlay(
    private val context: Context,
    private val settings: Settings,
    private val onDraft: (Tone) -> Unit,
    private val onPaste: () -> Unit,
) {
    private val wm = context.getSystemService(WindowManager::class.java)
    private val density = context.resources.displayMetrics.density
    private fun dp(v: Int) = (v * density).toInt()

    private var bubble: View? = null
    private var panel: View? = null
    private var bubbleWanted = false
    private var selectedTone: Tone = settings.defaultTone

    val isPanelOpen: Boolean get() = panel != null

    private fun params(w: Int, h: Int, gravity: Int, y: Int) = WindowManager.LayoutParams(
        w, h,
        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
        PixelFormat.TRANSLUCENT,
    ).apply {
        this.gravity = gravity
        this.y = y
    }

    // ------------------------------------------------------------ bubble

    fun showBubble() {
        bubbleWanted = true
        if (bubble != null || settings.bubbleMode == BubbleMode.OFF) return
        val size = dp(48)
        val v = TextView(context).apply {
            text = "✍"
            textSize = 20f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(ACCENT) }
            elevation = dp(6).toFloat()
            contentDescription = "Draft a reply with Claude"
            alpha = 0.92f
        }
        val startY = settings.bubbleY.takeIf { it >= 0 } ?: (context.resources.displayMetrics.heightPixels * 0.35).toInt()
        val lp = params(size, size, Gravity.TOP or Gravity.END, startY).apply { x = dp(4) }
        attachDrag(v, lp)
        wm.addView(v, lp)
        bubble = v
    }

    fun hideBubble() {
        bubbleWanted = false
        bubble?.let { wm.removeView(it) }
        bubble = null
    }

    @SuppressLint("ClickableViewAccessibility") // performClick is called on tap below
    private fun attachDrag(v: View, lp: WindowManager.LayoutParams) {
        var downRawY = 0f
        var startY = 0
        var moved = false
        v.setOnTouchListener { view, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { downRawY = e.rawY; startY = lp.y; moved = false; true }
                MotionEvent.ACTION_MOVE -> {
                    val dy = (e.rawY - downRawY).toInt()
                    if (abs(dy) > dp(6)) moved = true
                    if (moved) { lp.y = (startY + dy).coerceAtLeast(0); wm.updateViewLayout(view, lp) }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (moved) settings.bubbleY = lp.y else view.performClick()
                    true
                }
                else -> false
            }
        }
        v.setOnClickListener { togglePanel() }
    }

    // ------------------------------------------------------------ panel

    fun togglePanel() = if (panel != null) hidePanel() else showPanel()

    fun showPanel() {
        if (panel != null) return
        selectedTone = settings.defaultTone
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(14))
            background = GradientDrawable().apply { cornerRadius = dp(18).toFloat(); setColor(PANEL_BG) }
            elevation = dp(10).toFloat()
        }
        root.addView(label("Reply with Claude", 17f, bold = true))
        root.addView(label("Tone", 13f, color = MUTED).apply { setPadding(0, dp(10), 0, dp(6)) })

        val chips = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        Tone.entries.forEach { tone ->
            chip(tone.label).also { c ->
                c.setOnClickListener {
                    selectedTone = tone
                    refreshChips(chips)
                }
                c.tag = tone
                chips.addView(c)
            }
        }
        refreshChips(chips)
        root.addView(HorizontalScrollView(context).apply { isHorizontalScrollBarEnabled = false; addView(chips) })

        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(14), 0, 0)
        }
        row.addView(button("Draft in Claude", primary = true) { onDraft(selectedTone) }, weighted())
        row.addView(spacer())
        row.addView(button("Paste reply", primary = false) { onPaste() }, weighted())
        root.addView(row)
        root.addView(
            label("Nothing is sent for you. You send from Claude, then paste here and send yourself.", 12f, color = MUTED)
                .apply { setPadding(0, dp(10), 0, 0) },
        )
        root.addView(
            label("Close", 14f, color = ACCENT).apply {
                gravity = Gravity.END
                setPadding(0, dp(8), 0, 0)
                setOnClickListener { hidePanel() }
            },
        )

        val width = (context.resources.displayMetrics.widthPixels * 0.92).toInt()
        val lp = params(width, WindowManager.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.CENTER_HORIZONTAL, dp(72))
        wm.addView(root, lp)
        panel = root
    }

    fun hidePanel() {
        panel?.let { wm.removeView(it) }
        panel = null
    }

    /** Takes both overlays off screen so a fallback screenshot shows only the chat. */
    fun hideAllForCapture() {
        val keepBubble = bubbleWanted
        hidePanel()
        bubble?.let { wm.removeView(it) }
        bubble = null
        bubbleWanted = keepBubble
    }

    fun restoreAfterCapture() {
        if (bubbleWanted) showBubble()
    }

    fun removeAll() {
        hidePanel()
        hideBubble()
    }

    // ------------------------------------------------------------ small view helpers

    private fun refreshChips(chips: LinearLayout) {
        for (i in 0 until chips.childCount) {
            val c = chips.getChildAt(i) as TextView
            val on = c.tag == selectedTone
            (c.background as GradientDrawable).setColor(if (on) ACCENT else CHIP_BG)
            c.setTextColor(if (on) Color.WHITE else TEXT)
        }
    }

    private fun label(text: String, size: Float, bold: Boolean = false, color: Int = TEXT) = TextView(context).apply {
        this.text = text
        textSize = size
        setTextColor(color)
        if (bold) setTypeface(typeface, Typeface.BOLD)
    }

    private fun chip(text: String) = TextView(context).apply {
        this.text = text
        textSize = 14f
        setPadding(dp(14), dp(8), dp(14), dp(8))
        background = GradientDrawable().apply { cornerRadius = dp(16).toFloat(); setColor(CHIP_BG) }
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply { marginEnd = dp(8) }
    }

    private fun button(text: String, primary: Boolean, onClick: () -> Unit) = TextView(context).apply {
        this.text = text
        textSize = 15f
        gravity = Gravity.CENTER
        setTypeface(typeface, Typeface.BOLD)
        setPadding(dp(12), dp(12), dp(12), dp(12))
        setTextColor(if (primary) Color.WHITE else TEXT)
        background = GradientDrawable().apply {
            cornerRadius = dp(14).toFloat()
            setColor(if (primary) ACCENT else CHIP_BG)
        }
        setOnClickListener { onClick() }
    }

    private fun weighted() = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
    private fun spacer() = View(context).apply { layoutParams = LinearLayout.LayoutParams(dp(10), 1) }

    private companion object {
        val ACCENT = Color.rgb(0xD9, 0x77, 0x57)
        val PANEL_BG = Color.rgb(0x26, 0x25, 0x24)
        val CHIP_BG = Color.rgb(0x3A, 0x38, 0x36)
        val TEXT = Color.rgb(0xF4, 0xF1, 0xEC)
        val MUTED = Color.rgb(0xB5, 0xAF, 0xA6)
    }
}
