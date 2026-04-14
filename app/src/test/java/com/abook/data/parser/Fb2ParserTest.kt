package com.abook.data.parser

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream

class Fb2ParserTest {

    private val sampleFb2 = """<?xml version="1.0" encoding="UTF-8"?>
<FictionBook xmlns="http://www.gribuser.ru/xml/fictionbook/2.0">
  <description>
    <title-info>
      <genre>prose</genre>
      <author>
        <first-name>Лев</first-name>
        <last-name>Толстой</last-name>
      </author>
      <book-title>Война и мир</book-title>
      <lang>ru</lang>
      <annotation><p>Великий роман</p></annotation>
    </title-info>
  </description>
  <body>
    <section>
      <title><p>Часть первая</p></title>
      <p>Первый абзац первой части.</p>
      <p>Второй абзац первой части.</p>
      <empty-line/>
      <p>Третий абзац после пустой строки.</p>
    </section>
    <section>
      <title><p>Часть вторая</p></title>
      <p>Текст второй части.</p>
    </section>
  </body>
</FictionBook>"""

    @Test
    fun parsesMetadata() = runBlocking {
        val parser = Fb2Parser()
        val result = parser.parse(
            ByteArrayInputStream(sampleFb2.toByteArray(Charsets.UTF_8)),
            "test.fb2"
        )
        assertEquals("Война и мир", result.title)
        assertEquals("Лев Толстой", result.author)
        assertEquals("ru", result.language)
    }

    @Test
    fun extractsChapters() = runBlocking {
        val parser = Fb2Parser()
        val result = parser.parse(
            ByteArrayInputStream(sampleFb2.toByteArray(Charsets.UTF_8)),
            "test.fb2"
        )
        assertEquals(2, result.chapters.size)
        assertEquals("Часть первая", result.chapters[0].title)
        assertEquals("Часть вторая", result.chapters[1].title)
    }

    @Test
    fun chapterContentIsClean() = runBlocking {
        val parser = Fb2Parser()
        val result = parser.parse(
            ByteArrayInputStream(sampleFb2.toByteArray(Charsets.UTF_8)),
            "test.fb2"
        )
        val firstChapter = result.chapters[0].textContent
        assertTrue(firstChapter.contains("Первый абзац"))
        assertTrue(firstChapter.contains("Третий абзац"))
        assertFalse(firstChapter.contains("<p>"))
        assertFalse(firstChapter.contains("</p>"))
    }

    @Test
    fun handlesNestedInlineTags() = runBlocking {
        val fb2 = """<?xml version="1.0" encoding="UTF-8"?>
<FictionBook>
  <description><title-info><book-title>T</book-title></title-info></description>
  <body>
    <section>
      <title><p>Глава 1</p></title>
      <p>Обычный текст и <strong>жирный</strong> текст с <emphasis>курсивом</emphasis>.</p>
    </section>
  </body>
</FictionBook>"""
        val parser = Fb2Parser()
        val result = parser.parse(
            ByteArrayInputStream(fb2.toByteArray(Charsets.UTF_8)),
            "t.fb2"
        )
        val text = result.chapters[0].textContent
        assertTrue(text.contains("жирный"))
        assertTrue(text.contains("курсивом"))
        assertFalse(text.contains("<strong>"))
        assertFalse(text.contains("<emphasis>"))
    }

    @Test
    fun splitsContentByEmptyInlineSectionHeadings() = runBlocking {
        // Real-world FB2 pattern: author uses empty nested <section><title/></section>
        // as inline headings, with content following in the parent section.
        val fb2 = """<?xml version="1.0" encoding="UTF-8"?>
<FictionBook>
  <description><title-info><book-title>T</book-title></title-info></description>
  <body>
    <section>
      <title><p>Глава 0: Кто такой SRE</p></title>
      <section>
        <title><p>Рождение профессии</p></title>
      </section>
      <p>Content about birth of profession.</p>
      <p>More context.</p>
      <section>
        <title><p>Что придумал Google</p></title>
      </section>
      <p>Google invented SRE.</p>
    </section>
  </body>
</FictionBook>"""
        val parser = Fb2Parser()
        val result = parser.parse(
            ByteArrayInputStream(fb2.toByteArray(Charsets.UTF_8)),
            "t.fb2"
        )
        // Expect 3 chapters: one for parent (intro before first heading, may be empty
        // in which case it should still emit the title), and one per inline heading.
        val titles = result.chapters.map { it.title }
        assertTrue(
            "expected 'Рождение профессии' in $titles",
            titles.any { it.contains("Рождение профессии") }
        )
        assertTrue(
            "expected 'Что придумал Google' in $titles",
            titles.any { it.contains("Что придумал Google") }
        )
        // Content belonging to each heading should be under that heading, not parent.
        val birthChapter = result.chapters.first { it.title.contains("Рождение профессии") }
        assertTrue(
            "expected 'birth of profession' under Рождение профессии, got: ${birthChapter.textContent}",
            birthChapter.textContent.contains("birth of profession")
        )
        val googleChapter = result.chapters.first { it.title.contains("Что придумал Google") }
        assertTrue(
            "expected 'Google invented' under Что придумал Google, got: ${googleChapter.textContent}",
            googleChapter.textContent.contains("Google invented")
        )
        // Ensure no chapter has empty text — that's what caused playback to freeze
        result.chapters.forEach {
            assertFalse(
                "chapter '${it.title}' has blank text",
                it.textContent.isBlank()
            )
        }
    }

    @Test
    fun parsesRealWorldSreBookWithInlineHeadings() = runBlocking {
        // Real file that triggered "chapters stop playing" bug in the wild.
        // Uses empty nested <section><title/></section> as inline headings.
        val stream = Fb2ParserTest::class.java.classLoader!!
            .getResourceAsStream("sre-real.fb2") ?: return@runBlocking
        val parser = Fb2Parser()
        val result = parser.parse(stream, "sre-real.fb2")

        // Must have the heading the user reported getting stuck on
        val titles = result.chapters.map { it.title }
        assertTrue(
            "expected 'Рождение профессии' among $titles",
            titles.any { it.contains("Рождение профессии") }
        )

        // The Рождение-chapter MUST contain body content, not just the title,
        // otherwise playback would freeze/auto-skip with no substance.
        val birthCh = result.chapters.first { it.title.contains("Рождение профессии") }
        assertTrue(
            "Рождение chapter too short (${birthCh.textContent.length} chars): " +
                birthCh.textContent.take(100),
            birthCh.textContent.length > 200
        )

        // Every chapter must be non-blank — this is the invariant that prevents
        // silent freezes.
        result.chapters.forEach {
            assertFalse(
                "empty chapter: '${it.title}'",
                it.textContent.isBlank()
            )
        }
    }

    @Test
    fun detectsWin1251Encoding() = runBlocking {
        val xml = """<?xml version="1.0" encoding="windows-1251"?>
<FictionBook>
  <description><title-info><book-title>Тест</book-title></title-info></description>
  <body><section><title><p>Глава 1</p></title><p>Привет мир.</p></section></body>
</FictionBook>"""
        val bytes = xml.toByteArray(java.nio.charset.Charset.forName("windows-1251"))
        val parser = Fb2Parser()
        val result = parser.parse(ByteArrayInputStream(bytes), "t.fb2")
        assertEquals("Тест", result.title)
        assertTrue(result.chapters[0].textContent.contains("Привет"))
    }
}
