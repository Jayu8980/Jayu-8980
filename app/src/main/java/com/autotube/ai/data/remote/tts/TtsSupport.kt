package com.autotube.ai.data.remote.tts

import com.autotube.ai.core.model.AppLanguage
import com.autotube.ai.core.model.Emotion
import com.autotube.ai.core.model.WordTiming
import com.autotube.ai.domain.TtsRequest
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.roundToLong

/** Splits narration into request-sized pieces at sentence boundaries (works for Latin and Devanagari). */
internal object TextChunker {

    private val SENTENCE_END = Regex("(?<=[.!?।॥…])\\s+")

    fun split(text: String, maxChars: Int): List<String> {
        val clean = text.trim()
        if (clean.length <= maxChars) return listOf(clean)
        val chunks = mutableListOf<String>()
        val current = StringBuilder()

        fun flush() {
            if (current.isNotBlank()) chunks += current.toString().trim()
            current.setLength(0)
        }

        fun appendPiece(piece: String) {
            val extra = if (current.isEmpty()) piece.length else piece.length + 1
            if (current.length + extra > maxChars) flush()
            if (current.isNotEmpty()) current.append(' ')
            current.append(piece)
        }

        for (sentence in clean.split(SENTENCE_END)) {
            if (sentence.isBlank()) continue
            if (sentence.length <= maxChars) {
                appendPiece(sentence)
                continue
            }
            // A single very long sentence: fall back to words, then to a hard cut.
            for (word in sentence.split(' ')) {
                if (word.isEmpty()) continue
                if (word.length <= maxChars) {
                    appendPiece(word)
                } else {
                    flush()
                    word.chunked(maxChars).forEach { part -> chunks += part }
                }
            }
        }
        flush()
        return chunks
    }
}

/** PCM payload of a RIFF/WAVE file. */
internal class WavPcm(
    val audioFormat: Int,
    val channels: Int,
    val sampleRate: Int,
    val bitsPerSample: Int,
    val data: ByteArray,
)

/**
 * Minimal WAV reader/writer. Streamed WAVs from TTS APIs often carry placeholder sizes
 * (0 or 0xFFFFFFFF); they are normalized so Android extractors report the right duration.
 */
internal object WavFiles {

    private const val WAVE_FORMAT_EXTENSIBLE = 0xFFFE

    fun parse(bytes: ByteArray): WavPcm? {
        if (bytes.size < 12 || !bytes.hasAscii(0, "RIFF") || !bytes.hasAscii(8, "WAVE")) return null
        var offset = 12
        var format = -1
        var channels = 0
        var sampleRate = 0
        var bits = 0
        while (offset + 8 <= bytes.size) {
            val id = String(bytes, offset, 4, Charsets.US_ASCII)
            val declared = bytes.le32(offset + 4)
            val start = offset + 8
            val available = (bytes.size - start).toLong()
            when (id) {
                "fmt " -> {
                    if (declared < 16 || start + 16 > bytes.size) return null
                    format = bytes.le16(start)
                    channels = bytes.le16(start + 2)
                    sampleRate = bytes.le32(start + 4).toInt()
                    bits = bytes.le16(start + 14)
                }
                "data" -> {
                    if (format < 0 || channels <= 0 || sampleRate <= 0 || bits <= 0) return null
                    val size = if (declared <= 0L || declared > available) available else declared
                    val blockAlign = channels * (bits / 8).coerceAtLeast(1)
                    val aligned = (size - size % blockAlign).toInt()
                    return WavPcm(
                        audioFormat = if (format == WAVE_FORMAT_EXTENSIBLE) 1 else format,
                        channels = channels,
                        sampleRate = sampleRate,
                        bitsPerSample = bits,
                        data = bytes.copyOfRange(start, start + aligned),
                    )
                }
            }
            if (declared > available) return null
            offset = start + declared.toInt() + (declared and 1L).toInt()
        }
        return null
    }

    /** Writes a canonical 44-byte-header WAV with [parts] concatenated as its data chunk. */
    fun write(outFile: File, format: WavPcm, parts: List<ByteArray>) {
        val dataSize = parts.sumOf { it.size.toLong() }
        val blockAlign = format.channels * format.bitsPerSample / 8
        val byteRate = format.sampleRate * blockAlign
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray(Charsets.US_ASCII))
            putInt((36 + dataSize).toInt())
            put("WAVE".toByteArray(Charsets.US_ASCII))
            put("fmt ".toByteArray(Charsets.US_ASCII))
            putInt(16)
            putShort(format.audioFormat.toShort())
            putShort(format.channels.toShort())
            putInt(format.sampleRate)
            putInt(byteRate)
            putShort(blockAlign.toShort())
            putShort(format.bitsPerSample.toShort())
            put("data".toByteArray(Charsets.US_ASCII))
            putInt(dataSize.toInt())
        }
        outFile.absoluteFile.parentFile?.mkdirs()
        FileOutputStream(outFile).use { out ->
            out.write(header.array())
            parts.forEach { out.write(it) }
        }
    }

    private fun ByteArray.hasAscii(offset: Int, text: String): Boolean =
        offset + text.length <= size && String(this, offset, text.length, Charsets.US_ASCII) == text

    private fun ByteArray.le16(offset: Int): Int =
        (this[offset].toInt() and 0xFF) or ((this[offset + 1].toInt() and 0xFF) shl 8)

    private fun ByteArray.le32(offset: Int): Long =
        (this[offset].toLong() and 0xFF) or
            ((this[offset + 1].toLong() and 0xFF) shl 8) or
            ((this[offset + 2].toLong() and 0xFF) shl 16) or
            ((this[offset + 3].toLong() and 0xFF) shl 24)
}

