package com.autotube.ai.pipeline.audio.synth

import com.autotube.ai.core.model.Emotion
import com.autotube.ai.core.model.MusicStyle
import com.autotube.ai.domain.PcmAudio
import com.autotube.ai.pipeline.audio.dsp.Biquad
import com.autotube.ai.pipeline.audio.dsp.Curves
import com.autotube.ai.pipeline.audio.dsp.LoudnessMeter
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.roundToInt

/**
 * Royalty-free background music generated on the device.
 *
 * Every (emotion, intensity, style, seed) maps to a deterministic piece: key and mode per emotion,
 * a looping 4-bar chord progression, tempo from intensity, and layered warm pad, soft bass,
 * Karplus-Strong plucks / strums, optional drone and light synthesized drums. Audio is produced bar
 * by bar through a [MusicStream], so any length (including 10+ minute videos) streams in constant
 * memory. Streams are loudness-calibrated to [NOMINAL_LUFS].
 */
@Singleton
class ProceduralMusicSynth @Inject constructor() {

    /** Opens an endless stream calibrated to [NOMINAL_LUFS] integrated loudness. */
    fun open(
        emotion: Emotion,
        intensity: Int,
        style: MusicStyle,
        seed: Long,
        sampleRate: Int = 48_000,
    ): MusicStream {
        val spec = MusicSpec.create(emotion, intensity.coerceIn(1, 10), resolveStyle(style, emotion), seed, sampleRate)
        val gainDb = calibrate(spec)
        return ProceduralMusicStream(spec, Curves.dbToLinear(gainDb))
    }

    /** Renders a finite, loop-friendly clip (short edge fades) of [durationMs]. */
    fun generate(
        emotion: Emotion,
        intensity: Int,
        style: MusicStyle,
        durationMs: Long,
        seed: Long,
        sampleRate: Int = 48_000,
    ): PcmAudio {
        val frames = (durationMs * sampleRate / 1000).toInt().coerceAtLeast(1)
        val out = FloatArray(frames * 2)
        val stream = open(emotion, intensity, style, seed, sampleRate)
        val block = FloatArray(BLOCK_FRAMES * 2)
        var pos = 0
        while (pos < frames) {
            val n = minOf(BLOCK_FRAMES, frames - pos)
            stream.read(block, n)
            System.arraycopy(block, 0, out, pos * 2, n * 2)
            pos += n
        }
        val edge = minOf(frames / 4, (0.03f * sampleRate).toInt())
        Curves.fadeIn(out, 2, edge)
        Curves.fadeOut(out, 2, edge)
        return PcmAudio(out, sampleRate, 2)
    }

    /** AUTO picks a style that suits the emotion. */
    fun resolveStyle(style: MusicStyle, emotion: Emotion): MusicStyle = when (style) {
        MusicStyle.AUTO -> when (emotion) {
            Emotion.SUSPENSE -> MusicStyle.CINEMATIC
            Emotion.HAPPY -> MusicStyle.ACOUSTIC
            Emotion.SAD -> MusicStyle.CINEMATIC
            Emotion.EXCITING -> MusicStyle.ELECTRONIC
            Emotion.CALM -> MusicStyle.LOFI
            Emotion.DRAMATIC -> MusicStyle.CINEMATIC
            Emotion.INSPIRING -> MusicStyle.CINEMATIC
            Emotion.FUNNY -> MusicStyle.ACOUSTIC
        }
        MusicStyle.CINEMATIC, MusicStyle.LOFI, MusicStyle.ELECTRONIC, MusicStyle.ACOUSTIC, MusicStyle.AMBIENT -> style
    }

    private fun calibrate(spec: MusicSpec): Float {
        val preview = ProceduralMusicStream(spec, 1f)
        val meter = LoudnessMeter(spec.sampleRate, 2)
        val frames = minOf(spec.barFrames * 8, spec.sampleRate * 16).coerceAtLeast(spec.sampleRate * 4)
        val block = FloatArray(BLOCK_FRAMES * 2)
        var pos = 0
        while (pos < frames) {
            val n = minOf(BLOCK_FRAMES, frames - pos)
            preview.read(block, n)
            meter.process(block, 0, n)
            pos += n
        }
        val lufs = meter.integratedLufs()
        if (lufs <= LoudnessMeter.SILENCE_LUFS + 1f) return 0f
        return (NOMINAL_LUFS - lufs).coerceIn(-24f, 24f)
    }

