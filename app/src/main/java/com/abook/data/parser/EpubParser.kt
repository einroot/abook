package com.abook.data.parser

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.parser.Parser
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.util.zip.ZipInputStream

/**
 * EPUB parser via ZipInputStream + Jsoup.
 * Reads container.xml → OPF → manifest/spine → NCX or nav TOC → XHTML content.
 */
class EpubParser : BookParser {

    private data class EpubMetadata(
        val title: String?,
        val author: String?,
        val language: String?,
        val description: String?,
        val coverId: String?
    )

    private data class ManifestItem(
        val id: String,
        val href: String,
        val mediaType: String,
        val properties: String?
    )

    private data class TocEntry(val title: String, val href: String)

    override suspend fun parse(inputStream: InputStream, fileName: String): ParsedBook =
        withContext(Dispatchers.IO) {
            parseInternal(inputStream, fileName)
        }

    private fun parseInternal(inputStream: InputStream, fileName: String): ParsedBook {
        val bytes = inputStream.readBytes()
        val entries = readZipEntries(bytes)

        val opfPath = findOpfPath(entries)
            ?: throw IllegalStateException("Invalid EPUB: no OPF found")
        val opfDir = opfPath.substringBeforeLast("/", "")

        val opfBytes = entries[opfPath]
            ?: throw IllegalStateException("OPF file not found in archive")
        val opfDoc = Jsoup.parse(String(opfBytes, Charsets.UTF_8), "", Parser.xmlParser())

        val metadata = parseMetadata(opfDoc)
        val manifest = parseManifest(opfDoc)
        val spine = parseSpine(opfDoc)
        val tocId = getSpineTocId(opfDoc)

        // Try to get TOC: NCX first, then nav
        val toc = if (!tocId.isNullOrBlank()) {
            val ncxToc = parseTocNcx(entries, manifest, opfDir, tocId)
            if (ncxToc.isNotEmpty()) ncxToc else parseTocNav(entries, manifest, opfDir)
        } else {
            parseTocNav(entries, manifest, opfDir)
        }

        val chapters = extractChapters(entries, spine, manifest, toc, opfDir)

        val cover = extractCover(entries, manifest, metadata.coverId, opfDir)

        return ParsedBook(
            title = metadata.title?.takeIf { it.isNotBlank() }
                ?: fileName.substringBeforeLast("."),
            author = metadata.author ?: "",
            language = metadata.language,
            description = metadata.description,
            coverImageBytes = cover,
            chapters = chapters.ifEmpty { listOf(ParsedChapter("Глава 1", "")) }
        )
    }