/** Emotion-matched delivery settings shared by the cloud TTS engines. */
internal object VoiceDirection {

    /** Delivery instructions for instruction-following TTS models (gpt-4o-mini-tts). */
    fun openAiInstructions(request: TtsRequest): String = buildString {
        append("Voice and tone: ").append(emotionDirection(request.emotion)).append(". ")
        append("Intensity: ").append(intensityDirection(request.intensity)).append(". ")
        val keyWords = request.keyWords.map { it.trim() }.filter { it.isNotEmpty() }.distinct().take(8)
        if (keyWords.isNotEmpty()) append("Emphasise: ").append(keyWords.joinToString(", ")).append(". ")
        when {
            request.speed < 0.97f -> append("Pace: slightly slower than usual. ")
            request.speed > 1.03f -> append("Pace: slightly quicker than usual, never rushed. ")
        }
        append("Natural breaths and short pauses between sentences; conversational YouTube narrator, never robotic. ")
        append(
            when (request.language) {
                AppLanguage.ENGLISH -> "Speak in English."
                AppLanguage.HINDI -> "Speak in Hindi with natural Indian pronunciation; say English loanwords naturally."
            },
        )
    }

    private fun emotionDirection(emotion: Emotion): String = when (emotion) {
        Emotion.SUSPENSE -> "low, hushed, slow build, dramatic pauses before reveals, mysterious and tense"
        Emotion.HAPPY -> "bright, smiling, upbeat and warm"
        Emotion.SAD -> "soft, gentle and somber, slower pace with tender pauses"
        Emotion.EXCITING -> "energetic, punchy, fast-paced and enthusiastic"
        Emotion.CALM -> "relaxed, warm, steady and reassuring, unhurried"
        Emotion.DRAMATIC -> "deep, powerful and cinematic, weighty emphasis, bold pauses"
        Emotion.INSPIRING -> "uplifting, confident and heartfelt, energy rising toward the key lines"
        Emotion.FUNNY -> "playful, witty and light, comedic timing with a smile in the voice"
    }

    private fun intensityDirection(intensity: Int): String = when (intensity.coerceIn(1, 10)) {
        in 1..3 -> "subtle and understated"
        in 4..6 -> "moderate and natural"
        in 7..8 -> "strong and expressive"
        else -> "very strong and highly expressive"
    }

    /** ElevenLabs stability/style: calm and sad are steady, exciting/dramatic/funny are lively. */
    fun elevenLabsTuning(emotion: Emotion, intensity: Int, modelId: String): Pair<Float, Float> {
        val (baseStability, baseStyle) = when (emotion) {
            Emotion.CALM -> 0.62f to 0.15f
            Emotion.SAD -> 0.60f to 0.22f
            Emotion.INSPIRING -> 0.45f to 0.40f
            Emotion.HAPPY -> 0.42f to 0.45f
            Emotion.SUSPENSE -> 0.45f to 0.45f
            Emotion.EXCITING -> 0.30f to 0.60f
            Emotion.DRAMATIC -> 0.30f to 0.65f
            Emotion.FUNNY -> 0.32f to 0.60f
        }
        val t = (intensity.coerceIn(1, 10) - 5) / 5f
        var stability = (baseStability - 0.10f * t).coerceIn(0.2f, 0.85f)
        val style = (baseStyle + 0.15f * t).coerceIn(0f, 0.85f)
        if (modelId.contains("v3", ignoreCase = true)) {
            // v3 only accepts the Creative (0.0) / Natural (0.5) / Robust (1.0) presets.
            stability = when {
                stability < 0.35f -> 0.0f
                stability < 0.75f -> 0.5f
                else -> 1.0f
            }
        }
        return stability to style
    }

    /**
     * Pauses are expressed with punctuation, never SSML tags, so the character alignment maps
     * one-to-one onto spoken words.
     */
    fun elevenLabsText(text: String, emotion: Emotion, intensity: Int, language: AppLanguage): String {
        var result = text.replace(Regex("\\s+"), " ").trim()
        val longPause = intensity >= 6 && (emotion == Emotion.SUSPENSE || emotion == Emotion.DRAMATIC || emotion == Emotion.SAD)
        result = result.replace(Regex("\\s*[—–]\\s*"), if (longPause) ", ... " else ", ")
        if (result.isNotEmpty() && result.last() !in ".!?।…\"'”)") {
            result += if (language == AppLanguage.HINDI) "।" else "."
        }
        return result
    }
}

/**
 * Groups per-character timings into words: consecutive non-whitespace characters form a word.
 * Tokens without any letter or digit (such as "..." pauses) are dropped. Times are seconds in, ms out.
 */
internal fun charactersToWords(
    characters: List<String>,
    startsSec: List<Double>,
    endsSec: List<Double>,
    offsetMs: Long,
): List<WordTiming> {
    val count = minOf(characters.size, startsSec.size, endsSec.size)
    val words = mutableListOf<WordTiming>()
    val text = StringBuilder()
    var wordStart = 0.0
    var wordEnd = 0.0

    fun flush() {
        val word = text.toString()
        if (word.any { it.isLetterOrDigit() }) {
            val startMs = offsetMs + (wordStart * 1000).roundToLong()
            val endMs = maxOf(startMs, offsetMs + (wordEnd * 1000).roundToLong())
            words += WordTiming(word = word, startMs = startMs, endMs = endMs)
        }
        text.setLength(0)
    }

    for (i in 0 until count) {
        val ch = characters[i]
        if (ch.isBlank()) {
            if (text.isNotEmpty()) flush()
            continue
        }
        if (text.isEmpty()) wordStart = startsSec[i]
        text.append(ch)
        wordEnd = endsSec[i]
    }
    if (text.isNotEmpty()) flush()
    return words
}
