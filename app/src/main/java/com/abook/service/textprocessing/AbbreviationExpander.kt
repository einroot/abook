package com.abook.service.textprocessing

/**
 * Expands common Russian/English abbreviations to full words for better TTS pronunciation.
 */
object AbbreviationExpander {

    private val abbreviations = mapOf(
        // Russian
        "т.е." to "то есть",
        "т.к." to "так как",
        "т.д." to "так далее",
        "т.п." to "тому подобное",
        "т.н." to "так называемый",
        "т.ч." to "том числе",
        "и т.д." to "и так далее",
        "и т.п." to "и тому подобное",
        "и др." to "и другие",
        "и пр." to "и прочее",
        "см." to "смотри",
        "стр." to "страница",
        "г." to "год",
        "гг." to "годы",
        "в." to "век",
        "вв." to "века",
        "руб." to "рублей",
        "коп." to "копеек",
        "кг." to "килограмм",
        "г-н" to "господин",
        "г-жа" to "госпожа",
        "ул." to "улица",
        "пр." to "проспект",
        "пл." to "площадь",
        "д." to "дом",
        "кв." to "квартира",
        "напр." to "например",
        "проф." to "профессор",
        "акад." to "академик",
        // English
        "i.e." to "that is",
        "e.g." to "for example",
        "etc." to "etcetera",
        "vs." to "versus",
        "Mr." to "Mister",
        "Mrs." to "Missus",
        "Dr." to "Doctor",
        "St." to "Saint"
    )

    fun expand(text: String): String {
        var result = text
        for ((abbr, expansion) in abbreviations) {
            // Match the abbreviation as a standalone token
            val pattern = Regex("""(?<![a-zA-Zа-яА-ЯёЁ])${Regex.escape(abbr)}(?![a-zA-Zа-яА-ЯёЁ])""")
            result = pattern.replace(result, expansion)
        }
        return result
    }
}
