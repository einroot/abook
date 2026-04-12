package com.abook.service.textprocessing

/**
 * Pre-TTS text processing pipeline.
 * Normalizes punctuation, expands abbreviations, removes footnotes.
 */
class TextProcessor(
    private val expandAbbreviations: Boolean = true,
    private val removeFootnotes: Boolean = true,
    private val normalizePunctuation: Boolean = true,
    private val customPronunciations: Map<String, String> = emptyMap()
) {
    fun process(text: String): String {
        var result = text

        if (normalizePunctuation) {
            result = PunctuationMapper.normalize(result)
        }
        if (removeFootnotes) {
            result = FootnoteHandler.removeFootnotes(result)
        }
        if (expandAbbreviations) {
            result = AbbreviationExpander.expand(result)
        }
        // Apply custom pronunciations: replace word with pronunciation
        for ((word, pron) in customPronunciations) {
            val pattern = Regex("""\b${Regex.escape(word)}\b""", RegexOption.IGNORE_CASE)
            result = pattern.replace(result, pron)
        }

        return result
    }

    companion object {
        val DEFAULT = TextProcessor()
    }
}
