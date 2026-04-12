package com.abook.service

object SsmlBuilder {

    data class SsmlConfig(
        val pauseMs: Int = 300,
        val prosodyRate: String? = null,    // "slow", "medium", "fast", "x-fast" or percent "80%"
        val prosodyPitch: String? = null,   // "low", "medium", "high" or semitones "+2st"
        val prosodyVolume: String? = null,  // "silent", "soft", "medium", "loud", "x-loud" or dB
        val emphasisLevel: String? = null,  // "none", "reduced", "moderate", "strong"
        val pronunciations: Map<String, String> = emptyMap()
    )

    fun buildAdvancedSsml(text: String, config: SsmlConfig): String {
        val sentences = splitIntoSentences(text)
        val sb = StringBuilder("<speak>")

        val hasProsody = config.prosodyRate != null ||
            config.prosodyPitch != null ||
            config.prosodyVolume != null

        if (hasProsody) {
            sb.append("<prosody")
            config.prosodyRate?.let { sb.append(" rate=\"").append(it).append("\"") }
            config.prosodyPitch?.let { sb.append(" pitch=\"").append(it).append("\"") }
            config.prosodyVolume?.let { sb.append(" volume=\"").append(it).append("\"") }
            sb.append(">")
        }

        sentences.forEachIndexed { index, sentence ->
            var processed = escapeXml(sentence.trim())
            // Apply pronunciation substitutions
            for ((word, pron) in config.pronunciations) {
                processed = processed.replace(
                    Regex("""\b${Regex.escape(word)}\b"""),
                    "<phoneme alphabet=\"ipa\" ph=\"${escapeXml(pron)}\">$word</phoneme>"
                )
            }

            if (config.emphasisLevel != null) {
                sb.append("<emphasis level=\"").append(config.emphasisLevel).append("\">")
                sb.append(processed)
                sb.append("</emphasis>")
            } else {
                sb.append(processed)
            }

            if (index < sentences.size - 1 && config.pauseMs > 0) {
                sb.append("<break time=\"").append(config.pauseMs).append("ms\"/>")
            }
        }

        if (hasProsody) sb.append("</prosody>")
        sb.append("</speak>")
        return sb.toString()
    }

    fun wrapWithPauses(text: String, pauseBetweenSentencesMs: Int): String {
        if (pauseBetweenSentencesMs <= 0) return text

        val sentences = splitIntoSentences(text)
        if (sentences.size <= 1) {
            return "<speak>${escapeXml(text)}</speak>"
        }

        val sb = StringBuilder("<speak>")
        sentences.forEachIndexed { index, sentence ->
            sb.append(escapeXml(sentence.trim()))
            if (index < sentences.size - 1) {
                sb.append("<break time=\"${pauseBetweenSentencesMs}ms\"/>")
            }
        }
        sb.append("</speak>")
        return sb.toString()
    }

    fun splitIntoSentences(text: String): List<String> {
        val pattern = Regex("""(?<=[.!?…])\s+(?=[A-ZА-ЯЁ\d"«])""")
        return text.split(pattern).filter { it.isNotBlank() }
    }

    private fun escapeXml(text: String): String {
        return text
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&apos;")
    }
}