    companion object {
        /** Loudness every music source (procedural, bundled or imported) is normalized to. */
        const val NOMINAL_LUFS = -20f
        const val BLOCK_FRAMES = 4096
    }
}

/** A chord: root as semitones above the key root, minor or major triad. */
internal data class Chord(val root: Int, val minor: Boolean)

/** Bass note: start beat, length in beats, interval above the chord root. */
internal data class BassHit(val beat: Float, val beats: Float, val interval: Int)

internal enum class ArpMode { UP, UP_DOWN, RISING, OSTINATO, STACCATO, SPARSE, NONE }

internal enum class DrumKit { NONE, POP, DRIVING, LOFI, TAIKO, HEARTBEAT }

/** Everything that defines one deterministic piece. */
internal class MusicSpec(
    val emotion: Emotion,
    val intensity: Int,
    val style: MusicStyle,
    val seed: Long,
    val sampleRate: Int,
    val bpm: Float,
    val keyRoot: Int,
    val progression: List<Chord>,
    val altProgression: List<Chord>,
    val sevenths: Boolean,
    val arpMode: ArpMode,
    val arpSteps: Int,
    val drumKit: DrumKit,
    val drone: Boolean,
    val strum: Boolean,
    val sidechain: Boolean,
    val swing: Float,
    val width: Float,
) {
    val barFrames: Int = (4f * 60f / bpm * sampleRate).roundToInt()
    val beatFrames: Float = barFrames / 4f

    companion object {
        fun create(emotion: Emotion, intensity: Int, style: MusicStyle, seed: Long, sampleRate: Int): MusicSpec {
            val rng = XorShift(seed * 31 + emotion.ordinal * 977L + intensity)
            val baseKey = when (emotion) {
                Emotion.HAPPY -> 0
                Emotion.SAD -> 9
                Emotion.SUSPENSE -> 2
                Emotion.EXCITING -> 4
                Emotion.CALM -> 5
                Emotion.DRAMATIC -> 0
                Emotion.INSPIRING -> 7
                Emotion.FUNNY -> 2
            }
            val keyRoot = ((baseKey + rng.nextInt(5) - 2) % 12 + 12) % 12

            val m = { r: Int -> Chord(r, true) }
            val M = { r: Int -> Chord(r, false) }
            val progression = when (emotion) {
                Emotion.HAPPY -> listOf(M(0), M(7), m(9), M(5))
                Emotion.SAD -> listOf(m(0), M(8), M(3), M(10))
                Emotion.SUSPENSE -> listOf(m(0), M(1), m(0), M(1))
                Emotion.EXCITING -> listOf(m(0), M(10), M(8), M(10))
                Emotion.CALM -> listOf(M(0), M(5), m(9), M(5))
                Emotion.DRAMATIC -> listOf(m(0), M(8), m(5), M(7))
                Emotion.INSPIRING -> listOf(M(5), M(0), M(7), m(9))
                Emotion.FUNNY -> listOf(M(0), M(5), M(7), M(0))
            }
            val altProgression = when (emotion) {
                Emotion.HAPPY -> listOf(m(9), M(5), M(0), M(7))
                Emotion.SAD -> listOf(m(5), M(8), m(0), M(10))
                Emotion.SUSPENSE -> listOf(m(0), m(0), M(1), m(5))
                Emotion.EXCITING -> listOf(M(8), M(10), m(0), m(0))
                Emotion.CALM -> listOf(m(2), M(7), M(0), M(5))
                Emotion.DRAMATIC -> listOf(M(8), M(3), M(10), M(7))
                Emotion.INSPIRING -> listOf(m(9), M(5), M(0), M(7))
                Emotion.FUNNY -> listOf(M(5), M(0), M(7), M(0))
            }

            var bpm = 70f + (intensity - 1) / 9f * 70f
            bpm = when (emotion) {
                Emotion.CALM -> bpm.coerceAtMost(92f)
                Emotion.SAD -> bpm.coerceAtMost(88f)
                Emotion.SUSPENSE -> bpm.coerceIn(70f, 110f)
                Emotion.EXCITING -> bpm.coerceAtLeast(112f)
                Emotion.DRAMATIC -> bpm.coerceIn(78f, 120f)
                Emotion.FUNNY -> bpm.coerceIn(100f, 130f)
                Emotion.HAPPY -> bpm.coerceIn(88f, 132f)
                Emotion.INSPIRING -> bpm.coerceIn(80f, 128f)
            }
            bpm = when (style) {
                MusicStyle.LOFI -> bpm.coerceIn(70f, 90f)
                MusicStyle.AMBIENT -> bpm.coerceIn(60f, 80f)
                MusicStyle.ELECTRONIC -> bpm.coerceAtLeast(100f)
                MusicStyle.AUTO, MusicStyle.CINEMATIC, MusicStyle.ACOUSTIC -> bpm
            }
            bpm += rng.nextInt(7) - 3

            val arpMode = when {
                style == MusicStyle.AMBIENT -> ArpMode.SPARSE
                emotion == Emotion.SUSPENSE -> ArpMode.OSTINATO
                emotion == Emotion.INSPIRING -> ArpMode.RISING
                emotion == Emotion.FUNNY -> ArpMode.STACCATO
                emotion == Emotion.CALM || emotion == Emotion.SAD -> if (intensity <= 4) ArpMode.SPARSE else ArpMode.UP_DOWN
                emotion == Emotion.DRAMATIC && intensity <= 3 -> ArpMode.NONE
                else -> if (rng.chance(0.5f)) ArpMode.UP else ArpMode.UP_DOWN
            }
            val arpSteps = when (arpMode) {
                ArpMode.SPARSE -> 4
                ArpMode.OSTINATO, ArpMode.STACCATO -> 8
                ArpMode.RISING -> if (intensity >= 5) 16 else 8
                ArpMode.NONE -> 0
                ArpMode.UP, ArpMode.UP_DOWN -> when {
                    intensity <= 3 -> 4
                    intensity <= 6 -> 8
                    else -> 16
                }
            }

            val drumKit = when {
                style == MusicStyle.AMBIENT -> DrumKit.NONE
                style == MusicStyle.LOFI -> if (intensity >= 3) DrumKit.LOFI else DrumKit.NONE
                emotion == Emotion.DRAMATIC -> if (intensity >= 3) DrumKit.TAIKO else DrumKit.NONE
                emotion == Emotion.SUSPENSE -> if (intensity >= 5) DrumKit.HEARTBEAT else DrumKit.NONE
                emotion == Emotion.EXCITING || style == MusicStyle.ELECTRONIC -> if (intensity >= 4) DrumKit.DRIVING else DrumKit.NONE
                emotion == Emotion.CALM || emotion == Emotion.SAD -> if (intensity >= 7) DrumKit.LOFI else DrumKit.NONE
                else -> if (intensity >= 5) DrumKit.POP else DrumKit.NONE
            }

            return MusicSpec(
                emotion = emotion,
                intensity = intensity,
                style = style,
                seed = seed,
                sampleRate = sampleRate,
                bpm = bpm,
                keyRoot = keyRoot,
                progression = progression,
                altProgression = altProgression,
                sevenths = style == MusicStyle.LOFI || style == MusicStyle.AMBIENT || emotion == Emotion.CALM,
                arpMode = arpMode,
                arpSteps = arpSteps,
                drumKit = drumKit,
                drone = emotion == Emotion.SUSPENSE || style == MusicStyle.AMBIENT ||
                    (style == MusicStyle.CINEMATIC && (emotion == Emotion.DRAMATIC || emotion == Emotion.SAD) && intensity <= 6),
                strum = style == MusicStyle.ACOUSTIC,
                sidechain = style == MusicStyle.ELECTRONIC,
                swing = if (style == MusicStyle.LOFI || drumKit == DrumKit.LOFI) 0.1f else 0f,
                width = when (style) {
                    MusicStyle.CINEMATIC, MusicStyle.AMBIENT -> 1.25f
                    MusicStyle.LOFI -> 0.85f
                    MusicStyle.AUTO, MusicStyle.ELECTRONIC, MusicStyle.ACOUSTIC -> 1.05f
                },
            )
        }
    }
}

