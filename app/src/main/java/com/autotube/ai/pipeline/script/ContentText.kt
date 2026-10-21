package com.autotube.ai.pipeline.script

import com.autotube.ai.core.model.AppLanguage
import kotlin.math.max

/**
 * Language-aware text helpers shared by the content pipeline (script, length, voice, captions, SEO).
 * Everything here works for Latin script and Devanagari alike.
 */
internal object ContentText {

    private val WHITESPACE = Regex("\\s+")
    private val SENTENCE_SPLIT = Regex("(?<=[.!?।॥…])\\s+")
    private val BRACKETED = Regex("\\[[^\\]]*]|\\{[^}]*}")
    private val STAGE_PARENS = Regex(
        "\\((?:pause|music|sfx|sound|beat|laughs?|sighs?|whispers?|dramatic|cut to|b-roll|visual|on screen|scene)[^)]*\\)",
        RegexOption.IGNORE_CASE,
    )
    private val MARKDOWN = Regex("[*_#`~|>]+")
    private val SPEAKER_LABEL = Regex("^(?:narrator|voice ?over|vo|host|speaker)\\s*:\\s*", RegexOption.IGNORE_CASE)
    private val SENTENCE_END_CHARS = charArrayOf('.', '!', '?', '।', '॥', '…')
    private val CLAUSE_END_CHARS = charArrayOf(',', ';', ':', '—', '–')

    val STOPWORDS_EN: Set<String> = setOf(
        "a", "an", "the", "and", "or", "but", "if", "then", "so", "of", "to", "in", "on", "at", "by", "for", "with",
        "from", "into", "about", "as", "is", "are", "was", "were", "be", "been", "being", "it", "its", "it's", "this",
        "that", "these", "those", "you", "your", "you're", "we", "our", "they", "their", "he", "she", "his", "her",
        "i", "me", "my", "do", "does", "did", "done", "have", "has", "had", "not", "no", "yes", "can", "could",
        "will", "would", "should", "may", "might", "just", "very", "really", "more", "most", "some", "any", "all",
        "what", "why", "how", "when", "where", "who", "which", "there", "here", "than", "too", "also", "only",
        "even", "ever", "never", "every", "much", "many", "one", "out", "up", "down", "over", "under", "again",
        "let's", "lets", "don't", "isn't", "aren't", "that's", "there's", "here's", "what's", "get", "got", "make",
        "like", "know", "think", "thing", "things", "way", "now", "today", "video", "watch", "show", "shorts",
    )

    val STOPWORDS_HI: Set<String> = setOf(
        "और", "या", "लेकिन", "का", "की", "के", "को", "में", "से", "पर", "है", "हैं", "था", "थी", "थे", "हो", "होता",
        "होती", "होते", "यह", "ये", "वह", "वो", "वे", "इस", "उस", "इन", "उन", "एक", "कि", "जो", "भी", "तो", "ही",
        "नहीं", "न", "क्या", "क्यों", "कैसे", "कब", "कहाँ", "आप", "हम", "मैं", "तुम", "अपने", "अपना", "अपनी", "कर",
        "करते", "करता", "करती", "करना", "रहा", "रही", "रहे", "गया", "गई", "गए", "लिए", "साथ", "बहुत", "सब", "कुछ",
        "अब", "जब", "तब", "वाला", "वाली", "वाले", "दिया", "किया", "जाता", "जाती", "जाते",
    )

    fun words(text: String): List<String> = text.trim().split(WHITESPACE).filter { it.isNotBlank() }

    fun wordCount(text: String): Int = words(text).size

    fun sentences(text: String): List<String> =
        text.trim().split(SENTENCE_SPLIT).map { it.trim() }.filter { it.isNotEmpty() }

    fun isDevanagari(text: String): Boolean = text.any { it in 'ऀ'..'ॿ' }

    fun isAscii(text: String): Boolean = text.all { it.code < 128 }

    fun isCombiningMark(ch: Char): Boolean {
        val t = Character.getType(ch)
        return t == Character.NON_SPACING_MARK.toInt() || t == Character.COMBINING_SPACING_MARK.toInt()
    }

    /** Lowercase letters, digits and combining marks only (keeps Devanagari matras and virama). */
    fun normalizeToken(token: String): String {
        val sb = StringBuilder(token.length)
        for (ch in token.lowercase()) {
            if (ch.isLetterOrDigit() || isCombiningMark(ch)) sb.append(ch)
        }
        return sb.toString()
    }

    fun endsSentence(word: String): Boolean {
        val t = word.trimEnd('"', '\'', '”', '’', ')')
        return t.isNotEmpty() && t.last() in SENTENCE_END_CHARS
    }

    fun endsClause(word: String): Boolean {
        val t = word.trimEnd('"', '\'', '”', '’', ')')
        return t.isNotEmpty() && t.last() in CLAUSE_END_CHARS
    }

    fun isStopword(token: String): Boolean {
        val n = normalizeToken(token)
        return n.isEmpty() || n in STOPWORDS_EN || n in STOPWORDS_HI
    }

