package com.devin.replydrafter.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatReaderTest {
    // A 1080 x 2400 phone, chat window covering the whole screen, message box at y = 2150.
    private val win = WindowBox(0, 0, 1080, 2400)

    private fun them(text: String, top: Int, right: Int = 700) = ScreenNode(text, 40, top, right, top + 120)
    private fun me(text: String, top: Int, left: Int = 400) = ScreenNode(text, left, top, 1040, top + 120)

    @Test fun attributesBubblesBySide() {
        val nodes = listOf(
            ScreenNode("Alex Rivera", 160, 90, 600, 160), // toolbar title
            ScreenNode("Send", 980, 2200, 1060, 2280),
            them("hey are we still on for tonight", 400),
            me("yeah 7 still good?", 560),
            them("perfect, I'll bring the dog", 720),
            ScreenNode("Type a message", 140, 2180, 900, 2280, isEditable = true),
        )
        val t = ChatReader.read(nodes, win, inputTop = 2150)
        assertEquals("Alex Rivera", t.title)
        assertEquals(
            listOf(
                ChatLine(Speaker.THEM, "hey are we still on for tonight"),
                ChatLine(Speaker.ME, "yeah 7 still good?"),
                ChatLine(Speaker.THEM, "perfect, I'll bring the dog"),
            ),
            t.lines,
        )
        assertTrue(t.isUsable)
        assertEquals(listOf("yeah 7 still good?"), t.myLines)
    }

    @Test fun wideBubblesUseTheHuggedEdge() {
        assertEquals(Speaker.THEM, ChatReader.speakerFor(ScreenNode("long", 40, 0, 900, 100), win))
        assertEquals(Speaker.ME, ChatReader.speakerFor(ScreenNode("long", 200, 0, 1040, 100), win))
        assertEquals(Speaker.UNKNOWN, ChatReader.speakerFor(ScreenNode("Tue 4:12 PM", 380, 0, 700, 100), win))
    }

    @Test fun keepsContainerDescriptionOverInnerText() {
        val outer = ScreenNode("Alex: see you soon", 30, 600, 720, 760)
        val inner = ScreenNode("see you soon", 60, 620, 690, 740)
        val t = ChatReader.read(listOf(outer, inner, me("ok!", 800)), win, 2150)
        assertEquals(listOf("Alex: see you soon", "ok!"), t.lines.map { it.text })
    }

    @Test fun dropsTimestampsAndUiNoise() {
        assertTrue(ChatReader.isNoise("4:05 PM"))
        assertTrue(ChatReader.isNoise("Seen"))
        assertTrue(ChatReader.isNoise("5m"))
        assertFalse(ChatReader.isNoise("ok"))
    }

    @Test fun ignoresListContainer() {
        val list = ScreenNode("everything", 0, 300, 1080, 2100)
        val t = ChatReader.read(listOf(list, them("hi", 400), me("hey", 600)), win, 2150)
        assertEquals(listOf("hi", "hey"), t.lines.map { it.text })
    }

    @Test fun mergesOverlappingScrollPages() {
        fun l(s: String) = ChatLine(if (s.startsWith("m")) Speaker.ME else Speaker.THEM, s)
        val bottom = listOf("t3", "m3", "t4", "m4").map(::l)
        val older = listOf("t1", "m1", "t2", "m2", "t3", "m3").map(::l)
        val merged = ChatReader.mergeOlder(older, bottom)
        assertEquals(listOf("t1", "m1", "t2", "m2", "t3", "m3", "t4", "m4"), merged.map { it.text })
        assertTrue(ChatReader.addsNothing(listOf("t3", "m3").map(::l), bottom))
        assertFalse(ChatReader.addsNothing(older, bottom))
        assertEquals(listOf("a", "b"), ChatReader.mergeOlder(listOf(l("a")), listOf(l("b"))).map { it.text })
    }

    @Test fun notUsableWithOneSide() {
        val t = ChatReader.read(listOf(them("hi", 400)), win, 2150)
        assertFalse(t.isUsable)
    }
}

class StyleProfileTest {
    @Test fun learnsDistinctUsefulSamplesAndRoundTrips() {
        val p = StyleProfile(maxSamples = 3)
        assertEquals(2, p.learn(listOf("lol ok", "LOL OK", "4:05 PM", "k", "sounds good")))
        p.learn(listOf("on my way", "bet"))
        assertEquals(listOf("sounds good", "on my way", "bet"), p.samples)
        val back = StyleProfile.decode(p.encode())
        assertEquals(p.samples, back.samples)
        assertEquals(listOf("a", "b"), StyleProfile.splitPasted(" a \n\n b "))
    }
}

class PromptBuilderTest {
    @Test fun buildsSingleReplyPrompt() {
        val t = Transcript("Alex", listOf(ChatLine(Speaker.THEM, "you up?"), ChatLine(Speaker.ME, "yeah")))
        val style = StyleProfile(listOf("lmao no way"), notes = "all lowercase")
        val s = PromptBuilder.build(PromptRequest(t, "Messenger", Tone.FLIRTY, 1, style, hasScreenshot = false))
        assertTrue(s.contains("Chat with: Alex"))
        assertTrue(s.contains("Them: you up?\nMe: yeah"))
        assertTrue(s.contains("- lmao no way"))
        assertTrue(s.contains("all lowercase"))
        assertTrue(s.contains(Tone.FLIRTY.ask))
        assertTrue(s.endsWith("No quotes, no intro, no explanation."))
        assertFalse(s.contains("screenshot"))
    }

    @Test fun buildsOptionsPromptWithScreenshot() {
        val s = PromptBuilder.build(
            PromptRequest(Transcript(null, emptyList()), "WhatsApp", Tone.AUTO, 3, StyleProfile(), hasScreenshot = true),
        )
        assertTrue(s.contains("screenshot of the chat is attached"))
        assertTrue(s.contains("Give me 3 different options"))
        assertFalse(s.contains("How I write"))
    }
}
