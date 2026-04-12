package com.abook.service.textprocessing

/**
 * Removes footnote markers like [1], (1), {1} from text to keep TTS flow clean.
 */
object FootnoteHandler {

    private val patterns = listOf(
        Regex("""\[\d+\]"""),
        Regex("""\[\w{1,3}\]"""),  // [a], [ix]
        Regex("""\{\d+\}"""),
        Regex("""\u00B9|\u00B2|\u00B3|[\u2070-\u2079]""")  // superscript numerals
    )

    fun removeFootnotes(text: String): String {
        var result = text
        for (pattern in patterns) {
            result = pattern.replace(result, "")
        }
        return result.replace(Regex("""  +"""), " ")
    }
}
