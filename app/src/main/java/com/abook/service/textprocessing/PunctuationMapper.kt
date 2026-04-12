package com.abook.service.textprocessing

/**
 * Normalizes punctuation for better TTS pronunciation.
 * Replaces dashes, ellipses, and odd characters with normalized forms.
 */
object PunctuationMapper {

    fun normalize(text: String): String {
        return text
            // Em-dash and en-dash to regular dash with spaces (pause hint)
            .replace("—", " — ")
            .replace("–", " – ")
            // Ellipsis unicode to three dots
            .replace("…", "...")
            // Curly quotes to straight
            .replace("«", "\"")
            .replace("»", "\"")
            .replace("„", "\"")
            .replace("“", "\"")
            .replace("”", "\"")
            .replace("‘", "'")
            .replace("’", "'")
            // Collapse multiple spaces
            .replace(Regex("""[ \t]+"""), " ")
            // Collapse 3+ newlines to 2
            .replace(Regex("""\n{3,}"""), "\n\n")
            .trim()
    }
}
