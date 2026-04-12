package com.abook.service.textprocessing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FootnoteHandlerTest {

    @Test
    fun removesNumericFootnotes() {
        val text = "Это утверждение[1] подтверждается[42] исследованиями."
        val result = FootnoteHandler.removeFootnotes(text)
        assertFalse(result.contains("[1]"))
        assertFalse(result.contains("[42]"))
        assertTrue(result.contains("утверждение"))
        assertTrue(result.contains("исследованиями"))
    }

    @Test
    fun preservesBracketedAbbreviations() {
        val text = "Нажмите [OK] для продолжения. Язык: [RU]."
        val result = FootnoteHandler.removeFootnotes(text)
        assertTrue(result.contains("[OK]"))
        assertTrue(result.contains("[RU]"))
    }

    @Test
    fun removesCurlyBraceFootnotes() {
        val text = "Текст{3} и ещё{14}."
        val result = FootnoteHandler.removeFootnotes(text)
        assertFalse(result.contains("{3}"))
        assertFalse(result.contains("{14}"))
    }

    @Test
    fun removesSuperscriptNumerals() {
        val text = "Текст\u00B9 и ещё\u00B2 и\u00B3."
        val result = FootnoteHandler.removeFootnotes(text)
        assertFalse(result.contains("\u00B9"))
        assertFalse(result.contains("\u00B2"))
    }

    @Test
    fun collapsesDoubleSpaces() {
        val text = "слово[1] другое"
        val result = FootnoteHandler.removeFootnotes(text)
        assertFalse(result.contains("  "))
    }
}
