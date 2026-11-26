package com.devin.replydrafter.core

/**
 * On-device memory of how the phone owner writes: their own sent messages (picked up from the
 * right-hand bubbles each time they draft a reply) plus anything they paste in by hand.
 * Nothing here leaves the phone except the handful of samples placed in a shared prompt.
 */
class StyleProfile(
    samples: List<String> = emptyList(),
    var notes: String = "",
    private val maxSamples: Int = 300,
) {
    private val _samples = ArrayList<String>(samples)
    val samples: List<String> get() = _samples

    /** Adds new samples, newest last. Returns how many were new. */
    fun learn(lines: List<String>): Int {
        var added = 0
        for (raw in lines) {
            val s = ChatReader.normalize(raw)
            if (!isUseful(s)) continue
            if (_samples.any { it.equals(s, ignoreCase = true) }) continue
            _samples += s
            added++
        }
        while (_samples.size > maxSamples) _samples.removeAt(0)
        return added
    }

    fun clear() = _samples.clear()

    /** Most recent distinct samples, trimmed, for a prompt. */
    fun pick(count: Int, maxChars: Int = 200): List<String> =
        _samples.takeLast(count).map { if (it.length > maxChars) it.take(maxChars) + "…" else it }

    fun encode(): String = _samples.joinToString(SEP)

    companion object {
        private const val SEP = "\u001E" // ASCII record separator, never typed in chats

        fun decode(stored: String?, notes: String = ""): StyleProfile =
            StyleProfile(stored.orEmpty().split(SEP).filter { it.isNotBlank() }, notes)

        /** Splits pasted sample text into separate messages: one per line. */
        fun splitPasted(text: String): List<String> = text.lines().map { it.trim() }.filter { it.isNotEmpty() }

        private fun isUseful(s: String): Boolean =
            s.length >= 2 && !ChatReader.isNoise(s) && s.any { it.isLetter() }
    }
}
