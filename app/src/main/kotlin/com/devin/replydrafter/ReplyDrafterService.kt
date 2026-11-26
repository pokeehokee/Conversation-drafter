package com.devin.replydrafter

import android.accessibilityservice.AccessibilityButtonController
import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityService.ScreenshotResult
import android.accessibilityservice.AccessibilityService.TakeScreenshotCallback
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
import com.devin.replydrafter.core.ChatReader
import com.devin.replydrafter.core.PromptBuilder
import com.devin.replydrafter.core.PromptRequest
import com.devin.replydrafter.core.ScreenNode
import com.devin.replydrafter.core.StyleProfile
import com.devin.replydrafter.core.Tone
import com.devin.replydrafter.core.Transcript
import com.devin.replydrafter.core.WindowBox
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.Executors

/**
 * Reads the chat on screen only when the user taps the bubble, the accessibility button or the
 * Quick Settings tile. It never sends anything: it opens the Claude app with a prompt, and can
 * paste Claude's reply (from the clipboard) into the chat's text box for the user to send.
 */
class ReplyDrafterService : AccessibilityService() {

    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
    private lateinit var settings: Settings
    private lateinit var overlay: Overlay

    /** The app the user was last typing in: the chat we read and paste into. */
    private var chatPackage: String? = null

    private val buttonCallback = object : AccessibilityButtonController.AccessibilityButtonCallback() {
        override fun onClicked(controller: AccessibilityButtonController) = overlay.togglePanel()
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        settings = Settings(this)
        overlay = Overlay(this, settings, onDraft = ::draft, onPaste = ::pasteReply)
        accessibilityButtonController.registerAccessibilityButtonCallback(buttonCallback)
        if (settings.bubbleMode == BubbleMode.ALWAYS) overlay.showBubble()
        instance = this
    }

    override fun onDestroy() {
        instance = null
        if (::overlay.isInitialized) overlay.removeAll()
        accessibilityButtonController.unregisterAccessibilityButtonCallback(buttonCallback)
        io.shutdown()
        super.onDestroy()
    }

