package com.abook.service.textprocessing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AbbreviationExpanderTest {

    @Test
    fun expandsRussianAbbreviations() {
        val text = "Это означает т.е. первое."
        val result = AbbreviationExpander.expand(text)
        assertTrue(result.contains("то есть"))
    }

    @Test
    fun expandsCompoundAbbreviations() {
        val text = "Например, яблоки, груши и т.д."
        val result = AbbreviationExpander.expand(text)
        assertTrue(result.contains("и так далее"))
    }

    @Test
    fun preservesNormalText() {
        val text = "Обычный текст без сокращений"
        val result = AbbreviationExpander.expand(text)
        assertEquals(text, result)
    }
}
