package com.abook.data.parser

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.ByteArrayInputStream

class TxtParserTest {

    @Test
    fun parsesPlainTxt_withNoChapters() = runBlocking {
        val text = "Simple text without any chapter markers. Just content."
        val parser = TxtParser()
        val result = parser.parse(ByteArrayInputStream(text.toByteArray()), "test.txt")
        assertTrue(result.chapters.isNotEmpty())
    }

    @Test
    fun detectsRussianChapters() = runBlocking {
        val text = """
            Какое-то вступление.

            Глава 1. Начало
            Первая глава содержит текст.

            Глава 2. Продолжение
            Вторая глава тоже содержит текст.
        """.trimIndent()
        val parser = TxtParser()
        val result = parser.parse(ByteArrayInputStream(text.toByteArray(Charsets.UTF_8)), "book.txt")
        assertTrue(result.chapters.size >= 2)
    }

    @Test
    fun handlesBomUtf8() = runBlocking {
        val bom = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())
        val text = "Simple UTF-8 text"
        val bytes = bom + text.toByteArray(Charsets.UTF_8)
        val parser = TxtParser()
        val result = parser.parse(ByteArrayInputStream(bytes), "test.txt")
        // BOM should be stripped; first chapter should not start with BOM char
        assertTrue(result.chapters.first().textContent.trim().startsWith("Simple"))
    }
}