    /** Approximate spoken length of a word in syllables (aksharas for Devanagari). */
    fun syllableWeight(word: String): Float {
        val w = normalizeToken(word)
        if (w.isEmpty()) return 0.5f
        if (isDevanagari(w)) {
            var count = 0
            for (i in w.indices) {
                val c = w[i]
                val isBase = c in 'ऄ'..'ह' || c in 'क़'..'ॡ' || c in 'ॲ'..'ॿ'
                if (isBase && !(i > 0 && w[i - 1] == '्')) count++
            }
            return max(1, count).toFloat()
        }
        if (w.all { it.isDigit() }) return (w.length * 1.2f).coerceIn(1f, 8f)
        var count = 0
        var prevVowel = false
        for (c in w) {
            val vowel = c in "aeiouy"
            if (vowel && !prevVowel) count++
            prevVowel = vowel
        }
        if (w.length > 3 && w.endsWith("e") && !w.endsWith("le") && count > 1) count--
        return max(1, count).toFloat()
    }

    private fun isEmojiCodePoint(cp: Int): Boolean =
        cp in 0x1F000..0x1FAFF || cp in 0x2600..0x27BF || cp in 0x2B00..0x2BFF ||
            cp in 0x1F900..0x1F9FF || cp == 0xFE0F || cp in 0xE0020..0xE007F

    fun stripEmoji(text: String): String {
        val sb = StringBuilder(text.length)
        var i = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            if (!isEmojiCodePoint(cp)) sb.appendCodePoint(cp)
            i += Character.charCount(cp)
        }
        return sb.toString()
    }

    /** Narration suitable for TTS: no stage directions, markdown, speaker labels or emojis. */
    fun sanitizeNarration(text: String): String {
        var t = stripEmoji(text)
        t = BRACKETED.replace(t, " ")
        t = STAGE_PARENS.replace(t, " ")
        t = MARKDOWN.replace(t, " ")
        t = SPEAKER_LABEL.replace(t.trim(), "")
        return t.replace(WHITESPACE, " ").trim()
    }

    /** Single-line plain text (titles, on-screen text). */
    fun sanitizeLine(text: String): String =
        MARKDOWN.replace(stripEmoji(text), " ").replace(WHITESPACE, " ").trim().trim('"', '\'')

    /** Cuts [text] to at most [maxWords] words. */
    fun limitWords(text: String, maxWords: Int): String {
        val w = words(text)
        return if (w.size <= maxWords) text.trim() else w.take(maxWords).joinToString(" ")
    }

    /** Cuts [text] to at most [maxChars] characters at a word boundary. */
    fun limitChars(text: String, maxChars: Int): String {
        val t = text.trim()
        if (t.length <= maxChars) return t
        val cut = t.substring(0, maxChars)
        val space = cut.lastIndexOf(' ')
        return (if (space > maxChars / 2) cut.substring(0, space) else cut).trimEnd(',', ';', ':', '-', ' ')
    }

    /** Content words, most distinctive first (longest), without duplicates. */
    fun contentWords(text: String): List<String> =
        words(text).map { it.trim { c -> !c.isLetterOrDigit() && !isCombiningMark(c) } }
            .filter { it.length >= 3 && !isStopword(it) }
            .distinctBy { normalizeToken(it) }

    /** Ends the sentence with proper punctuation for the language when missing. */
    fun ensureTerminalPunctuation(text: String, language: AppLanguage): String {
        val t = text.trim()
        if (t.isEmpty() || endsSentence(t)) return t
        return t.trimEnd(',', ';', ':') + if (language == AppLanguage.HINDI) "।" else "."
    }
}

/** Order-independent, run-stable pseudo-randomness keyed by arbitrary values. */
internal object Seeded {

    fun hash(vararg parts: Any?): Int {
        var h = 0x811C9DC5L.toInt()
        for (p in parts) {
            val s = p?.toString() ?: "null"
            for (c in s) {
                h = h xor c.code
                h *= 16777619
            }
            h = h xor 0x5F
            h *= 16777619
        }
        return mix(h)
    }

    /** Uniform in [0, 1). */
    fun unit(vararg parts: Any?): Double = (hash(*parts).toLong() and 0xFFFFFFFFL).toDouble() / 4294967296.0

    /** Uniform whole number in [lo, hi]. */
    fun between(lo: Long, hi: Long, vararg parts: Any?): Long {
        if (hi <= lo) return lo
        val span = hi - lo + 1
        return lo + (unit(*parts) * span).toLong().coerceIn(0L, span - 1)
    }

    fun <T> pick(list: List<T>, vararg parts: Any?): T = list[between(0, (list.size - 1).toLong(), *parts).toInt()]

    private fun mix(x0: Int): Int {
        var x = x0
        x = x xor (x ushr 16)
        x *= -0x7a143595
        x = x xor (x ushr 13)
        x *= -0x3d4d51cb
        x = x xor (x ushr 16)
        return x
    }
}