    private fun readZipEntries(bytes: ByteArray): Map<String, ByteArray> {
        val map = mutableMapOf<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                if (!entry.isDirectory) {
                    map[entry.name] = zis.readBytes()
                }
                entry = zis.nextEntry
            }
        }
        return map
    }

    private fun findOpfPath(entries: Map<String, ByteArray>): String? {
        val containerXml = entries["META-INF/container.xml"] ?: return null
        val doc = Jsoup.parse(String(containerXml, Charsets.UTF_8), "", Parser.xmlParser())
        return doc.select("rootfile").first()?.attr("full-path")
    }

    private fun parseMetadata(opfDoc: Document): EpubMetadata {
        val metadata = opfDoc.getElementsByTag("metadata").firstOrNull()
            ?: opfDoc.getElementsByTag("opf:metadata").firstOrNull()
        val title = findFirstTagText(metadata, "dc:title", "title")
        val author = findFirstTagText(metadata, "dc:creator", "creator")
        val language = findFirstTagText(metadata, "dc:language", "language")
        val description = findFirstTagText(metadata, "dc:description", "description")
        val coverId = metadata?.getElementsByTag("meta")
            ?.firstOrNull { it.attr("name") == "cover" }
            ?.attr("content")
        return EpubMetadata(title, author, language, description, coverId)
    }

    private fun findFirstTagText(parent: Element?, vararg tags: String): String? {
        if (parent == null) return null
        for (tag in tags) {
            val els = parent.getElementsByTag(tag)
            if (els.isNotEmpty()) {
                val text = els.first()?.text()?.trim()
                if (!text.isNullOrEmpty()) return text
            }
        }
        return null
    }

    private fun parseManifest(opfDoc: Document): Map<String, ManifestItem> {
        val manifestEl = opfDoc.getElementsByTag("manifest").firstOrNull()
            ?: return emptyMap()
        val items = manifestEl.getElementsByTag("item")
        return items.associate { el ->
            val id = el.attr("id")
            id to ManifestItem(
                id = id,
                href = el.attr("href"),
                mediaType = el.attr("media-type"),
                properties = el.attr("properties").takeIf { it.isNotEmpty() }
            )
        }
    }

    private fun parseSpine(opfDoc: Document): List<String> {
        val spineEl = opfDoc.getElementsByTag("spine").firstOrNull()
            ?: return emptyList()
        return spineEl.getElementsByTag("itemref").map { it.attr("idref") }
    }

    private fun getSpineTocId(opfDoc: Document): String? {
        return opfDoc.getElementsByTag("spine").firstOrNull()?.attr("toc")?.takeIf { it.isNotEmpty() }
    }

    private fun parseTocNcx(
        entries: Map<String, ByteArray>,
        manifest: Map<String, ManifestItem>,
        opfDir: String,
        tocId: String
    ): List<TocEntry> {
        val tocItem = manifest[tocId] ?: return emptyList()
        val tocPath = resolvePath(opfDir, tocItem.href)
        val tocBytes = entries[tocPath] ?: return emptyList()
        return try {
            val doc = Jsoup.parse(String(tocBytes, Charsets.UTF_8), "", Parser.xmlParser())
            doc.getElementsByTag("navPoint").map { np ->
                val navLabel = np.getElementsByTag("navLabel").firstOrNull()
                val title = navLabel?.getElementsByTag("text")?.firstOrNull()?.text().orEmpty()
                val href = np.getElementsByTag("content").firstOrNull()?.attr("src").orEmpty()
                TocEntry(title, href)
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun parseTocNav(
        entries: Map<String, ByteArray>,
        manifest: Map<String, ManifestItem>,
        opfDir: String
    ): List<TocEntry> {
        // Find manifest item with properties="nav"
        val navItem = manifest.values.firstOrNull { it.properties?.contains("nav") == true }
            ?: return emptyList()
        val navPath = resolvePath(opfDir, navItem.href)
        val navBytes = entries[navPath] ?: return emptyList()
        return try {
            val doc = Jsoup.parse(String(navBytes, Charsets.UTF_8))
            doc.select("nav li a").map { a ->
                TocEntry(a.text(), a.attr("href"))
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun extractChapters(
        entries: Map<String, ByteArray>,
        spine: List<String>,
        manifest: Map<String, ManifestItem>,
        toc: List<TocEntry>,
        opfDir: String
    ): List<ParsedChapter> {
        val tocMap = toc.associateBy { it.href.substringBefore("#") }
        val chapters = mutableListOf<ParsedChapter>()

        spine.forEachIndexed { index, idref ->
            val item = manifest[idref] ?: return@forEachIndexed
            val itemPath = resolvePath(opfDir, item.href)
            val contentBytes = entries[itemPath] ?: return@forEachIndexed

            val html = String(contentBytes, Charsets.UTF_8)
            val doc = Jsoup.parse(html)
            val text = extractText(doc)
            if (text.isBlank()) return@forEachIndexed

            val title = tocMap[item.href]?.title
                ?: tocMap[item.href.substringAfterLast("/")]?.title
                ?: "Глава ${index + 1}"
            chapters.add(ParsedChapter(title, text))
        }
        return chapters
    }

    /**
     * Extract all readable text from an XHTML document.
     *
     * Strategy:
     * 1. Remove elements that shouldn't be read: script, style, nav, header, footer, figure
     * 2. Try structured extraction: select all block-level text-bearing elements
     *    (p, h1-h6, blockquote, li, td) in document order. Each becomes a paragraph.
     * 3. If structured extraction produced less than 10% of what body.text() returns,
     *    fall back to flat body.text() — some EPUBs use non-standard markup.
     */
    private fun extractText(doc: Document): String {
        val body = doc.body() ?: return ""

        // Strip non-content elements in place
        body.select("script, style, nav, header, footer, figure, aside").remove()

        val blocks = body.select("p, h1, h2, h3, h4, h5, h6, blockquote, li, td, pre")

        val structured = StringBuilder()
        for (block in blocks) {
            // Skip nested blocks (e.g., <p> inside <blockquote>) — parent will include them.
            // But document-order selection gives them all anyway; de-duplication via ancestor check.
            var skip = false
            var parent = block.parent()
            while (parent != null && parent !== body) {
                if (parent.tagName().lowercase() in setOf("p", "blockquote", "li", "td")) {
                    skip = true; break
                }
                parent = parent.parent()
            }
            if (skip) continue

            val text = block.text().trim()
            if (text.isNotBlank()) {
                if (block.tagName().lowercase() == "li") {
                    structured.append("• ").append(text).append("\n")
                } else {
                    structured.append(text).append("\n\n")
                }
            }
        }

        val structuredResult = structured.toString().trim()

        // Fallback: if structured extraction missed most of the content,
        // use flat body.text() (whitespace-normalized by Jsoup).
        val flatText = body.text().trim()

        return when {
            structuredResult.isBlank() && flatText.isNotBlank() -> flatText
            flatText.length > 100 && structuredResult.length < flatText.length / 4 -> flatText
            else -> structuredResult
        }
    }

    private fun extractCover(
        entries: Map<String, ByteArray>,
        manifest: Map<String, ManifestItem>,
        coverId: String?,
        opfDir: String
    ): ByteArray? {
        // Try coverId from meta
        val coverItem = coverId?.let { manifest[it] }
            // Fallback: find item with properties="cover-image"
            ?: manifest.values.firstOrNull { it.properties?.contains("cover-image") == true }
            // Fallback: first image with "cover" in name
            ?: manifest.values.firstOrNull {
                it.mediaType.startsWith("image/") && it.href.contains("cover", ignoreCase = true)
            }
            ?: return null

        val path = resolvePath(opfDir, coverItem.href)
        return entries[path]
    }

    private fun resolvePath(opfDir: String, href: String): String {
        val cleanHref = href.substringBefore("#")
        return if (opfDir.isEmpty()) cleanHref else "$opfDir/$cleanHref"
    }
}
