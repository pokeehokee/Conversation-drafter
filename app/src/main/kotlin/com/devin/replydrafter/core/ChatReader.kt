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
) {
    val width: Int get() = right - left
    val height: Int get() = bottom - top
    val centerX: Int get() = (left + right) / 2

    fun contains(other: ScreenNode): Boolean =
        left <= other.left && top <= other.top && right >= other.right && bottom >= other.bottom
}

enum class Speaker(val label: String) { ME("Me"), THEM("Them"), UNKNOWN("?") }

data class ChatLine(val speaker: Speaker, val text: String)

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

    private val TIME_ONLY = Regex("""^\s*(\d{1,2}:\d{2}\s*([ap]\.?m\.?)?|today|yesterday|just now|\d+\s*(m|min|h|hr)s?(\s*ago)?)\s*$""", RegexOption.IGNORE_CASE)

    /**
     * @param nodes every visible node with text or a content description, in any order.
     * @param window bounds of the chat app's window.
     * @param inputTop top edge of the message box if known; anything at or below it is ignored.
     */
    fun read(nodes: List<ScreenNode>, window: WindowBox, inputTop: Int? = null): Transcript {
        val toolbarBottom = window.top + (window.height * 0.12).toInt()
        val bottomLimit = inputTop ?: (window.bottom - (window.height * 0.08).toInt())

        val cleaned = nodes
            .map { it.copy(text = normalize(it.text)) }
            .filter { it.text.isNotEmpty() && !it.isEditable && it.width > 0 && it.height > 0 }

        val title = cleaned
            .filter { it.bottom <= toolbarBottom && !isNoise(it.text) && it.text.length <= 60 }
            .minWithOrNull(compareBy({ it.top }, { it.left }))
            ?.text

        val body = cleaned.filter {
            it.top >= toolbarBottom && it.bottom <= bottomLimit + 4 && !isNoise(it.text) && !it.isClickableUi &&
                it.height < window.height / 2 // a node this tall is the list itself, not a bubble
        }

        val deduped = dropContained(body)

        val lines = deduped
            .sortedWith(compareBy({ it.top }, { it.left }))
            .map { ChatLine(speakerFor(it, window), it.text) }

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
        return t.length <= 1 || t.lowercase() in UI_NOISE || TIME_ONLY.matches(t)
    }

    internal fun normalize(text: String): String =
        text.replace(Regex("""[​-‍﻿]"""), "").replace(Regex("""\s+"""), " ").trim()
}
