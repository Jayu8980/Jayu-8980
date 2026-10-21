package com.autotube.ai.pipeline.audio.library

import android.content.Context
import com.autotube.ai.core.model.Emotion
import com.autotube.ai.core.model.MusicStyle
import com.autotube.ai.core.model.SfxType
import com.autotube.ai.domain.PcmAudio
import com.autotube.ai.domain.ProjectFiles
import com.autotube.ai.domain.StepLogger
import com.autotube.ai.pipeline.audio.AudioIOImpl
import com.autotube.ai.pipeline.audio.dsp.Curves
import com.autotube.ai.pipeline.audio.dsp.LoudnessMeter
import com.autotube.ai.pipeline.audio.synth.MusicStream
import com.autotube.ai.pipeline.audio.synth.ProceduralMusicSynth
import com.autotube.ai.pipeline.audio.synth.SfxSynth
import com.autotube.ai.pipeline.audio.synth.XorShift
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs

/**
 * Resolves music and sound effects.
 *
 * Music for (emotion, intensity, style): user-imported files in importedMusicDir()/<emotion>/ first,
 * then bundled assets/music/<emotion>/, chosen deterministically by the project seed, looped with
 * crossfaded loop points and loudness-normalized to [ProceduralMusicSynth.NOMINAL_LUFS]; otherwise the
 * [ProceduralMusicSynth]. Sound effects: bundled assets/sfx/<type>/ when present, else [SfxSynth].
 */
