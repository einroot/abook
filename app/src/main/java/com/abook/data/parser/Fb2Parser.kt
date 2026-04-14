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
        var title = ""
        val contentBuilder = StringBuilder()
        val subChapters = mutableListOf<ParsedChapter>()

        var event = parser.next()
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG) {
                when (parser.name) {
                    "title" -> title = parseTextContainer(parser, "title")
                    "section" -> {
                        val nested = parseSection(
                            parser, depth + 1,
                            startChapterNum + subChapters.size + 1
                        )
                        // An empty nested section containing only a title is a
                        // valid FB2 "inline heading" pattern — the actual content
                        // follows in the parent. Detect it (single chapter whose
                        // text == title) and inline the heading INTO this parent's
                        // content instead of emitting a separate chapter.
                        //
                        // Result: the chapter list stays clean (one list entry per
                        // top-level <section>), and TTS still announces each
                        // heading in context because it's now part of the chapter
                        // text stream, framed by blank lines so SSML / pause logic
                        // treats it as a paragraph break.
                        val isInlineHeading = nested.size == 1 &&
                            nested[0].textContent.trim() == nested[0].title.trim()
                        if (isInlineHeading) {
                            if (contentBuilder.isNotEmpty() &&
                                !contentBuilder.endsWith("\n\n")
                            ) {
                                contentBuilder.append("\n\n")
                            }
                            contentBuilder.append(nested[0].title).append("\n\n")
                        } else if (depth < 2) {
                            subChapters.addAll(nested)
                        } else {
                            // Too deep to expose as sub-chapters — fold into content
                            nested.forEach {
                                contentBuilder.append(it.title).append("\n\n")
                                if (it.textContent != it.title) {
                                    contentBuilder.append(it.textContent).append("\n\n")
                                }
                            }
                        }
                    }
                    "p", "subtitle", "cite" -> {
                        val text = parseTextContainer(parser, parser.name)
                        if (text.isNotBlank()) contentBuilder.append(text).append("\n\n")
                    }
                    "empty-line" -> {
                        contentBuilder.append("\n")
                        skipToEndTag(parser, "empty-line")
                    }
                    "epigraph" -> {
                        val text = parseTextContainer(parser, "epigraph")
                        if (text.isNotBlank()) contentBuilder.append("  ").append(text).append("\n\n")
                    }
                    "poem" -> {
                        val poemText = parsePoem(parser)
                        if (poemText.isNotBlank()) contentBuilder.append(poemText).append("\n\n")
                    }
                    "image" -> skipToEndTag(parser, "image")
                }
            } else if (event == XmlPullParser.END_TAG && parser.name == "section") {
                break
            }
            event = parser.next()
        }

        val result = mutableListOf<ParsedChapter>()
        val finalTitle = title.ifBlank { "Глава $startChapterNum" }
        val text = contentBuilder.toString().trim()
        if (text.isNotBlank() || subChapters.isEmpty()) {
            // If section has no textual content but a meaningful title (e.g. a
            // part/chapter divider), use the title as content so TTS still
            // announces it instead of the player auto-skipping.
            val finalText = if (text.isBlank() && title.isNotBlank()) title else text
            result.add(ParsedChapter(finalTitle, finalText))
        }
        result.addAll(subChapters)
        return result
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
