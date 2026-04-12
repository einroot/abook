package com.abook.data.parser

import com.itextpdf.kernel.pdf.PdfDocument
import com.itextpdf.kernel.pdf.PdfOutline
import com.itextpdf.kernel.pdf.PdfReader
import com.itextpdf.kernel.pdf.canvas.parser.PdfTextExtractor
import com.itextpdf.kernel.pdf.canvas.parser.listener.LocationTextExtractionStrategy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream

/**
 * PDF parser using iTextPDF 8.x.
 * Extracts text page-by-page, groups into chapters via outlines or heading heuristics.
 */
class PdfParser : BookParser {

    private data class PdfBookmark(val title: String, val pageNumber: Int)

    private val headingPatterns = listOf(
        Regex("""^\s*(?:Глава|ГЛАВА|Chapter|CHAPTER)\s+(?:\d+|[IVXLCDM]+)"""),
        Regex("""^\s*\d+\.\s+[A-ZА-ЯЁ]"""),
        Regex("""^\s*[A-ZА-ЯЁ][A-ZА-ЯЁ\s]{3,79}\s*$""")
    )

    override suspend fun parse(inputStream: InputStream, fileName: String): ParsedBook =
        withContext(Dispatchers.IO) {
            parseInternal(inputStream, fileName)
        }

    private fun parseInternal(inputStream: InputStream, fileName: String): ParsedBook {
        val bytes = inputStream.readBytes()
        val reader = try {
            PdfReader(ByteArrayInputStream(bytes))
        } catch (e: Exception) {
            throw IOException("Не удалось открыть PDF: ${e.message}")
        }
        val document = PdfDocument(reader)

        return try {
            if (document.numberOfPages == 0) {
                return ParsedBook(title = fileName.substringBeforeLast("."), author = "",
                    chapters = listOf(ParsedChapter("Документ", "")))
            }
            val info = try { document.documentInfo } catch (_: Exception) { null }
            val title = info?.title?.takeIf { it.isNotBlank() }
                ?: fileName.substringBeforeLast(".")
            val author = info?.author ?: ""

            val pageTexts = extractPageTexts(document)
            val bookmarks = try {
                extractBookmarks(document)
            } catch (e: Exception) {
                emptyList()
            }

            val chapters = when {
                bookmarks.isNotEmpty() -> buildChaptersFromBookmarks(pageTexts, bookmarks)
                else -> {
                    val byPatterns = buildChaptersFromPatterns(pageTexts)
                    if (byPatterns.size > 1) byPatterns
                    else buildChaptersByPageGroups(pageTexts, 10)
                }
            }

            ParsedBook(
                title = title,
                author = author,
                chapters = chapters.ifEmpty { listOf(ParsedChapter("Документ", pageTexts.joinToString("\n\n"))) }
            )
        } finally {
            try { document.close() } catch (_: Exception) {}
            try { reader.close() } catch (_: Exception) {}
        }
    }

    private fun extractPageTexts(document: PdfDocument): List<String> {
        val n = document.numberOfPages
        return (1..n).map { pageNum ->
            try {
                PdfTextExtractor.getTextFromPage(
                    document.getPage(pageNum),
                    LocationTextExtractionStrategy()
                ).orEmpty()
            } catch (e: Exception) {
                ""
            }
        }
    }

    private fun extractBookmarks(document: PdfDocument): List<PdfBookmark> {
        val outlines = try {
            document.getOutlines(false)
        } catch (_: Exception) {
            null
        } ?: return emptyList()

        val result = mutableListOf<PdfBookmark>()

        fun traverse(outline: PdfOutline) {
            val title = outline.title.orEmpty().trim()
            if (title.isNotBlank()) {
                result.add(PdfBookmark(title, 1))  // Placeholder — redistributed below
            }
            for (child in outline.allChildren.orEmpty()) {
                traverse(child)
            }
        }

        try {
            for (child in outlines.allChildren.orEmpty()) {
                traverse(child)
            }
        } catch (_: Exception) {
            return emptyList()
        }

        // Distribute bookmarks evenly across pages (fallback when we can't
        // resolve exact page numbers from destinations).
        if (result.isNotEmpty() && result.size <= document.numberOfPages) {
            val pagesPerBookmark = (document.numberOfPages.toDouble() / result.size)
                .coerceAtLeast(1.0)
            for (i in result.indices) {
                val newPage = ((i * pagesPerBookmark).toInt() + 1)
                    .coerceAtMost(document.numberOfPages)
                result[i] = result[i].copy(pageNumber = newPage)
            }
        }

        return result
    }

    private fun buildChaptersFromBookmarks(
        pages: List<String>,
        bookmarks: List<PdfBookmark>
    ): List<ParsedChapter> {
        val chapters = mutableListOf<ParsedChapter>()
        for (i in bookmarks.indices) {
            val start = bookmarks[i].pageNumber - 1
            val end = if (i + 1 < bookmarks.size) {
                bookmarks[i + 1].pageNumber - 1
            } else pages.size
            if (start in pages.indices) {
                val text = pages.subList(start, end.coerceAtMost(pages.size))
                    .joinToString("\n") { it.trim() }
                    .trim()
                if (text.isNotBlank()) {
                    chapters.add(ParsedChapter(bookmarks[i].title, text))
                }
            }
        }
        return chapters
    }

    private fun buildChaptersFromPatterns(pages: List<String>): List<ParsedChapter> {
        val chapters = mutableListOf<ParsedChapter>()
        var currentTitle = "Вступление"
        val currentText = StringBuilder()

        for (page in pages) {
            val firstLine = page.lineSequence().firstOrNull { it.isNotBlank() }?.trim().orEmpty()
            val isHeading = headingPatterns.any { it.containsMatchIn(firstLine) }
            if (isHeading && currentText.isNotEmpty()) {
                chapters.add(ParsedChapter(currentTitle, currentText.toString().trim()))
                currentText.clear()
                currentTitle = firstLine
            }
            currentText.append(page).append("\n")
        }
        if (currentText.isNotEmpty()) {
            chapters.add(ParsedChapter(currentTitle, currentText.toString().trim()))
        }
        return chapters
    }

    private fun buildChaptersByPageGroups(
        pages: List<String>,
        groupSize: Int
    ): List<ParsedChapter> {
        if (pages.isEmpty()) return emptyList()
        val chapters = mutableListOf<ParsedChapter>()
        var i = 0
        while (i < pages.size) {
            val end = (i + groupSize).coerceAtMost(pages.size)
            val text = pages.subList(i, end).joinToString("\n").trim()
            if (text.isNotBlank()) {
                chapters.add(ParsedChapter("Страницы ${i + 1}-${end}", text))
            }
            i = end
        }
        return chapters
    }
}