@Singleton
class MusicLibrary @Inject constructor(
    @ApplicationContext private val context: Context,
    private val projectFiles: ProjectFiles,
    private val audioIO: AudioIOImpl,
    private val musicSynth: ProceduralMusicSynth,
    private val sfxSynth: SfxSynth,
) {
    private val sfxCache = object : LinkedHashMap<String, PcmAudio>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, PcmAudio>?): Boolean = size > 32
    }

    /** A music file that could be used for an emotion. */
    sealed interface TrackRef {
        val name: String

        data class Imported(val file: File) : TrackRef {
            override val name: String get() = file.name
        }

        data class Bundled(val assetPath: String) : TrackRef {
            override val name: String get() = assetPath.substringAfterLast('/')
        }
    }

    /** Imported tracks first, then bundled ones, each list sorted by name. */
    fun candidates(emotion: Emotion): Pair<List<TrackRef>, List<TrackRef>> {
        val imported = runCatching {
            File(projectFiles.importedMusicDir(), emotion.wireName)
                .listFiles()
                ?.filter { it.isFile && it.length() > 0 && isAudioName(it.name) }
                ?.sortedBy { it.name.lowercase() }
                ?.map { TrackRef.Imported(it) }
        }.getOrNull().orEmpty()
        val bundled = listAssets("music/${emotion.wireName}").map { TrackRef.Bundled(it) }
        return imported to bundled
    }

    /**
     * Opens an endless, loudness-normalized music stream for one music segment. Never throws for
     * missing/broken files: it falls back to the procedural synth.
     */
    suspend fun openMusic(
        emotion: Emotion,
        intensity: Int,
        style: MusicStyle,
        seed: Long,
        log: StepLogger?,
        sampleRate: Int = 48_000,
    ): MusicStream {
        val (imported, bundled) = candidates(emotion)
        val pool = imported.ifEmpty { bundled }
        if (pool.isNotEmpty()) {
            val first = pickIndex(seed, emotion, pool.size)
            for (k in pool.indices) {
                val ref = pool[(first + k) % pool.size]
                try {
                    val stream = openTrack(ref, sampleRate)
                    if (stream != null) {
                        log?.i("Music (${emotion.wireName}): ${if (ref is TrackRef.Imported) "imported" else "bundled"} track ${ref.name}")
                        return stream
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    log?.w("Music track ${ref.name} could not be used; trying another source", e)
                }
            }
        }
        val stream = withContext(Dispatchers.Default) { musicSynth.open(emotion, intensity, style, seed, sampleRate) }
        log?.i("Music (${emotion.wireName}, intensity $intensity): ${stream.label}")
        return stream
    }

    private suspend fun openTrack(ref: TrackRef, sampleRate: Int): MusicStream? {
        val pcm = when (ref) {
            is TrackRef.Imported -> audioIO.decode(ref.file, sampleRate, 2, MAX_TRACK_MS)
            is TrackRef.Bundled -> audioIO.decodeAsset(ref.assetPath, sampleRate, 2, MAX_TRACK_MS)
        }
        return withContext(Dispatchers.Default) {
            val trimmed = trimSilence(pcm)
            if (trimmed.frames < sampleRate * 4) return@withContext null // too short to loop nicely
            val lufs = LoudnessMeter.measure(trimmed.samples, trimmed.sampleRate, 2)
            if (lufs <= LoudnessMeter.SILENCE_LUFS + 1f) return@withContext null
            val gain = Curves.dbToLinear((ProceduralMusicSynth.NOMINAL_LUFS - lufs).coerceIn(-30f, 20f))
            LoopingTrackStream(trimmed, gain, crossfadeFrames = (LOOP_CROSSFADE_SEC * sampleRate).toInt(), label = ref.name)
        }
    }

    /** Sound effect, stereo at [sampleRate]: bundled asset when present, else procedural. */
    suspend fun sfx(type: SfxType, seed: Long, riserMs: Long = 2000, log: StepLogger? = null, sampleRate: Int = 48_000): PcmAudio {
        val folder = "sfx/${sfxFolder(type)}"
        val files = listAssets(folder)
        if (files.isNotEmpty()) {
            val path = files[pickIndex(seed, null, files.size)]
            val key = "asset:$path"
            synchronized(sfxCache) { sfxCache[key] }?.let { return it }
            try {
                val audio = audioIO.decodeAsset(path, sampleRate, 2, 10_000)
                if (audio.frames > 0) {
                    synchronized(sfxCache) { sfxCache[key] = audio }
                    return audio
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log?.w("Sound effect $path could not be decoded; using a generated one", e)
            }
        }
        val variant = abs(seed % 3)
        val key = "synth:${type.name}:$variant:$riserMs:$sampleRate"
        synchronized(sfxCache) { sfxCache[key] }?.let { return it }
        val audio = withContext(Dispatchers.Default) { sfxSynth.generate(type, variant, sampleRate, riserMs) }
        synchronized(sfxCache) { sfxCache[key] = audio }
        return audio
    }

    fun sfxFolder(type: SfxType): String = when (type) {
        SfxType.WHOOSH -> "whoosh"
        SfxType.RISER -> "riser"
        SfxType.IMPACT -> "impact"
        SfxType.POP -> "pop"
        SfxType.CLICK -> "click"
        SfxType.SWOOSH_SOFT -> "swoosh_soft"
    }

    private fun listAssets(folder: String): List<String> = runCatching {
        context.assets.list(folder)
            ?.filter { isAudioName(it) }
            ?.sortedBy { it.lowercase() }
            ?.map { "$folder/$it" }
    }.getOrNull().orEmpty()

    private fun isAudioName(name: String): Boolean =
        name.substringAfterLast('.', "").lowercase() in AUDIO_EXTENSIONS

    private fun pickIndex(seed: Long, emotion: Emotion?, size: Int): Int {
        if (size <= 1) return 0
        val rng = XorShift(seed * 0x9E37L + (emotion?.ordinal ?: 99))
        return rng.nextInt(size)
    }

    /** Removes leading/trailing near-silence (< -50 dBFS) so loops do not contain dead air. */
    private fun trimSilence(audio: PcmAudio): PcmAudio {
        val ch = audio.channels
        val frames = audio.frames
        val threshold = Curves.dbToLinear(-50f)
        fun loud(f: Int): Boolean {
            val base = f * ch
            for (c in 0 until ch) if (abs(audio.samples[base + c]) > threshold) return true
            return false
        }
        var start = 0
        while (start < frames && !loud(start)) start++
        var end = frames
        while (end > start && !loud(end - 1)) end--
        if (start == 0 && end == frames) return audio
        if (end <= start) return PcmAudio(FloatArray(0), audio.sampleRate, ch)
        return PcmAudio(audio.samples.copyOfRange(start * ch, end * ch), audio.sampleRate, ch)
    }

    companion object {
        val AUDIO_EXTENSIONS = setOf("mp3", "m4a", "aac", "wav", "ogg", "oga", "opus", "flac")
        /** Longest piece of a track that is decoded (memory guard); longer beds loop this part. */
        const val MAX_TRACK_MS = 3 * 60_000L
        const val LOOP_CROSSFADE_SEC = 2f
    }
}

/**
 * Plays a decoded stereo track forever: after the first pass, every loop point is an equal-power
 * crossfade from the track's tail into its head.
 */
class LoopingTrackStream(
    private val track: PcmAudio,
    private val gain: Float,
    crossfadeFrames: Int,
    override val label: String,
) : MusicStream {
    override val sampleRate: Int = track.sampleRate
    private val n = track.frames
    private val xf = crossfadeFrames.coerceIn(0, n / 3)
    private val cycle = (n - xf).coerceAtLeast(1)
    private var position = 0L

    override fun read(dst: FloatArray, frames: Int) {
        val s = track.samples
        for (f in 0 until frames) {
            val t = position++
            val iteration = t / cycle
            val phase = (t % cycle).toInt()
            var l: Float
            var r: Float
            if (iteration > 0 && phase < xf) {
                val x = phase.toFloat() / xf
                val gi = Curves.equalPowerIn(x)
                val go = Curves.equalPowerOut(x)
                val head = phase * 2
                val tail = (cycle + phase) * 2
                l = s[head] * gi + s[tail] * go
                r = s[head + 1] * gi + s[tail + 1] * go
            } else {
                val i = phase * 2
                l = s[i]
                r = s[i + 1]
            }
            dst[f * 2] = l * gain
            dst[f * 2 + 1] = r * gain
        }
    }
}
