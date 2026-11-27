package com.devin.replydrafter.core

/**
 * Pure-Kotlin chat parsing. No Android imports here, so it can be unit-tested on a plain JVM.
 *
 * The accessibility service flattens the chat window into [ScreenNode]s; [ChatReader] turns them
 * into an ordered transcript and guesses who sent each line from where its bubble sits:
 * right-aligned bubbles are the phone owner's, left-aligned ones are the other side's.
 */

data class ScreenNode(
    val text: String,
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
    val isEditable: Boolean = false,
    val isClickableUi: Boolean = false,
    /** Sender name the app put in front of the text (Messenger does), if any. */
    val sender: String? = null,
) {
    val width: Int get() = right - left
    val height: Int get() = bottom - top
    val centerX: Int get() = (left + right) / 2

    fun contains(other: ScreenNode): Boolean =
        left <= other.left && top <= other.top && right >= other.right && bottom >= other.bottom
}

enum class Speaker(val label: String) { ME("Me"), THEM("Them"), UNKNOWN("?") }

data class ChatLine(val speaker: Speaker, val text: String, val sender: String? = null)

data class Transcript(val title: String?, val lines: List<ChatLine>) {
    val myLines: List<String> get() = lines.filter { it.speaker == Speaker.ME }.map { it.text }
    val isUsable: Boolean get() = lines.count { it.speaker != Speaker.UNKNOWN } >= 2
}

/** Bounds of the app window the chat lives in, in screen pixels. */
data class WindowBox(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val width: Int get() = right - left
    val height: Int get() = bottom - top
}

object ChatReader {

    /** Short UI strings that are never chat content. Compared case-insensitively. */
    private val UI_NOISE = setOf(
        "send", "like", "camera", "gallery", "photo", "voice", "voice clip", "record",
        "emoji", "sticker", "gif", "more", "back", "call", "video call", "audio call",
        "menu", "info", "options", "search", "type a message", "message", "aa",
        "seen", "delivered", "sent", "active now", "typing…", "typing...",
    )

    private const val DAY = """(mon|tues?|wed|thu(rs?)?|fri|sat|sun)(day)?|today|yesterday|""" +
        """(jan|feb|mar|apr|may|jun|jul|aug|sept?|oct|nov|dec)[a-z]*\.?\s+\d{1,2}(st|nd|rd|th)?(,?\s+\d{4})?|\d{1,2}/\d{1,2}(/\d{2,4})?"""
    private const val CLOCK = """\d{1,2}[:.]\d{2}\s*([ap]\.?\s?m\.?)?"""

    /** Time stamps and date separators: "4:05 PM", "THU AT 6:53 PM", "Yesterday", "Nov 3", "5m". */
    private val TIME_ONLY = Regex(
        """^\s*(($DAY)(\s*,?\s*(at\s+)?$CLOCK)?|$CLOCK|just now|now|\d+\s*(s|secs?|m|mins?|h|hrs?|d|w)(\s*ago)?)\s*$""",
        RegexOption.IGNORE_CASE,
    )

    /** Delivery and read receipts: "Delivered 9 hours ago", "Seen by Sam at 9:02 PM", "Sent". */
    private val STATUS = Regex(
        """^(delivered|seen|sent|read|sending|not delivered|failed to send)(\s+by\s+.+)?""" +
            """(\s+(\d+\s*\w+\s+ago|just now|today|yesterday|(at|on)\s+.+))?\.?$""",
        RegexOption.IGNORE_CASE,
    )

    private val TYPING = Regex("""^.{1,40}\s+is typing(…|\.\.\.)?$""", RegexOption.IGNORE_CASE)

