package chimahon.ocr

import chimahon.anki.AnkiProfile
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource

class OcrLookupTextTest {

    @ParameterizedTest
    @MethodSource("wholeWordCases")
    fun `extractWholeWord returns the tapped word`(testCase: WholeWordCase) {
        Assertions.assertEquals(
            testCase.expected,
            extractWholeWord(testCase.text, testCase.tapOffset, testCase.lineStart, testCase.lineEnd),
        )
    }

    @ParameterizedTest
    @MethodSource("lookupSelectionCases")
    fun `buildLookupSelection returns query and source range`(testCase: LookupSelectionCase) {
        Assertions.assertEquals(
            testCase.expected,
            buildLookupSelection(
                text = testCase.text,
                tapOffset = testCase.tapOffset,
                wholeWord = testCase.wholeWord,
                lineStart = testCase.lineStart,
                lineEnd = testCase.lineEnd,
            ),
        )
    }

    @ParameterizedTest
    @MethodSource("wholeWordScanCases")
    fun `shouldScanWholeWord respects resolution and language`(testCase: WholeWordScanCase) {
        Assertions.assertEquals(
            testCase.expected,
            shouldScanWholeWord(testCase.resolution, testCase.language),
            "shouldScanWholeWord('${testCase.resolution}', '${testCase.language}')",
        )
    }

    @ParameterizedTest
    @MethodSource("wordBoundaryCases")
    fun nextWordBoundarySubstringCutsAtLastWord(testCase: WordBoundaryCase) {
        Assertions.assertEquals(
            testCase.expected,
            nextWordBoundarySubstring(testCase.current),
        )
    }

    @ParameterizedTest
    @MethodSource("languageCases")
    fun isLanguageWholeWordScanDistinguishesScripts(language: String?, expected: Boolean) {
        Assertions.assertEquals(expected, isLanguageWholeWordScan(language))
    }

    data class WholeWordCase(
        val text: String,
        val tapOffset: Int,
        val lineStart: Int,
        val lineEnd: Int,
        val expected: String,
    )

    data class WholeWordScanCase(val resolution: String, val language: String?, val expected: Boolean)

    data class LookupSelectionCase(
        val text: String,
        val tapOffset: Int,
        val wholeWord: Boolean,
        val lineStart: Int,
        val lineEnd: Int,
        val expected: LookupSelection?,
    )

    data class WordBoundaryCase(val current: String, val expected: String)

    companion object {
        @JvmStatic
        fun wholeWordCases() = listOf(
            // Longest common: tap in the middle and at the start of "escoger"
            WholeWordCase("voy a escoger", 9, 0, 13, "escoger"),
            WholeWordCase("voy a escoger", 10, 0, 13, "escoger"),
            WholeWordCase("voy a escoger", 6, 0, 13, "escoger"),

            // Single word line: whole line is the word
            WholeWordCase("escoger", 2, 0, 7, "escoger"),
            WholeWordCase("escoger", 0, 0, 7, "escoger"),

            // Punctuation clamps at word end
            WholeWordCase("el perro, corre", 3, 0, 15, "perro"),

            // Line clamping: blocks concatenate lines without separators.
            // "el perro" then "corre", block text "el perrocorre".
            // Tapping inside the second line must not bleed into the first.
            WholeWordCase("el perrocorre", 8, 8, 13, "corre"),
            WholeWordCase("el perrocorre", 11, 8, 13, "corre"),
            WholeWordCase("el perrocorre", 4, 0, 8, "perro"),

            // CJK: expansion is whole-line (character resolution is handled upstream)
            WholeWordCase("あうえお", 1, 0, 4, "あうえお"),

            // Tap exactly on the offset for a single-letter word
            WholeWordCase("a b", 0, 0, 3, "a"),
            WholeWordCase("a b", 2, 0, 3, "b"),
        )

        @JvmStatic
        fun wholeWordScanCases() = listOf(
            // Default = word for space-delimited languages
            WholeWordScanCase("", "es", true),
            WholeWordScanCase("", "ru", true),
            WholeWordScanCase("", "pt-BR", true),
            WholeWordScanCase("", "en", true),
            // Default = character for CJK / all-languages
            WholeWordScanCase("", "ja", false),
            WholeWordScanCase("", "zh", false),
            WholeWordScanCase("", "yue", false),
            WholeWordScanCase("", "ko", false),
            WholeWordScanCase("", "", false),
            WholeWordScanCase("", "all", false),
            // Explicit resolution always wins
            WholeWordScanCase(AnkiProfile.SCAN_RESOLUTION_WORD, "ja", true),
            WholeWordScanCase(AnkiProfile.SCAN_RESOLUTION_WORD, "es", true),
            WholeWordScanCase(AnkiProfile.SCAN_RESOLUTION_CHARACTER, "es", false),
            WholeWordScanCase(AnkiProfile.SCAN_RESOLUTION_CHARACTER, "ja", false),
            // Case-insensitive
            WholeWordScanCase("Word", "es", true),
            WholeWordScanCase("CHARACTER", "ja", false),
        )

        @JvmStatic
        fun lookupSelectionCases() = listOf(
            LookupSelectionCase(
                text = "uma palavra inteira",
                tapOffset = 8,
                wholeWord = true,
                lineStart = 0,
                lineEnd = 19,
                expected = LookupSelection("palavra", 4, 11, 8),
            ),
            LookupSelectionCase(
                text = "uma palavra inteira",
                tapOffset = 8,
                wholeWord = false,
                lineStart = 0,
                lineEnd = 19,
                expected = LookupSelection("vra", 8, 11, 8),
            ),
            LookupSelectionCase(
                text = "l'esprit d'aujourd'hui",
                tapOffset = 3,
                wholeWord = true,
                lineStart = 0,
                lineEnd = 20,
                expected = LookupSelection("l'esprit", 0, 8, 3),
            ),
            LookupSelectionCase(
                text = "bem-estar geral",
                tapOffset = 5,
                wholeWord = true,
                lineStart = 0,
                lineEnd = 15,
                expected = LookupSelection("bem-estar", 0, 9, 5),
            ),
            LookupSelectionCase(
                text = "linhaumlinhadois",
                tapOffset = 11,
                wholeWord = true,
                lineStart = 7,
                lineEnd = 16,
                expected = LookupSelection("linhadois", 7, 16, 11),
            ),
            LookupSelectionCase(
                text = "texto, outro",
                tapOffset = 5,
                wholeWord = true,
                lineStart = 0,
                lineEnd = 12,
                expected = null,
            ),
        )

        @JvmStatic
        fun wordBoundaryCases() = listOf(
            WordBoundaryCase("voy a escoger", "voy a"),
            WordBoundaryCase("voy a", "voy"),
            WordBoundaryCase("voy", ""),
            WordBoundaryCase("", ""),
            WordBoundaryCase("hello world", "hello"),
            WordBoundaryCase("check this out", "check this"),
        )

        @JvmStatic
        fun languageCases() = listOf<Array<Any?>>(
            arrayOf("es", true),
            arrayOf("es-419", true),
            arrayOf("pt_BR", true),
            arrayOf("ja", false),
            arrayOf("zh", false),
            arrayOf("zh_Hant", false),
            arrayOf("yue", false),
            arrayOf("ko", false),
            arrayOf("all", false),
            arrayOf("other", false),
            arrayOf("unknown", false),
            arrayOf("", false),
            arrayOf(null, false),
        )
    }
}
