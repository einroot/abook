package com.abook.service.textprocessing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test

class PunctuationMapperTest {

    @Test
    fun replacesEllipsisUnicode() {
        val result = PunctuationMapper.normalize("Wait…")
        assertEquals("Wait...", result)
    }

    @Test
    fun replacesCurlyQuotes() {
        val result = PunctuationMapper.normalize("He said \"hello\"")
        assertFalse(result.contains("\u201C"))
    }

    @Test
    fun collapsesMultipleSpaces() {
        val result = PunctuationMapper.normalize("word   word")
        assertEquals("word word", result)
    }
}
