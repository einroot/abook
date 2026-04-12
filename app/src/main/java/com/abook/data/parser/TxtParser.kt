package com.abook.data.parser

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.nio.charset.Charset

/**
 * Smart TXT parser:
 * - Auto-detects encoding (UTF-8 BOM, UTF-16, UTF-8, Windows-1251, KOI8-R)
 * - Multi-pattern chapter detection
 * - Sentence-boundary-aware size-based fallback
 */
class TxtParser : BookParser {

    private val chapterPatterns = listOf(
        Regex("""(?m)^\s*(?:ГЛАВА|Глава|глава)\s+(?:\d+|[IVXLCDM]+)[.\s:].*$"""),
        Regex("""(?m)^\s*(?:CHAPTER|Chapter|chapter)\s+(?:\d+|[IVXLCDM]+)[.\s:].*$"""),
        Regex("""(?m)^\s*(?:ЧАСТЬ|Часть|часть)\s+(?:\d+|[IVXLCDM]+|[А-Яа-я]+)[.\s:].*$"""),
        Regex("""(?m)^\s*(?:PART|Part|part)\s+(?:\d+|[IVXLCDM]+|[A-Za-z]+)[.\s:].*$"""),
        Regex("""(?m)^\s*(?:\d+|[IVXLCDM]+)\.\s+[A-ZА-ЯЁ].*$"""),
        Regex("""(?m)^\s*[A-ZА-ЯЁ][A-ZА-ЯЁ\s\d.,!?:;\-]{2,79}\s*$""")
    )

    override suspend fun parse(inputStream: InputStream, fileName: String): ParsedBook =
        withContext(Dispatchers.IO) {
            val bytes = inputStream.readBytes()
            val (charset, skipBytes) = detectEncoding(bytes)
            val text = String(bytes, skipBytes, bytes.size - skipBytes, charset)

            val chapters = splitIntoChapters(text)

            // Book title: first non-blank line if short, else filename
            val title = text.lineSequence()
                .map { it.trim() }
                .firstOrNull { it.isNotBlank() }
                ?.takeIf { it.length < 100 }
                ?: fileName.substringBeforeLast(".")

            ParsedBook(
                title = title,
                author = "",
                chapters = chapters
            )
        }

    /**
     * Returns (charset, bytesToSkip) — where bytesToSkip handles BOM.
     */
    private fun detectEncoding(bytes: ByteArray): Pair<Charset, Int> {
        if (bytes.size >= 3 &&
            bytes[0] == 0xEF.toByte() &&
            bytes[1] == 0xBB.toByte() &&
            bytes[2] == 0xBF.toByte()
        ) return Charsets.UTF_8 to 3

        if (bytes.size >= 2) {
            if (bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte()) {
                return Charsets.UTF_16LE to 2
            }
            if (bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte()) {
                return Charsets.UTF_16BE to 2
            }
        }

        // Try UTF-8 validity check
        val utf8 = try {
            val decoded = String(bytes, Charsets.UTF_8)
            if (!decoded.contains('\uFFFD')) return Charsets.UTF_8 to 0
            decoded
        } catch (_: Exception) {
            null
        }

        // Frequency analysis for Windows-1251 vs KOI8-R
        var win1251 = 0
        var koi8r = 0
        for (b in bytes) {
            val v = b.toInt() and 0xFF
            if (v in 0xE0..0xFF) win1251++
            if (v in 0xC0..0xDF) koi8r++
        }
        return if (koi8r > win1251 * 1.5) {
            try { Charset.forName("KOI8-R") to 0 } catch (_: Exception) { Charsets.UTF_8 to 0 }
        } else {
            try { Charset.forName("windows-1251") to 0 } catch (_: Exception) { Charsets.UTF_8 to 0 }
        }
    }

    private fun splitIntoChapters(text: String): List<ParsedChapter> {
        if (text.isBlank()) return listOf(ParsedChapter("Глава 1", text))

        // Try each pattern; first one giving 2-200 chapters wins
        for (pattern in chapterPatterns) {
            val matches = pattern.findAll(text).toList()
            if (matches.size in 2..200) {
                return splitByMatches(text, matches)
            }
        }

        // Try multiple-blank-lines split
        val blankSplits = Regex("""\n{4,}""").findAll(text).toList()
        if (blankSplits.size in 2..100) {
            val parts = text.split(Regex("""\n{4,}"""))
            return parts.filter { it.isNotBlank() }.mapIndexed { i, p ->
                ParsedChapter("Часть ${i + 1}", p.trim())
            }
        }

        // Size-based fallback with sentence boundaries
        return splitBySize(text)
    }

    private fun splitByMatches(
        text: String,
        matches: List<MatchResult>
    ): List<ParsedChapter> {
        val chapters = mutableListOf<ParsedChapter>()
        // Preface before first match
        if (matches.first().range.first > 0) {
            val preface = text.substring(0, matches.first().range.first).trim()
            if (preface.isNotBlank()) chapters.add(ParsedChapter("Вступление", preface))
        }
        for (i in matches.indices) {
            val title = matches[i].value.trim()
            val start = matches[i].range.last + 1
            val end = if (i + 1 < matches.size) matches[i + 1].range.first else text.length
            val content = text.substring(start, end).trim()
            if (content.isNotBlank() || i == matches.lastIndex) {
                chapters.add(ParsedChapter(title, content))
            }
        }
        return chapters
    }

    private fun splitBySize(text: String, targetSize: Int = 5000): List<ParsedChapter> {
        val chapters = mutableListOf<ParsedChapter>()
        var pos = 0
        var chapterNum = 1
        while (pos < text.length) {
            val targetEnd = (pos + targetSize).coerceAtMost(text.length)
            val splitAt = if (targetEnd < text.length) {
                val windowStart = (targetEnd - 500).coerceAtLeast(pos)
                val window = text.substring(windowStart, targetEnd)
                val lastSentence = Regex("""[.!?…]\s""").findAll(window).lastOrNull()
                if (lastSentence != null) windowStart + lastSentence.range.last + 1
                else targetEnd
            } else targetEnd
            val content = text.substring(pos, splitAt).trim()
            if (content.isNotBlank()) {
                chapters.add(ParsedChapter("Часть $chapterNum", content))
                chapterNum++
            }
            pos = splitAt
        }
        return chapters.ifEmpty { listOf(ParsedChapter("Глава 1", text)) }
    }
}
