package com.abook.service.textprocessing

/**
 * Removes footnote markers like [1], (1), {1} from text to keep TTS flow clean.
 */
object FootnoteHandler {

    private val patterns = listOf(
        Regex("""\[\d{1,4}\]"""),                              // [1], [42], [1234]
        Regex("""\{\d{1,4}\}"""),                              // {1}, {42}
        Regex("""\u00B9|\u00B2|\u00B3|[\u2070-\u2079]""")     // superscript numerals
        // Removed \[\w{1,3}\] — too aggressive, catches [A], [OK], [PR] in normal text
    )

    fun removeFootnotes(text: String): String {
        var result = text
        for (pattern in patterns) {
            result = pattern.replace(result, "")
        }
        return result.replace(Regex("""  +"""), " ")
    }
}
