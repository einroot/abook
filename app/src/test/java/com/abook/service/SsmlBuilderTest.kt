package com.abook.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SsmlBuilderTest {

    @Test
    fun wrapWithPauses_singleSentence() {
        val result = SsmlBuilder.wrapWithPauses("Hello world.", 300)
        assertTrue(result.startsWith("<speak>"))
        assertTrue(result.endsWith("</speak>"))
        assertTrue(result.contains("Hello world."))
    }

    @Test
    fun wrapWithPauses_multipleSentences_insertsBreaks() {
        val text = "First sentence. Second sentence. Third sentence."
        val result = SsmlBuilder.wrapWithPauses(text, 500)
        // Should have two <break> tags between three sentences
        val breakCount = "<break time=\"500ms\"/>".toRegex().findAll(result).count()
        assertEquals(2, breakCount)
    }

    @Test
    fun wrapWithPauses_zeroDuration_returnsText() {
        val text = "Plain text."
        val result = SsmlBuilder.wrapWithPauses(text, 0)
        assertEquals(text, result)
    }

    @Test
    fun splitIntoSentences_russianText() {
        val text = "Привет мир. Как дела? Всё хорошо!"
        val sentences = SsmlBuilder.splitIntoSentences(text)
        assertEquals(3, sentences.size)
    }

    @Test
    fun buildAdvancedSsml_withProsody() {
        val config = SsmlBuilder.SsmlConfig(
            pauseMs = 300,
            prosodyRate = "slow",
            prosodyPitch = "high"
        )
        val result = SsmlBuilder.buildAdvancedSsml("Test sentence.", config)
        assertTrue(result.contains("<prosody"))
        assertTrue(result.contains("rate=\"slow\""))
        assertTrue(result.contains("pitch=\"high\""))
    }

    @Test
    fun escapesXmlSpecialChars() {
        val text = "Text with <angle> & 'apostrophe'"
        val result = SsmlBuilder.wrapWithPauses(text, 300)
        assertTrue(result.contains("&lt;") || result.contains("&amp;"))
    }
}