    /**
     * Screen-reader hints that apps append to each bubble, e.g. Messenger's
     * ", double tap to see sent/receive date and time, double tap and hold to react on message".
     * Everything from the first hint to the end is dropped.
     */
    private val HINT = Regex(""",\s*(double[ -]tap|tap and hold|touch and hold|long[ -]press)\b.*$""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))

    /** "Sam, see you soon" → sender "Sam". Only used on bubbles that carried a hint, which always start with the name. */
    private val NAME_PREFIX = Regex("""^([^,]{1,40}?),\s+(.+)$""", RegexOption.DOT_MATCHES_ALL)

    private val TITLE_SUFFIX = Regex(""",\s*(thread|conversation|chat|group)\s+(details|info|settings)$""", RegexOption.IGNORE_CASE)

    /**
     * @param nodes every visible node with text or a content description, in any order.
     * @param window bounds of the chat app's window.
     * @param inputTop top edge of the message box if known; anything at or below it is ignored.
     */
    fun read(nodes: List<ScreenNode>, window: WindowBox, inputTop: Int? = null): Transcript {
        val toolbarBottom = window.top + (window.height * 0.12).toInt()
        val bottomLimit = inputTop ?: (window.bottom - (window.height * 0.08).toInt())

        val cleaned = nodes
            .map(::cleanNode)
            .filter { it.text.isNotEmpty() && !it.isEditable && it.width > 0 && it.height > 0 }

        val title = cleaned
            .filter { it.bottom <= toolbarBottom && !isNoise(it.text) && it.text.length <= 60 }
            .minWithOrNull(compareBy({ it.top }, { it.left }))
            ?.text
            ?.let(::cleanTitle)

        val body = cleaned.filter {
            it.top >= toolbarBottom && it.bottom <= bottomLimit + 4 && !isNoise(it.text) && !it.isClickableUi &&
                it.height < window.height / 2 // a node this tall is the list itself, not a bubble
        }

        val deduped = dropContained(body)

        val sorted = deduped.sortedWith(compareBy({ it.top }, { it.left }))
        val bySender = senderSides(sorted, window)
        val lines = sorted.map { n ->
            val speaker = n.sender?.let { bySender[it] }?.takeIf { it != Speaker.UNKNOWN } ?: speakerFor(n, window)
            ChatLine(speaker, n.text, n.sender)
        }

        return Transcript(title, mergeRepeats(lines))
    }

    fun speakerFor(node: ScreenNode, window: WindowBox): Speaker {
        val w = window.width.toDouble()
        val leftFrac = (node.left - window.left) / w
        val rightFrac = (node.right - window.left) / w
        val centerFrac = (node.centerX - window.left) / w
        return when {
            // Wide bubbles: decide by which edge hugs the screen side.
            leftFrac <= 0.12 && rightFrac < 0.9 -> Speaker.THEM
            rightFrac >= 0.88 && leftFrac >= 0.15 -> Speaker.ME
            centerFrac <= 0.42 -> Speaker.THEM
            centerFrac >= 0.58 -> Speaker.ME
            else -> Speaker.UNKNOWN // centered: date stamps, system notices
        }
    }

    /**
     * When bubbles carry the sender's name, decide each name's side once (by where most of its
     * bubbles sit), so a centered or oddly sized bubble still goes to the right person.
     */
    internal fun senderSides(nodes: List<ScreenNode>, window: WindowBox): Map<String, Speaker> {
        val sides = nodes.filter { it.sender != null }.groupBy { it.sender!! }.mapValues { (_, ns) ->
            val me = ns.count { speakerFor(it, window) == Speaker.ME }
            val them = ns.count { speakerFor(it, window) == Speaker.THEM }
            when {
                me > them -> Speaker.ME
                them > me -> Speaker.THEM
                else -> Speaker.UNKNOWN
            }
        }.toMutableMap()
        // Two people and one side known: the other name is the other side.
        if (sides.size == 2 && sides.values.count { it == Speaker.UNKNOWN } == 1) {
            val known = sides.values.first { it != Speaker.UNKNOWN }
            val unknown = sides.entries.first { it.value == Speaker.UNKNOWN }.key
            sides[unknown] = if (known == Speaker.ME) Speaker.THEM else Speaker.ME
        }
        return sides
    }

    /** Normalizes a node's text, drops screen-reader hints and, where the app put one, the sender's name. */
    internal fun cleanNode(node: ScreenNode): ScreenNode {
        val t = normalize(node.text)
        val hint = HINT.find(t) ?: return node.copy(text = t)
        val body = t.substring(0, hint.range.first).trim()
        val named = NAME_PREFIX.matchEntire(body)
        return if (named != null) {
            node.copy(text = named.groupValues[2].trim(), sender = named.groupValues[1].trim())
        } else {
            node.copy(text = body)
        }
    }

    /** Cleans one stored or pasted sample the same way as a bubble. Null if nothing useful is left. */
    fun cleanSample(text: String): String? =
        cleanNode(ScreenNode(text, 0, 0, 1, 1)).text.takeIf { it.isNotEmpty() && !isNoise(it) }

    internal fun cleanTitle(title: String): String = title.replace(TITLE_SUFFIX, "").trim()

    /**
     * Chat apps often expose a bubble twice: once on the container (content description) and once
     * on the text inside it. Keep the outer node when it already holds the inner node's text,
     * since the outer one tends to carry extra context such as the sender's name.
     */
    internal fun dropContained(nodes: List<ScreenNode>): List<ScreenNode> {
        val distinct = nodes.distinctBy { listOf(it.text, it.left, it.top, it.right, it.bottom) }
        return distinct.filter { inner ->
            distinct.none { outer ->
                outer !== inner && outer.contains(inner) &&
                    outer.text.contains(inner.text, ignoreCase = true) &&
                    (outer.text.length > inner.text.length || outer.width * outer.height > inner.width * inner.height)
            }
        }
    }

    /**
     * Joins an older screenful onto what's been read so far. Scrolled pages overlap, so the
     * longest run where the end of [older] equals the start of [newer] is kept only once.
     */
    fun mergeOlder(older: List<ChatLine>, newer: List<ChatLine>): List<ChatLine> {
        val max = minOf(older.size, newer.size)
        for (k in max downTo 1) {
            if (older.subList(older.size - k, older.size) == newer.subList(0, k)) {
                return older + newer.subList(k, newer.size)
            }
        }
        return older + newer
    }

    /** True when [older] adds nothing that isn't already at the top of [newer] (the list hit its start). */
    fun addsNothing(older: List<ChatLine>, newer: List<ChatLine>): Boolean =
        older.isEmpty() || mergeOlder(older, newer).size == newer.size

    private fun mergeRepeats(lines: List<ChatLine>): List<ChatLine> {
        val out = ArrayList<ChatLine>(lines.size)
        for (line in lines) {
            if (out.isNotEmpty() && out.last() == line) continue
            out += line
        }
        return out
    }

    internal fun isNoise(text: String): Boolean {
        val t = text.trim()
        return t.length <= 1 || t.lowercase() in UI_NOISE || TIME_ONLY.matches(t) || STATUS.matches(t) || TYPING.matches(t)
    }

    internal fun normalize(text: String): String =
        text.replace(Regex("""[​-‍﻿]"""), "").replace(Regex("""\s+"""), " ").trim()
}