    override fun onInterrupt() = Unit

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        val pkg = event.packageName?.toString() ?: return
        if (isIgnoredPackage(pkg)) return
        when (event.eventType) {
            AccessibilityEvent.TYPE_VIEW_FOCUSED -> if (event.source?.isEditable == true) {
                chatPackage = pkg
                if (settings.bubbleMode == BubbleMode.WHEN_TYPING) overlay.showBubble()
            }
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> if (settings.bubbleMode == BubbleMode.WHEN_TYPING) {
                if (pkg == chatPackage) overlay.showBubble() else if (!overlay.isPanelOpen) overlay.hideBubble()
            }
        }
    }

    /** Called by settings when the bubble mode changes. */
    fun applyBubbleMode() {
        when (settings.bubbleMode) {
            BubbleMode.ALWAYS -> overlay.showBubble()
            BubbleMode.OFF -> overlay.hideBubble()
            BubbleMode.WHEN_TYPING -> if (chatPackage == null) overlay.hideBubble()
        }
    }

    /** From the Quick Settings tile: close the shade first so we read the chat, not the shade. */
    fun openFromTile() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            performGlobalAction(GLOBAL_ACTION_DISMISS_NOTIFICATION_SHADE)
        }
        main.postDelayed({ overlay.showPanel() }, 450)
    }

    // ---------------------------------------------------------------- reading the chat

    private fun isIgnoredPackage(pkg: String): Boolean {
        if (pkg == packageName || pkg == ClaudeHandoff.CLAUDE_PACKAGE || pkg == "com.android.systemui") return true
        val imm = getSystemService(InputMethodManager::class.java) ?: return false
        return imm.enabledInputMethodList.any { it.packageName == pkg }
    }

    /** The chat app's window: the one the user was typing in, else the active app window. */
    private fun chatWindow(): AccessibilityWindowInfo? {
        val apps = windows.filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }
        val roots = apps.mapNotNull { w -> w.root?.let { w to it } }
            .filter { (_, root) -> !isIgnoredPackage(root.packageName?.toString().orEmpty()) }
        return roots.firstOrNull { (_, root) -> root.packageName == chatPackage }?.first
            ?: roots.firstOrNull { (w, _) -> w.isActive }?.first
            ?: roots.firstOrNull()?.first
    }

    private class Snapshot(
        val packageName: String,
        val windowTitle: String?,
        val window: WindowBox,
        val nodes: List<ScreenNode>,
        val inputTop: Int?,
    )

    private fun snapshot(win: AccessibilityWindowInfo): Snapshot? {
        val root = win.root ?: return null
        val wr = Rect().also(win::getBoundsInScreen)
        val nodes = ArrayList<ScreenNode>()
        var inputTop: Int? = null
        val stack = ArrayDeque<AccessibilityNodeInfo>().apply { addLast(root) }
        val r = Rect()
        var visited = 0
        while (stack.isNotEmpty() && visited < MAX_NODES) {
            val n = stack.removeLast()
            visited++
            if (!n.isVisibleToUser) continue
            n.getBoundsInScreen(r)
            if (n.isEditable) {
                if (n.isFocused || inputTop == null) inputTop = r.top
            }
            val text = n.text?.toString()?.takeIf { it.isNotBlank() }
                ?: n.contentDescription?.toString()?.takeIf { it.isNotBlank() }
            if (text != null) {
                val cls = n.className?.toString().orEmpty()
                nodes += ScreenNode(
                    text = text,
                    left = r.left, top = r.top, right = r.right, bottom = r.bottom,
                    isEditable = n.isEditable,
                    isClickableUi = cls.endsWith("Button") || cls.endsWith("ImageView"),
                )
            }
            for (i in n.childCount - 1 downTo 0) n.getChild(i)?.let(stack::addLast)
        }
        return Snapshot(
            packageName = root.packageName?.toString().orEmpty(),
            windowTitle = win.title?.toString(),
            window = WindowBox(wr.left, wr.top, wr.right, wr.bottom),
            nodes = nodes,
            inputTop = inputTop,
        )
    }

    // ---------------------------------------------------------------- drafting

    private class DraftJob(val tone: Tone, val appName: String, val depth: Int)

    private var busy = false

    private fun draft(tone: Tone) {
        if (busy) return
        val snap = chatWindow()?.let(::snapshot)
        if (snap == null) {
            toast("I couldn't find a chat on screen. Open the chat first.")
            return
        }
        chatPackage = snap.packageName
        overlay.hidePanel()
        val job = DraftJob(tone, KNOWN_APPS[snap.packageName] ?: snap.windowTitle ?: snap.packageName, settings.historyDepth)
        val first = ChatReader.read(snap.nodes, snap.window, snap.inputTop)
        if (!first.isUsable || first.lines.size >= job.depth) {
            finishDraft(first, job)
        } else {
            busy = true
            toast("Reading back through the chat…")
            scrollBack(first, job, steps = 0)
        }
    }

    /** Scrolls the message list up a screen at a time, merging older messages in, until we have enough. */
    private fun scrollBack(acc: Transcript, job: DraftJob, steps: Int) {
        val list = chatWindow()?.root?.let(::messageList)
        if (list == null || steps >= MAX_SCROLLS || !list.performAction(SCROLL_BACK)) {
            scrollHome(acc, job, steps)
            return
        }
        main.postDelayed({
            val snap = chatWindow()?.let(::snapshot)
            val older = snap?.let { ChatReader.read(it.nodes, it.window, it.inputTop).lines }.orEmpty()
            if (ChatReader.addsNothing(older, acc.lines)) {
                scrollHome(acc, job, steps + 1) // reached the start of the chat
            } else {
                val merged = acc.copy(lines = ChatReader.mergeOlder(older, acc.lines))
                if (merged.lines.size >= job.depth) scrollHome(merged, job, steps + 1) else scrollBack(merged, job, steps + 1)
            }
        }, SCROLL_SETTLE_MS)
    }

    /** Puts the chat back where the user left it, then hands off. */
    private fun scrollHome(acc: Transcript, job: DraftJob, steps: Int) {
        if (steps <= 0) {
            busy = false
            finishDraft(acc, job)
            return
        }
        chatWindow()?.root?.let(::messageList)?.performAction(SCROLL_FORWARD)
        main.postDelayed({ scrollHome(acc, job, steps - 1) }, 150)
    }

    /** The chat's scrollable message list: the biggest node that can scroll back. */
    private fun messageList(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val r = Rect()
        var best: AccessibilityNodeInfo? = null
        var bestArea = 0
        val stack = ArrayDeque<AccessibilityNodeInfo>().apply { addLast(root) }
        var visited = 0
        while (stack.isNotEmpty() && visited < MAX_NODES) {
            val n = stack.removeLast()
            visited++
            if (n.isScrollable && n.actionList.any { it.id == SCROLL_BACK }) {
                n.getBoundsInScreen(r)
                val area = r.width() * r.height()
                if (area > bestArea) { best = n; bestArea = area }
            }
            for (i in n.childCount - 1 downTo 0) n.getChild(i)?.let(stack::addLast)
        }
        return best
    }

    private fun finishDraft(transcript: Transcript, job: DraftJob) {
        // Only the user's own messages are kept, to learn their voice. Nothing from the other side is stored.
        val style = settings.loadStyle()
        if (settings.learnFromChats && transcript.isUsable && style.learn(transcript.myLines) > 0) {
            settings.saveStyle(style)
        }
        if (transcript.isUsable) {
            handOff(request(transcript, job, style, hasScreenshot = false), null)
        } else {
            // The app hides its text from accessibility: send a screenshot instead.
            overlay.hideAllForCapture()
            main.postDelayed({ captureAndHandOff(transcript, job, style) }, 250)
        }
    }

    private fun request(t: Transcript, job: DraftJob, style: StyleProfile, hasScreenshot: Boolean) =
        PromptRequest(t, job.appName, job.tone, settings.options, style, hasScreenshot, maxLines = job.depth)

    private fun captureAndHandOff(transcript: Transcript, job: DraftJob, style: StyleProfile) {
        takeScreenshot(Display.DEFAULT_DISPLAY, mainExecutor, object : TakeScreenshotCallback {
            override fun onSuccess(result: ScreenshotResult) {
                io.execute {
                    val ok = try {
                        result.hardwareBuffer.use { hb ->
                            val hw = Bitmap.wrapHardwareBuffer(hb, result.colorSpace) ?: return@use false
                            val soft = hw.copy(Bitmap.Config.ARGB_8888, false)
                            hw.recycle()
                            FileOutputStream(File(cacheDir, ShotProvider.SHOT_NAME)).use { out ->
                                soft.compress(Bitmap.CompressFormat.JPEG, 80, out)
                            }
                            soft.recycle()
                            true
                        }
                    } catch (e: Exception) {
                        false
                    }
                    main.post {
                        overlay.restoreAfterCapture()
                        val req = request(transcript, job, style, hasScreenshot = ok)
                        if (!ok && transcript.lines.isEmpty()) {
                            toast("I couldn't read this chat or take a screenshot of it.")
                        } else {
                            handOff(req, if (ok) ShotProvider.SHOT_URI else null)
                        }
                    }
                }
            }

            override fun onFailure(errorCode: Int) {
                overlay.restoreAfterCapture()
                if (transcript.lines.isEmpty()) {
                    toast("I couldn't read this chat (screenshot error $errorCode).")
                } else {
                    handOff(request(transcript, job, style, hasScreenshot = false), null)
                }
            }
        })
    }

    private fun handOff(req: PromptRequest, shot: android.net.Uri?) {
        overlay.hidePanel()
        val status = ClaudeHandoff.share(this, PromptBuilder.build(req), shot)
        toast(status)
    }

    // ---------------------------------------------------------------- pasting the reply back

    private fun pasteReply() {
        val root = chatWindow()?.root
        val input = root?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)?.takeIf { it.isEditable }
            ?: root?.let(::firstEditable)
        if (input == null) {
            toast("I couldn't find the message box. Tap it once, then try Paste again.")
            return
        }
        input.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        val pasted = input.performAction(AccessibilityNodeInfo.ACTION_PASTE)
        overlay.hidePanel()
        toast(if (pasted) "Pasted. Read it over, then send." else "Paste didn't work here. Long-press the box and tap Paste.")
    }

    private fun firstEditable(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val stack = ArrayDeque<AccessibilityNodeInfo>().apply { addLast(root) }
        var visited = 0
        while (stack.isNotEmpty() && visited < MAX_NODES) {
            val n = stack.removeLast()
            visited++
            if (n.isEditable && n.isVisibleToUser) return n
            for (i in n.childCount - 1 downTo 0) n.getChild(i)?.let(stack::addLast)
        }
        return null
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()

    companion object {
        @Volatile
        var instance: ReplyDrafterService? = null
            private set

        private const val MAX_NODES = 3000
        private const val MAX_SCROLLS = 12
        private const val SCROLL_SETTLE_MS = 450L
        private val SCROLL_BACK = AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_BACKWARD.id
        private val SCROLL_FORWARD = AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_FORWARD.id

        private val KNOWN_APPS = mapOf(
            "com.facebook.orca" to "Facebook Messenger",
            "com.facebook.mlite" to "Messenger Lite",
            "com.whatsapp" to "WhatsApp",
            "com.google.android.apps.messaging" to "Google Messages",
            "com.samsung.android.messaging" to "Samsung Messages",
            "org.telegram.messenger" to "Telegram",
            "org.thoughtcrime.securesms" to "Signal",
            "com.instagram.android" to "Instagram DMs",
            "com.snapchat.android" to "Snapchat",
            "com.discord" to "Discord",
            "com.Slack" to "Slack",
            "com.tinder" to "Tinder",
            "com.bumble.app" to "Bumble",
            "co.hinge.app" to "Hinge",
        )
    }
}
