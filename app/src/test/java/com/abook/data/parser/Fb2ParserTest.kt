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
    fun inlineHeadingsFromEmptyNestedSectionsStayInsideParentChapter() = runBlocking {
        // Valid FB2 pattern: empty nested <section><title/></section> acts as
        // an inline heading, with content following in the parent section.
        // The parser must keep the top-level <section> as a SINGLE chapter
        // (so the UI chapter list isn't cluttered with tiny heading entries),
        // but inline each heading into the chapter's content so TTS announces
        // it in its natural place.
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
        // Exactly ONE chapter for the top-level section.
        assertEquals(1, result.chapters.size)
        val chapter = result.chapters[0]
        assertEquals("Глава 0: Кто такой SRE", chapter.title)
        val body = chapter.textContent
        // Inline headings and their content all appear in order inside it.
        assertTrue("heading 'Рождение профессии' should be in body", body.contains("Рождение профессии"))
        assertTrue("'birth of profession' should be in body", body.contains("birth of profession"))
        assertTrue("heading 'Что придумал Google' should be in body", body.contains("Что придумал Google"))
        assertTrue("'Google invented' should be in body", body.contains("Google invented"))
        // Order is preserved: birth heading appears before its content, before
        // the next heading, before its content.
        assertTrue(body.indexOf("Рождение профессии") < body.indexOf("birth of profession"))
        assertTrue(body.indexOf("birth of profession") < body.indexOf("Что придумал Google"))
        assertTrue(body.indexOf("Что придумал Google") < body.indexOf("Google invented"))
    }

    @Test
    fun parsesRealWorldSreBookWithInlineHeadings() = runBlocking {
        // Regression test on a real FB2 that uses the inline-heading pattern
        // heavily. The TOP-LEVEL chapter list must stay clean (one entry per
        // top-level <section>), and each chapter must be substantive — not a
        // tiny title-only stub that plays for 2 seconds and auto-advances.
        val stream = Fb2ParserTest::class.java.classLoader!!
            .getResourceAsStream("sre-real.fb2") ?: return@runBlocking
        val parser = Fb2Parser()
        val result = parser.parse(stream, "sre-real.fb2")

        // Should be roughly the number of real chapters (От автора + Глава 0..12),
        // NOT 100+ tiny sub-heading entries. Allow some slack.
        assertTrue(
            "expected <= 20 chapters in flat list, got ${result.chapters.size}",
            result.chapters.size <= 20
        )

        // The Глава 0 chapter must contain its inline headings inside the body,
        // in order, with their content following each.
        val ch0 = result.chapters.first { it.title.contains("Глава 0") }
        assertTrue(
            "Глава 0 should contain 'Рождение профессии' heading inside body, got: " +
                ch0.textContent.take(200),
            ch0.textContent.contains("Рождение профессии")
        )
        assertTrue(
            "Глава 0 should contain 'Что придумал Google' inside body",
            ch0.textContent.contains("Что придумал Google")
        )
        // And the chapter as a whole should be long (all its content merged).
        assertTrue(
            "Глава 0 too short (${ch0.textContent.length} chars)",
            ch0.textContent.length > 3000
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