/** Bar-by-bar renderer. Deterministic for a given spec regardless of read block sizes. */
internal class ProceduralMusicStream(
    private val spec: MusicSpec,
    private val outputGain: Float,
) : MusicStream {

    override val sampleRate: Int = spec.sampleRate
    override val label: String = "procedural ${spec.emotion.wireName}/${spec.style.name.lowercase()} " +
        "${spec.bpm.roundToInt()} bpm"

    private val sr = spec.sampleRate
    private val barFrames = spec.barFrames
    private val tailFrames = 4 * sr
    private val accFrames = barFrames + tailFrames
    private val tonal = FloatArray(accFrames * 2)
    private val drums = FloatArray(accFrames * 2)
    private val barOut = FloatArray(barFrames * 2)
    private val scratch = FloatArray(3 * sr)
    private var barIndex = 0
    private var readPos = barFrames // forces the first bar to render

    private val masterLpL = Biquad.lowPass(sr, 4200.0, 0.7)
    private val masterLpR = Biquad.lowPass(sr, 4200.0, 0.7)
    private val masterHpL = Biquad.highPass(sr, 32.0, 0.7)
    private val masterHpR = Biquad.highPass(sr, 32.0, 0.7)
    private val crackleRng = XorShift(spec.seed xor 0x5DEECE66DL)
    private val crackleLp = Biquad.lowPass(sr, 2500.0, 0.7)

    override fun read(dst: FloatArray, frames: Int) {
        var written = 0
        while (written < frames) {
            if (readPos >= barFrames) {
                renderBar()
                readPos = 0
            }
            val n = minOf(frames - written, barFrames - readPos)
            System.arraycopy(barOut, readPos * 2, dst, written * 2, n * 2)
            readPos += n
            written += n
        }
    }

    private fun renderBar() {
        val bar = barIndex++
        val rng = XorShift(spec.seed * 1_000_003L + bar * 7919L + spec.emotion.ordinal)
        val phrase = bar / 8
        val prog = if (phrase % 2 == 1) spec.altProgression else spec.progression
        val chord = prog[bar % prog.size]
        val chordRootPc = spec.keyRoot + chord.root
        val intervals = buildList {
            add(0); add(if (chord.minor) 3 else 4); add(7)
            if (spec.sevenths) add(if (chord.minor) 10 else 11)
        }

        renderPad(chordRootPc, intervals, rng)
        if (spec.drone) renderDrone(rng)
        renderBass(chordRootPc, rng, bar)
        if (spec.strum) renderStrums(chordRootPc, intervals, rng, bar) else renderArp(chordRootPc, intervals, rng, bar)
        val inBreak = spec.intensity < 8 && bar % 16 in 12..13
        if (bar >= 2 && !inBreak) renderDrums(rng, bar)

        emitBar()
    }

    // ---------------------------------------------------------------- layers

    private fun renderPad(rootPc: Int, intervals: List<Int>, rng: XorShift) {
        val style = spec.style
        val attackSec: Float
        val releaseSec: Float
        val detune: Float
        val saws: Int
        var cutoff: Float
        var amp: Float
        var vibrato = 0f
        when (style) {
            MusicStyle.CINEMATIC, MusicStyle.AUTO -> {
                attackSec = 0.55f; releaseSec = 1.2f; detune = 12f; saws = 3
                cutoff = 1500f + spec.intensity * 160f; amp = 0.16f; vibrato = 0.0025f
            }
            MusicStyle.LOFI -> {
                attackSec = 0.04f; releaseSec = 0.6f; detune = 7f; saws = 2
                cutoff = 950f; amp = 0.15f
            }
            MusicStyle.ELECTRONIC -> {
                attackSec = 0.02f; releaseSec = 0.3f; detune = 20f; saws = 3
                cutoff = 1300f + spec.intensity * 230f; amp = 0.13f
            }
            MusicStyle.ACOUSTIC -> {
                attackSec = 0.4f; releaseSec = 0.8f; detune = 8f; saws = 2
                cutoff = 1000f; amp = 0.07f
            }
            MusicStyle.AMBIENT -> {
                attackSec = 1.6f; releaseSec = 2.5f; detune = 10f; saws = 2
                cutoff = 700f + spec.intensity * 40f; amp = 0.17f
            }
        }
        if (spec.emotion == Emotion.CALM) cutoff *= 0.85f
        if (spec.emotion == Emotion.SUSPENSE) amp *= 0.8f
        val attack = minOf((attackSec * sr).toInt(), barFrames / 2)
        val release = (releaseSec * sr).toInt()
        for (iv in intervals) {
            val midi = Notes.nearest(rootPc + iv, 62)
            Voices.pad(
                tonal, accFrames, 0,
                holdFrames = barFrames, attackFrames = attack, releaseFrames = release,
                freq = Notes.midiToHz(midi.toFloat()), amp = amp, detuneCents = detune, sawsPerSide = saws,
                cutoffHz = cutoff, vibratoDepth = vibrato, sampleRate = sr, rng = rng,
            )
        }
    }

    private fun renderDrone(rng: XorShift) {
        val midi = Notes.nearest(spec.keyRoot, 40)
        val fade = (0.8f * sr).toInt()
        Voices.drone(tonal, accFrames, 0, barFrames, fade, Notes.midiToHz(midi.toFloat()), 0.16f, sr, rng)
    }

    private fun bassPattern(): List<BassHit> = when {
        spec.style == MusicStyle.AMBIENT -> listOf(BassHit(0f, 4f, 0))
        spec.style == MusicStyle.ELECTRONIC || spec.emotion == Emotion.EXCITING ->
            (0 until 8).map { BassHit(it * 0.5f, 0.42f, if (it == 7) 12 else 0) }
        spec.emotion == Emotion.FUNNY -> (0 until 4).map { BassHit(it.toFloat(), 0.3f, if (it % 2 == 1) 12 else 0) }
        spec.emotion == Emotion.DRAMATIC -> listOf(BassHit(0f, 1.5f, 0), BassHit(1.5f, 0.5f, 0), BassHit(2f, 2f, 0))
        spec.style == MusicStyle.LOFI -> listOf(BassHit(0f, 1.5f, 0), BassHit(2.5f, 1.4f, 7))
        spec.style == MusicStyle.ACOUSTIC -> listOf(BassHit(0f, 1.5f, 0), BassHit(2f, 1.5f, 7))
        spec.emotion == Emotion.CALM || spec.emotion == Emotion.SAD || spec.emotion == Emotion.SUSPENSE ->
            listOf(BassHit(0f, 4f, 0))
        else -> listOf(BassHit(0f, 2f, 0), BassHit(2f, 2f, 0))
    }

    private fun renderBass(rootPc: Int, rng: XorShift, bar: Int) {
        if (spec.style == MusicStyle.AMBIENT && spec.drone) return
        val rootMidi = Notes.nearest(rootPc, 41)
        val staccato = spec.emotion == Emotion.FUNNY
        val amp = if (spec.intensity <= 3) 0.22f else 0.28f
        for (hit in bassPattern()) {
            val start = (hit.beat * spec.beatFrames).toInt()
            val len = (hit.beats * spec.beatFrames).toInt()
            val accent = if (hit.beat == 0f) 1f else 0.85f
            // Small deterministic humanization.
            val jitter = (rng.bipolar() * 0.004f * sr).toInt().coerceAtLeast(0)
            Voices.bass(tonal, accFrames, start + jitter, len, Notes.midiToHz((rootMidi + hit.interval).toFloat()), amp * accent, staccato, sr)
        }
        if (bar % 8 == 7 && spec.intensity >= 6 && spec.emotion != Emotion.SUSPENSE) {
            // Walk-up into the next phrase.
            val start = (3.5f * spec.beatFrames).toInt()
            Voices.bass(tonal, accFrames, start, (0.45f * spec.beatFrames).toInt(), Notes.midiToHz((rootMidi + 7).toFloat()), amp * 0.8f, true, sr)
        }
    }

    private fun stepStart(step: Int, steps: Int): Int {
        val beatsPerStep = 4f / steps
        var beat = step * beatsPerStep
        // Swing the off-beat eighths.
        if (spec.swing > 0f && steps == 8 && step % 2 == 1) beat += spec.swing
        return (beat * spec.beatFrames).toInt()
    }

    private fun renderArp(rootPc: Int, intervals: List<Int>, rng: XorShift, bar: Int) {
        val steps = spec.arpSteps
        if (spec.arpMode == ArpMode.NONE || steps == 0) return
        val base = Notes.nearest(rootPc, 69)
        val tones = intervals.map { base + it }
        val brightness = when (spec.style) {
            MusicStyle.LOFI -> 0.3f
            MusicStyle.ACOUSTIC -> 0.6f
            MusicStyle.ELECTRONIC -> 0.85f
            MusicStyle.AMBIENT -> 0.35f
            MusicStyle.CINEMATIC, MusicStyle.AUTO -> 0.5f
        }
        val decay = when (spec.arpMode) {
            ArpMode.STACCATO -> 0.18f
            ArpMode.SPARSE -> if (spec.style == MusicStyle.AMBIENT) 2.8f else 1.6f
            else -> if (steps >= 16) 0.5f else 0.9f
        }
        val amp = when (spec.arpMode) {
            ArpMode.OSTINATO -> 0.14f
            ArpMode.SPARSE -> 0.17f
            else -> 0.15f
        } * (if (steps >= 16) 0.8f else 1f)
        // Pattern variant changes every 2 phrases to avoid monotony.
        val variant = XorShift(spec.seed + (bar / 16) * 131L).nextInt(3)
        for (step in 0 until steps) {
            val midi: Int = when (spec.arpMode) {
                ArpMode.UP -> tones[(step + variant) % tones.size] + if ((step / tones.size) % 2 == 1) 12 else 0
                ArpMode.UP_DOWN -> {
                    val cycle = tones.size * 2 - 2
                    val p = (step + variant) % cycle.coerceAtLeast(1)
                    tones[if (p < tones.size) p else cycle - p]
                }
                ArpMode.RISING -> {
                    val span = tones.size * 2
                    val p = step % span
                    tones[p % tones.size] + 12 * (p / tones.size) - 12
                }
                ArpMode.OSTINATO -> {
                    // Root / fifth / octave figure over the i-bII movement.
                    val figure = intArrayOf(0, 7, 12, 7, 0, 7, 12 + 3, 7)
                    Notes.nearest(rootPc, 57) + figure[step % figure.size]
                }
                ArpMode.STACCATO -> tones[if (step % 2 == 0) 0 else 1 + (step / 2) % (tones.size - 1)] + if (step % 4 == 3) 12 else 0
                ArpMode.SPARSE -> tones[rng.nextInt(tones.size)] + if (rng.chance(0.3f)) 12 else 0
                ArpMode.NONE -> continue
            }
            if (spec.arpMode == ArpMode.SPARSE && rng.chance(0.45f)) continue
            if (spec.intensity <= 2 && step % 2 == 1 && rng.chance(0.5f)) continue
            val start = stepStart(step, steps)
            val accent = if (step % (steps / 4).coerceAtLeast(1) == 0) 1f else 0.75f
            val pan = if (step % 2 == 0) -0.3f else 0.3f
            Voices.pluck(
                tonal, accFrames, start, Notes.midiToHz(midi.toFloat()), amp * accent, pan,
                decay, brightness, sr, rng, scratch,
            )
        }
    }

    private fun renderStrums(rootPc: Int, intervals: List<Int>, rng: XorShift, bar: Int) {
        // Guitar-like voicing: root low, chord tones above, a doubled octave on top.
        val low = Notes.nearest(rootPc, 50)
        val voicing = listOf(low, low + 7, Notes.nearest(rootPc + intervals[1], 59), Notes.nearest(rootPc, 62), Notes.nearest(rootPc + intervals[1], 66))
        val pattern: List<Pair<Float, Boolean>> = when {
            spec.emotion == Emotion.FUNNY -> listOf(0f to true, 1f to true, 2f to true, 3f to true)
            spec.intensity <= 3 -> listOf(0f to true, 2f to true)
            spec.intensity <= 6 -> listOf(0f to true, 1f to true, 1.5f to false, 2.5f to false, 3f to true)
            else -> listOf(0f to true, 1f to true, 1.5f to false, 2.5f to false, 3f to true, 3.5f to false)
        }
        val decay = if (spec.emotion == Emotion.FUNNY) 0.35f else 1.4f
        val strumGap = (0.012f * sr).toInt()
        for ((beat, down) in pattern) {
            val start = (beat * spec.beatFrames).toInt()
            val strings = if (down) voicing else voicing.reversed().take(3)
            val amp = if (down) 0.11f else 0.07f
            strings.forEachIndexed { i, midi ->
                val pan = -0.35f + 0.7f * i / (strings.size - 1).coerceAtLeast(1)
                Voices.pluck(
                    tonal, accFrames, start + i * strumGap + (rng.nextFloat() * 0.003f * sr).toInt(),
                    Notes.midiToHz(midi.toFloat()), amp, pan, decay, 0.55f, sr, rng, scratch,
                )
            }
        }
        // Gentle lead pluck on top at higher intensity for some melodic motion.
        if (spec.intensity >= 6 && bar % 2 == 1) {
            val top = Notes.nearest(rootPc + intervals[rng.nextInt(intervals.size)], 74)
            Voices.pluck(tonal, accFrames, (2.5f * spec.beatFrames).toInt(), Notes.midiToHz(top.toFloat()), 0.1f, 0.2f, 1.2f, 0.6f, sr, rng, scratch)
        }
    }

    private fun at(beat: Float): Int = (beat * spec.beatFrames).toInt()

    private fun renderDrums(rng: XorShift, bar: Int) {
        val level = 0.75f + spec.intensity * 0.03f
        val fill = bar % 4 == 3
        when (spec.drumKit) {
            DrumKit.NONE -> Unit
            DrumKit.POP -> {
                Voices.kick(drums, accFrames, at(0f), 0.55f * level, sr, rng)
                Voices.kick(drums, accFrames, at(2f), 0.5f * level, sr, rng)
                if (spec.intensity >= 7) Voices.kick(drums, accFrames, at(2.5f), 0.35f * level, sr, rng)
                Voices.snare(drums, accFrames, at(1f), 0.3f * level, sr, rng)
                Voices.snare(drums, accFrames, at(3f), 0.3f * level, sr, rng)
                val hatSteps = if (spec.intensity >= 8) 16 else 8
                for (s in 0 until hatSteps) {
                    val amp = (if (s % 2 == 0) 0.11f else 0.07f) * level
                    Voices.hat(drums, accFrames, stepStart(s, hatSteps), amp, false, 0.25f, sr, rng)
                }
                if (fill) Voices.snare(drums, accFrames, at(3.5f), 0.2f * level, sr, rng)
            }
            DrumKit.DRIVING -> {
                for (b in 0 until 4) Voices.kick(drums, accFrames, at(b.toFloat()), 0.6f * level, sr, rng)
                Voices.snare(drums, accFrames, at(1f), 0.32f * level, sr, rng, toneHz = 220f)
                Voices.snare(drums, accFrames, at(3f), 0.32f * level, sr, rng, toneHz = 220f)
                for (b in 0 until 4) Voices.hat(drums, accFrames, at(b + 0.5f), 0.12f * level, spec.intensity >= 7, 0.2f, sr, rng)
                if (spec.intensity >= 8) {
                    for (s in 0 until 16) if (s % 2 == 1) Voices.hat(drums, accFrames, stepStart(s, 16), 0.05f * level, false, -0.25f, sr, rng)
                }
                if (fill) {
                    Voices.snare(drums, accFrames, at(3.5f), 0.22f * level, sr, rng, toneHz = 220f)
                    Voices.snare(drums, accFrames, at(3.75f), 0.26f * level, sr, rng, toneHz = 220f)
                }
            }
            DrumKit.LOFI -> {
                Voices.kick(drums, accFrames, at(0f), 0.45f * level, sr, rng)
                Voices.kick(drums, accFrames, at(2.5f + spec.swing), 0.38f * level, sr, rng)
                Voices.snare(drums, accFrames, at(1f), 0.22f * level, sr, rng, toneHz = 170f)
                Voices.snare(drums, accFrames, at(3f), 0.22f * level, sr, rng, toneHz = 170f)
                for (s in 0 until 8) {
                    if (rng.chance(0.12f)) continue
                    val amp = (if (s % 2 == 0) 0.08f else 0.05f) * level
                    Voices.hat(drums, accFrames, stepStart(s, 8), amp, false, 0.2f, sr, rng)
                }
            }
            DrumKit.TAIKO -> {
                Voices.taiko(drums, accFrames, at(0f), 0.6f * level, sr, rng)
                Voices.taiko(drums, accFrames, at(2f), 0.5f * level, sr, rng)
                if (spec.intensity >= 6) {
                    Voices.taiko(drums, accFrames, at(1.5f), 0.32f * level, sr, rng)
                    Voices.taiko(drums, accFrames, at(3.5f), 0.3f * level, sr, rng)
                }
                if (fill && spec.intensity >= 7) {
                    Voices.taiko(drums, accFrames, at(3f), 0.35f * level, sr, rng)
                    Voices.taiko(drums, accFrames, at(3.25f), 0.3f * level, sr, rng)
                    Voices.taiko(drums, accFrames, at(3.75f), 0.4f * level, sr, rng)
                }
            }
            DrumKit.HEARTBEAT -> {
                Voices.kick(drums, accFrames, at(0f), 0.45f * level, sr, rng)
                Voices.kick(drums, accFrames, at(0.3f), 0.3f * level, sr, rng)
                if (spec.intensity >= 7) {
                    Voices.kick(drums, accFrames, at(2f), 0.45f * level, sr, rng)
                    Voices.kick(drums, accFrames, at(2.3f), 0.3f * level, sr, rng)
                }
                if (spec.intensity >= 6) {
                    for (b in 0 until 4) Voices.hat(drums, accFrames, at(b + 0.5f), 0.05f * level, false, 0.35f, sr, rng)
                }
            }
        }
    }

    // ---------------------------------------------------------------- bus + master

    private fun emitBar() {
        val lofi = spec.style == MusicStyle.LOFI
        val beat = spec.beatFrames
        val width = spec.width
        for (f in 0 until barFrames) {
            val i = f * 2
            var tl = tonal[i]
            var tr = tonal[i + 1]
            if (spec.sidechain) {
                val phase = (f % beat) / beat
                val g = 1f - 0.55f * (1f - Curves.smoothstep(0f, 0.45f, phase))
                tl *= g; tr *= g
            }
            var l = tl + drums[i]
            var r = tr + drums[i + 1]
            if (lofi) {
                l = masterLpL.process(l)
                r = masterLpR.process(r)
                // Vinyl: soft hiss and sparse crackles.
                var crackle = crackleLp.process(crackleRng.bipolar()) * 0.006f
                if (crackleRng.nextFloat() < 0.0004f) crackle += crackleRng.bipolar() * 0.12f
                l += crackle
                r += crackle * 0.9f
            }
            l = masterHpL.process(l)
            r = masterHpR.process(r)
            val mid = (l + r) * 0.5f
            val side = (l - r) * 0.5f * width
            barOut[i] = Voices.softClip((mid + side) * outputGain)
            barOut[i + 1] = Voices.softClip((mid - side) * outputGain)
        }
        // Shift the tails of long notes to the front of the accumulators.
        val keep = tailFrames * 2
        System.arraycopy(tonal, barFrames * 2, tonal, 0, keep)
        System.arraycopy(drums, barFrames * 2, drums, 0, keep)
        java.util.Arrays.fill(tonal, keep, tonal.size, 0f)
        java.util.Arrays.fill(drums, keep, drums.size, 0f)
    }
}
