package com.devin.replydrafter.core

enum class Tone(val label: String, val ask: String) {
    AUTO("Auto", "Match whatever tone the conversation needs right now."),
    CASUAL("Casual", "Keep it casual and relaxed."),
    WARM("Warm", "Make it warm and caring."),
    FUNNY("Funny", "Make it playful and funny."),
    FLIRTY("Flirty", "Make it flirty and confident, but not over the top."),
    FIRM("Firm", "Be firm and clear, polite but not apologetic."),
    PRO("Pro", "Keep it professional and concise."),
    SHORT("Short", "Keep it very short, one line."),
}

data class PromptRequest(
    val transcript: Transcript,
    val appName: String,
    val tone: Tone,
    val options: Int,
    val style: StyleProfile,
    val hasScreenshot: Boolean,
    /** How many of the most recent messages to include. */
    val maxLines: Int = 30,
)

object PromptBuilder {
    const val MAX_LINE_CHARS = 500
    const val STYLE_SAMPLES = 25

    fun build(req: PromptRequest): String = buildString {
        appendLine("Help me reply to a chat. Write as me, in my own voice, as if I typed it.")
        appendLine()
        appendLine("App: ${req.appName}")
        req.transcript.title?.let { appendLine("Chat with: $it") }

        val lines = req.transcript.lines.takeLast(req.maxLines.coerceIn(1, 200))
        if (lines.isNotEmpty()) {
            appendLine()
            appendLine("Conversation, oldest first, read from my screen. \"Me\" is me, \"Them\" is the other side, \"?\" means I couldn't tell who sent it:")
            for (l in lines) {
                val t = if (l.text.length > MAX_LINE_CHARS) l.text.take(MAX_LINE_CHARS) + "…" else l.text
                appendLine("${l.speaker.label}: $t")
            }
        }
        if (req.hasScreenshot) {
            appendLine()
            appendLine("A screenshot of the chat is attached. My messages are the bubbles on the right.")
        }

        val samples = req.style.pick(STYLE_SAMPLES)
        if (samples.isNotEmpty() || req.style.notes.isNotBlank()) {
            appendLine()
            appendLine("How I write. Copy my voice from these (spelling, casing, punctuation, emoji, length), not their content:")
            samples.forEach { appendLine("- $it") }
            if (req.style.notes.isNotBlank()) appendLine("My own notes on my style: ${req.style.notes.trim()}")
        }

        appendLine()
        appendLine("Tone: ${req.tone.ask}")
        appendLine("Reply to the latest message from them, and take the whole conversation into account.")
        appendLine()
        if (req.options <= 1) {
            append("Answer with only the reply text, exactly as I'd send it. No quotes, no intro, no explanation.")
        } else {
            append(
                "Give me ${req.options} different options. Put each one alone in its own code block so I can copy it, " +
                    "with a two- or three-word label above each. Nothing else.",
            )
        }
    }
}
