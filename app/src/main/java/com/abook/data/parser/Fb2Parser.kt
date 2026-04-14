package com.abook.data.parser

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.io.InputStreamReader
import java.nio.charset.Charset

/**
 * FB2 (FictionBook 2) parser.
 * Handles Windows-1251, UTF-8, KOI8-R encodings.
 * Extracts title, author, language, cover (from base64 <binary>), chapters from <body>/<section>.
 */
class Fb2Parser : BookParser {

    private data class Fb2Metadata(
        val title: String,
        val author: String,
        val language: String?,
        val description: String?,
        val coverId: String?
    )

    override suspend fun parse(inputStream: InputStream, fileName: String): ParsedBook =
        withContext(Dispatchers.IO) {
            parseInternal(inputStream, fileName)
        }

    private fun parseInternal(inputStream: InputStream, fileName: String): ParsedBook {
        val bytes = inputStream.readBytes()
        val charset = detectEncoding(bytes)

        // First pass — metadata and chapters
        var metadata: Fb2Metadata? = null
        val chapters = mutableListOf<ParsedChapter>()

        val parser = newParser().apply {
            setInput(InputStreamReader(ByteArrayInputStream(bytes), charset))
        }

        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG) {
                when (parser.name) {
                    "description" -> metadata = parseDescription(parser)
                    "body" -> {
                        val nameAttr = parser.getAttributeValue(null, "name")
                        val bodyChapters = parseBody(parser)
                        if (nameAttr == "notes") {
                            // Combine notes into a single "Примечания" chapter
                            val notesText = bodyChapters.joinToString("\n\n") {
                                "${it.title}\n${it.textContent}"
                            }.trim()
                            if (notesText.isNotBlank()) {
                                chapters.add(ParsedChapter("Примечания", notesText))
                            }
                        } else {
                            chapters.addAll(bodyChapters)
                        }
                    }
                }
            }
            event = parser.next()
        }

        // Second pass — extract cover if needed
        val cover: ByteArray? = metadata?.coverId?.let { id ->
            extractCoverBinary(bytes, charset, id)
        }

        val finalChapters = if (chapters.isEmpty()) {
            // Don't use raw XML bytes as text — strip all tags as last resort
            val rawText = String(bytes, charset)
                .replace(Regex("<[^>]+>"), " ")
                .replace(Regex("\\s{2,}"), " ")
                .trim()
            listOf(ParsedChapter("Глава 1", rawText))
        } else chapters

        return ParsedBook(
            title = metadata?.title?.takeIf { it.isNotBlank() }
                ?: fileName.substringBeforeLast("."),
            author = metadata?.author ?: "",
            language = metadata?.language,
            description = metadata?.description,
            coverImageBytes = cover,
            chapters = finalChapters
        )
    }

    private fun detectEncoding(bytes: ByteArray): Charset {
        val headerLen = minOf(bytes.size, 200)
        val header = String(bytes, 0, headerLen, Charsets.US_ASCII)
        val match = Regex("""encoding\s*=\s*["']([^"']+)["']""").find(header)
        val name = match?.groupValues?.get(1) ?: "UTF-8"
        return try {
            Charset.forName(name)
        } catch (_: Exception) {
            Charsets.UTF_8
        }
    }

    private fun parseDescription(parser: XmlPullParser): Fb2Metadata {
        var title = ""
        var author = ""
        var language: String? = null
        var description: String? = null
        var coverId: String? = null

        var event = parser.next()
        var depth = 1
        while (depth > 0 && event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG) {
                depth++
                when (parser.name) {
                    "book-title" -> title = readElementText(parser).trim()
                    "author" -> if (author.isEmpty()) author = parseAuthor(parser)
                    "lang" -> language = readElementText(parser).trim()
                    "annotation" -> description = readElementText(parser).trim()
                    "coverpage" -> coverId = parseCoverpage(parser)
                }
                // parseX methods consume the END_TAG, so reduce depth
                if (parser.eventType == XmlPullParser.END_TAG) depth--
            } else if (event == XmlPullParser.END_TAG) {
                depth--
                if (parser.name == "description") break
            }
            if (depth > 0) event = parser.next() else break
        }
        return Fb2Metadata(title, author, language, description, coverId)
    }

    private fun parseAuthor(parser: XmlPullParser): String {
        // Inside <author>, collect <first-name>, <middle-name>, <last-name>
        val parts = mutableListOf<String>()
        var event = parser.next()
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG) {
                when (parser.name) {
                    "first-name", "middle-name", "last-name", "nickname" -> {
                        val text = readElementText(parser).trim()
                        if (text.isNotEmpty()) parts.add(text)
                    }
                }
            } else if (event == XmlPullParser.END_TAG && parser.name == "author") {
                break
            }
            event = parser.next()
        }
        return parts.joinToString(" ")
    }

    private fun parseCoverpage(parser: XmlPullParser): String? {
        var coverId: String? = null
        var event = parser.next()
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG && parser.name == "image") {
                val href = parser.getAttributeValue(null, "href")
                    ?: parser.getAttributeValue(null, "l:href")
                    ?: (0 until parser.attributeCount)
                        .firstOrNull { parser.getAttributeName(it).endsWith("href") }
                        ?.let { parser.getAttributeValue(it) }
                coverId = href?.removePrefix("#")
            } else if (event == XmlPullParser.END_TAG && parser.name == "coverpage") {
                break
            }
            event = parser.next()
        }
        return coverId
    }

    private fun parseBody(parser: XmlPullParser): List<ParsedChapter> {
        val chapters = mutableListOf<ParsedChapter>()
        var chapterNum = 1
        var event = parser.next()
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG) {
                when (parser.name) {
                    "section" -> {
                        val sectionChapters = parseSection(parser, depth = 0, chapterNum)
                        chapters.addAll(sectionChapters)
                        chapterNum += sectionChapters.size
                    }
                    "title" -> {
                        val t = parseTextContainer(parser, "title")
                        if (t.isNotBlank()) {
                            // Use title as content so TTS announces the section
                            // title when there's no body text to follow.
                            chapters.add(ParsedChapter(t, t))
                        }
                    }
                    "p", "subtitle" -> {
                        val text = parseTextContainer(parser, parser.name)
                        if (chapters.isEmpty()) {
                            chapters.add(ParsedChapter("Глава $chapterNum", text))
                        } else {
                            val last = chapters.last()
                            chapters[chapters.size - 1] = last.copy(
                                textContent = (last.textContent + "\n\n" + text).trim()
                            )
                        }
                    }
                }
            } else if (event == XmlPullParser.END_TAG && parser.name == "body") {
                break
            }
            event = parser.next()
        }
        return chapters
    }

    private fun parseSection(
        parser: XmlPullParser,
        depth: Int,
        startChapterNum: Int
    ): List<ParsedChapter> {
        var sectionTitle = ""
        // Segments split by inline headings (empty nested sections). The first
        // segment uses the section's own title; later segments come from empty
        // <section><title>X</title></section> markers acting as sub-headings,
        // where the content following the empty section belongs to X.
        var currentHeading = ""
        val currentContent = StringBuilder()
        val chapters = mutableListOf<ParsedChapter>()
        val realSubChapters = mutableListOf<ParsedChapter>()

        fun flushSegment() {
            val text = currentContent.toString().trim()
            val titleForSegment = currentHeading.ifBlank { sectionTitle }
            if (text.isNotBlank() || titleForSegment.isNotBlank()) {
                val finalTitle = titleForSegment.ifBlank {
                    "Глава ${startChapterNum + chapters.size}"
                }
                // If a segment has only a heading and no body, speak the heading
                // so TTS doesn't auto-skip it silently.
                val finalText = if (text.isBlank() && titleForSegment.isNotBlank()) {
                    titleForSegment
                } else {
                    text
                }
                chapters.add(ParsedChapter(finalTitle, finalText))
            }
            currentHeading = ""
            currentContent.clear()
        }

        var event = parser.next()
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG) {
                when (parser.name) {
                    "title" -> sectionTitle = parseTextContainer(parser, "title")
                    "section" -> {
                        val nested = parseSection(
                            parser, depth + 1,
                            startChapterNum + chapters.size + realSubChapters.size + 1
                        )
                        // An empty subsection that contains only a title acts as
                        // an inline heading. Parsed form: single chapter whose
                        // text equals its title (our empty-chapter fix above).
                        val isInlineHeading = nested.size == 1 &&
                            nested[0].textContent.trim() == nested[0].title.trim()
                        if (isInlineHeading) {
                            // Flush the current segment, start a new one whose
                            // heading is the empty-section's title.
                            flushSegment()
                            currentHeading = nested[0].title
                        } else if (depth < 2) {
                            realSubChapters.addAll(nested)
                        } else {
                            // Too deep — merge into current segment
                            nested.forEach {
                                currentContent.append(it.title).append("\n\n")
                                if (it.textContent != it.title) {
                                    currentContent.append(it.textContent).append("\n\n")
                                }
                            }
                        }
                    }
                    "p", "subtitle", "cite" -> {
                        val text = parseTextContainer(parser, parser.name)
                        if (text.isNotBlank()) currentContent.append(text).append("\n\n")
                    }
                    "empty-line" -> {
                        currentContent.append("\n")
                        skipToEndTag(parser, "empty-line")
                    }
                    "epigraph" -> {
                        val text = parseTextContainer(parser, "epigraph")
                        if (text.isNotBlank()) currentContent.append("  ").append(text).append("\n\n")
                    }
                    "poem" -> {
                        val poemText = parsePoem(parser)
                        if (poemText.isNotBlank()) currentContent.append(poemText).append("\n\n")
                    }
                    "image" -> skipToEndTag(parser, "image")
                }
            } else if (event == XmlPullParser.END_TAG && parser.name == "section") {
                break
            }
            event = parser.next()
        }

        // Decide whether to flush the final/only segment.
        val finalText = currentContent.toString().trim()
        val hasHeading = currentHeading.isNotBlank()
        val hasAnySplitChapters = chapters.isNotEmpty()
        val shouldEmit = when {
            hasAnySplitChapters -> true  // always flush last segment of a split section
            finalText.isNotBlank() -> true
            realSubChapters.isEmpty() -> true  // nothing else — emit at least a title
            hasHeading -> true
            else -> false  // wrapper for real sub-chapters with no content of its own
        }
        if (shouldEmit) flushSegment()

        return chapters + realSubChapters
    }

    private fun parsePoem(parser: XmlPullParser): String {
        val sb = StringBuilder()
        var event = parser.next()
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG) {
                when (parser.name) {
                    "title" -> {
                        val t = parseTextContainer(parser, "title")
                        if (t.isNotBlank()) sb.append(t).append("\n")
                    }
                    "stanza" -> {
                        val stanza = parsePoem(parser) // recursive: parses <v> lines
                        if (stanza.isNotBlank()) sb.append(stanza).append("\n")
                    }
                    "v" -> {
                        val verse = parseTextContainer(parser, "v")
                        if (verse.isNotBlank()) sb.append(verse).append("\n")
                    }
                }
            } else if (event == XmlPullParser.END_TAG &&
                (parser.name == "poem" || parser.name == "stanza")
            ) {
                break
            }
            event = parser.next()
        }
        return sb.toString().trim()
    }

    /**
     * Parses a text container element (like <p>, <title>, <v>) that may contain
     * mixed text and inline tags (<strong>, <emphasis>, <a>, <sub>, etc.).
     * Returns concatenated text content.
     */
    private fun parseTextContainer(parser: XmlPullParser, endTag: String): String {
        val sb = StringBuilder()
        var event = parser.next()
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.TEXT -> sb.append(parser.text)
                XmlPullParser.START_TAG -> {
                    // Nested inline tag — recurse
                    val innerTag = parser.name
                    if (innerTag == "empty-line") {
                        sb.append("\n")
                        skipToEndTag(parser, "empty-line")
                    } else if (innerTag == "image") {
                        skipToEndTag(parser, "image")
                    } else {
                        sb.append(parseTextContainer(parser, innerTag))
                    }
                }
                XmlPullParser.END_TAG -> {
                    if (parser.name == endTag) return sb.toString().trim()
                }
            }
            event = parser.next()
        }
        return sb.toString().trim()
    }

    private fun readElementText(parser: XmlPullParser): String {
        val sb = StringBuilder()
        val currentTag = parser.name
        var event = parser.next()
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.TEXT -> sb.append(parser.text)
                XmlPullParser.START_TAG -> {
                    sb.append(readElementText(parser))
                }
                XmlPullParser.END_TAG -> {
                    if (parser.name == currentTag) return sb.toString()
                }
            }
            event = parser.next()
        }
        return sb.toString()
    }

    private fun skipToEndTag(parser: XmlPullParser, tag: String) {
        var event = parser.eventType
        // If we're already at END_TAG matching, return
        if (event == XmlPullParser.END_TAG && parser.name == tag) return
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.END_TAG && parser.name == tag) return
            event = parser.next()
        }
    }

    /**
     * Second-pass binary extraction: scans the file for <binary id="coverId">BASE64</binary>
     * and decodes it.
     */
    private fun newParser(): XmlPullParser {
        val factory = XmlPullParserFactory.newInstance()
        factory.isNamespaceAware = false
        return factory.newPullParser()
    }

    private fun decodeBase64(input: String): ByteArray? {
        // Use java.util.Base64 (available on Android API 26+, matches our minSdk)
        return try {
            java.util.Base64.getDecoder().decode(input.replace(Regex("""\s+"""), ""))
        } catch (_: Exception) {
            null
        }
    }

    private fun extractCoverBinary(bytes: ByteArray, charset: Charset, coverId: String): ByteArray? {
        return try {
            val parser = newParser().apply {
                setInput(InputStreamReader(ByteArrayInputStream(bytes), charset))
            }
            var event = parser.eventType
            while (event != XmlPullParser.END_DOCUMENT) {
                if (event == XmlPullParser.START_TAG && parser.name == "binary") {
                    val id = parser.getAttributeValue(null, "id")
                    if (id == coverId) {
                        val base64 = readElementText(parser).trim()
                        return decodeBase64(base64)
                    }
                }
                event = parser.next()
            }
            null
        } catch (e: Exception) {
            null
        }
    }
}
