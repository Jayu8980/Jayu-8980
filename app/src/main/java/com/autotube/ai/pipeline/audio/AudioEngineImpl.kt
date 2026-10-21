package com.autotube.ai.pipeline.audio

import com.autotube.ai.core.common.PipelineException
import com.autotube.ai.core.model.AppSettings
import com.autotube.ai.core.model.AudioMixResult
import com.autotube.ai.core.model.EditDecisionList
import com.autotube.ai.core.model.Emotion
import com.autotube.ai.core.model.MusicAction
import com.autotube.ai.core.model.MusicCue
import com.autotube.ai.core.model.PipelineStep
import com.autotube.ai.core.model.SceneAudio
import com.autotube.ai.core.model.Script
import com.autotube.ai.core.model.SfxType
import com.autotube.ai.core.model.VideoRequest
import com.autotube.ai.core.model.VoiceTimeline
import com.autotube.ai.domain.AudioEngine
import com.autotube.ai.domain.ProjectFiles
import com.autotube.ai.domain.Progress
import com.autotube.ai.domain.StepLogger
import com.autotube.ai.pipeline.audio.dsp.Curves
import com.autotube.ai.pipeline.audio.dsp.EnvelopeFollower
import com.autotube.ai.pipeline.audio.dsp.Limiter
import com.autotube.ai.pipeline.audio.dsp.LoudnessMeter
import com.autotube.ai.pipeline.audio.dsp.TruePeak
import com.autotube.ai.pipeline.audio.dsp.countOvers
import com.autotube.ai.pipeline.audio.library.MusicLibrary
import com.autotube.ai.pipeline.audio.synth.AmbienceSynth
import com.autotube.ai.pipeline.audio.synth.MusicStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * The mixer: voice + situation-based music + SFX + room tone -> one loudness-normalized stereo WAV.
 *
 * Memory: the whole mix lives in ONE interleaved stereo float buffer. The voice is placed first; the
 * voice-only QC track is written from it and the sidechain/gap analysis is taken from it at a 10 ms
 * control rate. Music, SFX and ambience are then streamed block by block straight into the same
 * buffer, and loudness normalization + limiting run in place before the 16-bit write.
 */
@Singleton
class AudioEngineImpl @Inject constructor(
    private val audioIO: AudioIOImpl,
    private val projectFiles: ProjectFiles,
    private val library: MusicLibrary,
    private val ambienceSynth: AmbienceSynth,
) : AudioEngine {

    /** One mix at a time: each mix holds a full-length buffer. */
    private val mixLock = Mutex()

    /** A run of music in one mood. Times in frames. */
    private data class MusicSegment(val startFrame: Int, val endFrame: Int, val emotion: Emotion, val intensity: Int)

    override suspend fun mix(
        projectId: Long,
        request: VideoRequest,
        script: Script,
        timeline: VoiceTimeline,
        audios: List<SceneAudio>,
        edl: EditDecisionList,
        settings: AppSettings,
        log: StepLogger,
        onProgress: Progress,
    ): AudioMixResult = mixLock.withLock {
        withContext(Dispatchers.Default) {
            mixInternal(projectId, script, timeline, audios, edl, settings, log, onProgress)
        }
    }

    private suspend fun mixInternal(
        projectId: Long,
        script: Script,
        timeline: VoiceTimeline,
        audios: List<SceneAudio>,
        edl: EditDecisionList,
        settings: AppSettings,
        log: StepLogger,
        onProgress: Progress,
    ): AudioMixResult {
        val lastVoiceEndMs = timeline.scenes.maxOfOrNull { it.audioEndMs } ?: 0L
        val totalMs = max(if (edl.totalMs > 0) edl.totalMs else timeline.totalMs, lastVoiceEndMs + 200)
        val totalFrames = msToFrames(totalMs)
        if (totalFrames <= 0) throw PipelineException("Nothing to mix", "The video has no length yet.", step = PipelineStep.MUSIC)
        checkMemory(totalFrames)
        val seed = projectId * 1_000_003L + script.title.hashCode()
        log.i("Mixing ${totalMs / 1000.0}s at $SR Hz (target ${settings.targetLufs} LUFS)")

        val out = FloatArray(totalFrames * 2)

        // ---- (a) voice track
        placeVoice(out, totalFrames, timeline, audios, log) { onProgress(0.25f * it) }
        val renderDir = projectFiles.renderDir(projectId).apply { mkdirs() }
        val index = nextIndex(renderDir)
        val voiceFile = File(renderDir, "voice_$index.wav")
        withContext(Dispatchers.IO) { WavIO.writePcm16(voiceFile, out, SR, 2) }
        onProgress(0.28f)

        // ---- analysis for ducking / gaps
        val voiceLufsRaw = LoudnessMeter.measure(out, SR, 2)
        val hasVoice = voiceLufsRaw > LoudnessMeter.SILENCE_LUFS + 1f
        val voiceLufs = if (hasVoice) voiceLufsRaw else settings.targetLufs
        if (!hasVoice) log.w("The voice track is silent; mixing music only")
        val hops = totalFrames / HOP + 2
        val activity = FloatArray(hops)
        val gap = FloatArray(hops)
        analyzeVoice(out, totalFrames, voiceLufs, activity, gap)
        onProgress(0.32f)
        currentCoroutineContext().ensureActive()

        // Levels are set relative to the voice so the final normalization lands them where intended.
        val relativeToFinal = voiceLufs - settings.targetLufs

        // ---- (b, c) music bed
        if (settings.musicVolume > 0.01f) {
            val segments = buildSegments(edl, script, timeline, totalFrames)
            val control = buildMusicControl(edl.music, hops, totalMs, lastVoiceEndMs, timeline.tailMs, activity, gap)
            val volumeDb = 20f * log10(settings.musicVolume.coerceIn(0.01f, 1.5f) / 0.8f)
            renderMusic(out, totalFrames, segments, control, settings, seed, relativeToFinal, volumeDb, log) {
                onProgress(0.32f + 0.4f * it)
            }
        } else {
            log.i("Music volume is off; skipping the music bed")
        }
        onProgress(0.72f)

        // ---- (d) sound effects
        if (settings.soundEffects && edl.sfx.isNotEmpty()) {
            placeSfx(out, totalFrames, edl, seed, voiceLufs, log)
        }
        onProgress(0.76f)

        // ---- (e) room tone
        if (settings.ambientRoomTone) {
            addAmbience(out, totalFrames, activity, seed, relativeToFinal)
        }
        onProgress(0.8f)
        currentCoroutineContext().ensureActive()

        // ---- (f) loudness + limiting
        val clipped = countOvers(out)
        if (clipped > 0) log.i("$clipped samples exceeded full scale before limiting")
        val ceilingDb = CEILING_DBTP
        var measured = LoudnessMeter.measure(out, SR, 2)
        if (measured > LoudnessMeter.SILENCE_LUFS + 1f) {
            for (pass in 0 until 2) {
                val gainDb = (settings.targetLufs - measured).coerceIn(-30f, 30f)
                if (pass > 0 && abs(gainDb) < 0.5f) break
                applyGain(out, Curves.dbToLinear(gainDb))
                val reduced = Limiter(SR, 2, ceilingDb, lookaheadMs = 5f, releaseMs = 120f).process(out)
                measured = LoudnessMeter.measure(out, SR, 2)
                log.i("Loudness pass ${pass + 1}: gain ${"%.1f".format(gainDb)} dB, limited ${reduced * 1000L / SR} ms, now ${"%.1f".format(measured)} LUFS")
                currentCoroutineContext().ensureActive()
            }
        }
        var truePeakDb = TruePeak.measureDb(out, 2)
        if (truePeakDb > ceilingDb) {
            applyGain(out, Curves.dbToLinear(ceilingDb - truePeakDb - 0.05f))
            truePeakDb = TruePeak.measureDb(out, 2)
            measured = LoudnessMeter.measure(out, SR, 2)
        }
        onProgress(0.92f)

        // ---- write
        val mixFile = File(renderDir, "mix_$index.wav")
        withContext(Dispatchers.IO) {
            WavIO.writePcm16(mixFile, out, SR, 2)
            cleanupOld(renderDir, keep = setOf(mixFile.name, voiceFile.name))
        }
        onProgress(1f)
        log.i("Mix ready: ${"%.1f".format(measured)} LUFS, true peak ${"%.1f".format(truePeakDb)} dBTP")
        return AudioMixResult(
            mixPath = mixFile.absolutePath,
            durationMs = totalFrames * 1000L / SR,
            integratedLufs = measured,
            truePeakDbfs = truePeakDb,
            clippedSamples = clipped,
            voiceTrackPath = voiceFile.absolutePath,
        )
    }

    // ------------------------------------------------------------------ voice

    private suspend fun placeVoice(
        out: FloatArray,
        totalFrames: Int,
        timeline: VoiceTimeline,
        audios: List<SceneAudio>,
        log: StepLogger,
        progress: (Float) -> Unit,
    ) {
        var placed = 0
        audios.forEachIndexed { i, audio ->
            currentCoroutineContext().ensureActive()
            val timing = timeline.timingOf(audio.sceneNumber)
            if (timing == null) {
                log.w("Scene ${audio.sceneNumber} has no place on the timeline; its voice is skipped")
                return@forEachIndexed
            }
            val file = File(audio.filePath)
            if (!file.exists()) {
                log.w("Voice file for scene ${audio.sceneNumber} is missing; skipped")
                return@forEachIndexed
            }
            val pcm = try {
                audioIO.decode(file, SR, 1)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.w("Voice for scene ${audio.sceneNumber} could not be read; skipped", e)
                return@forEachIndexed
            }
            val start = msToFrames(timing.audioStartMs)
            val n = min(pcm.frames, totalFrames - start)
            val s = pcm.samples
            for (k in 0 until n) {
                val f = start + k
                if (f < 0) continue
                val v = s[k]
                out[f * 2] += v
                out[f * 2 + 1] += v
            }
            placed++
            progress((i + 1f) / audios.size)
        }
        if (placed == 0 && audios.isNotEmpty()) {
            throw PipelineException(
                "Voiceover missing",
                "None of the voiceover files could be read. Please regenerate the voice.",
                step = PipelineStep.MUSIC,
            )
        }
    }

    /** Fills [activity] (0..1 smoothed voice presence) and [gap] (0..1 pause boost) per 10 ms hop. */
    private fun analyzeVoice(out: FloatArray, totalFrames: Int, voiceLufs: Float, activity: FloatArray, gap: FloatArray) {
        val hops = activity.size
        val follower = EnvelopeFollower(SR.toFloat() / HOP, attackMs = 40f, releaseMs = 350f, holdMs = 150f)
        val silent = BooleanArray(hops)
        val activeLow = voiceLufs - 26f
        val activeHigh = voiceLufs - 14f
        val silenceDb = voiceLufs - 32f
        for (h in 0 until hops) {
            val from = h * HOP
            val to = min(totalFrames, from + HOP)
            var sum = 0.0
            for (f in from until to) {
                val v = out[f * 2]
                sum += v * v
            }
            val rms = if (to > from) sqrt(sum / (to - from)).toFloat() else 0f
            val rmsDb = Curves.linearToDb(rms)
            silent[h] = rmsDb < silenceDb
            val envDb = Curves.linearToDb(follower.process(rms))
            activity[h] = Curves.smoothstep(activeLow, activeHigh, envDb)
        }
        // Gaps longer than ~600 ms get a gentle music raise, ramped in and out inside the gap.
        var h = 0
        while (h < hops) {
            if (!silent[h]) {
                h++; continue
            }
            val start = h
            while (h < hops && silent[h]) h++
            val end = h
            if (end - start >= GAP_MIN_HOPS) {
                val rampIn = 25
                val rampOut = 20
                for (k in start until end) {
                    val a = Curves.smoothstep((start + 8).toFloat(), (start + 8 + rampIn).toFloat(), k.toFloat())
                    val b = 1f - Curves.smoothstep((end - 4 - rampOut).toFloat(), (end - 4).toFloat(), k.toFloat())
                    gap[k] = min(a, b).coerceIn(0f, 1f)
                }
            }
        }
    }

    // ------------------------------------------------------------------ music

    private fun buildSegments(edl: EditDecisionList, script: Script, timeline: VoiceTimeline, totalFrames: Int): List<MusicSegment> {
        val sceneByNumber = script.scenes.associateBy { it.sceneNumber }
        val firstScene = timeline.scenes.minByOrNull { it.audioStartMs }?.let { sceneByNumber[it.sceneNumber] }
            ?: script.scenes.firstOrNull()
        val defaultEmotion = firstScene?.emotion ?: Emotion.CALM
        val defaultIntensity = firstScene?.intensity ?: 5

        // (startMs, emotion, intensity) change points.
        val changes = mutableListOf<Triple<Long, Emotion, Int>>()
        val edlChanges = edl.music.filter { it.action == MusicAction.CHANGE }.sortedBy { it.atMs }
        if (edlChanges.isNotEmpty()) {
            val first = edlChanges.first()
            if (first.atMs > FIRST_CHANGE_SLACK_MS) changes += Triple(0L, defaultEmotion, defaultIntensity)
            edlChanges.forEach { changes += Triple(it.atMs.coerceAtLeast(0), it.emotion, it.intensity) }
            if (changes.first().first > 0) changes[0] = changes.first().copy(first = 0L)
        } else {
            // Derive from scene emotions: change only when the emotion changes, at the middle of the gap.
            val ordered = timeline.scenes.sortedBy { it.audioStartMs }
            var prevEmotion: Emotion? = null
            var prevEnd = 0L
            val intensities = mutableListOf<Int>()
            for (timing in ordered) {
                val scene = sceneByNumber[timing.sceneNumber] ?: continue
                if (scene.emotion != prevEmotion) {
                    val at = if (prevEmotion == null) 0L else (prevEnd + timing.audioStartMs) / 2
                    if (changes.isNotEmpty()) {
                        val last = changes.removeAt(changes.size - 1)
                        changes += last.copy(third = intensities.average().roundToInt())
                    }
                    intensities.clear()
                    changes += Triple(at, scene.emotion, scene.intensity)
                    prevEmotion = scene.emotion
                }
                intensities += scene.intensity.coerceIn(1, 10)
                prevEnd = timing.audioEndMs
            }
            if (changes.isNotEmpty() && intensities.isNotEmpty()) {
                val last = changes.removeAt(changes.size - 1)
                changes += last.copy(third = intensities.average().roundToInt())
            }
            if (changes.isEmpty()) changes += Triple(0L, defaultEmotion, defaultIntensity)
        }

        // Merge changes that are too close together or do not change anything audible.
        val merged = mutableListOf<Triple<Long, Emotion, Int>>()
        for (c in changes) {
            val prev = merged.lastOrNull()
            when {
                prev == null -> merged += c
                c.first - prev.first < MIN_SEGMENT_MS -> merged[merged.size - 1] = prev.copy(second = c.second, third = c.third)
                c.second == prev.second && abs(c.third - prev.third) <= 2 -> Unit
                else -> merged += c
            }
        }
        // The first segment always starts at 0 even after merging.
        merged[0] = merged[0].copy(first = 0L)

        return merged.mapIndexed { i, c ->
            val start = msToFrames(c.first).coerceIn(0, totalFrames)
            val end = if (i + 1 < merged.size) msToFrames(merged[i + 1].first).coerceIn(start, totalFrames) else totalFrames
            MusicSegment(start, end, c.second, c.third.coerceIn(1, 10))
        }.filter { it.endFrame > it.startFrame }
    }

    /**
     * Per-hop linear gain applied on top of each segment's level: sidechain ducking, gap raise,
     * drop-outs, swells, intro fade-in and outro fade-out.
     */
    private fun buildMusicControl(
        cues: List<MusicCue>,
        hops: Int,
        totalMs: Long,
        lastVoiceEndMs: Long,
        tailMs: Long,
        activity: FloatArray,
        gap: FloatArray,
    ): FloatArray {
        val drop = FloatArray(hops) { 1f }
        val swellDb = FloatArray(hops)
        val sorted = cues.sortedBy { it.atMs }
        for ((i, cue) in sorted.withIndex()) {
            when (cue.action) {
                MusicAction.DROP_OUT -> {
                    val resume = sorted.drop(i + 1).firstOrNull { it.action == MusicAction.RESUME && it.atMs - cue.atMs <= 8_000 }
                    val silentUntil = resume?.atMs ?: (cue.atMs + if (cue.durationMs > 0) cue.durationMs else 1_500L)
                    val fadeDownMs = 250f
                    val fadeUpMs = 700f
                    val fromHop = (cue.atMs / HOP_MS).toInt().coerceIn(0, hops)
                    val toHop = ((silentUntil + fadeUpMs.toLong()) / HOP_MS).toInt().coerceIn(0, hops)
                    for (h in fromHop until toHop) {
                        val t = h * HOP_MS.toFloat()
                        val down = 1f - Curves.smoothstep(cue.atMs.toFloat(), cue.atMs + fadeDownMs, t)
                        val up = Curves.smoothstep(silentUntil.toFloat(), silentUntil + fadeUpMs, t)
                        drop[h] = min(drop[h], max(down, up))
                    }
                }
                MusicAction.SWELL -> {
                    val dur = if (cue.durationMs > 0) cue.durationMs else 2_500L
                    val fromHop = (cue.atMs / HOP_MS).toInt().coerceIn(0, hops)
                    val toHop = ((cue.atMs + dur) / HOP_MS).toInt().coerceIn(0, hops)
                    val ramp = dur * 0.3f
                    for (h in fromHop until toHop) {
                        val t = h * HOP_MS.toFloat()
                        val rise = Curves.smoothstep(cue.atMs.toFloat(), cue.atMs + ramp, t)
                        val fall = 1f - Curves.smoothstep(cue.atMs + dur - ramp, (cue.atMs + dur).toFloat(), t)
                        swellDb[h] = max(swellDb[h], SWELL_DB * min(rise, fall))
                    }
                }
                MusicAction.CHANGE, MusicAction.RESUME -> Unit
            }
        }
        val outroStart = min(lastVoiceEndMs + 300, totalMs - max(1_500L, min(tailMs, 4_000L))).coerceAtLeast(0).toFloat()
        val control = FloatArray(hops)
        for (h in 0 until hops) {
            val t = h * HOP_MS.toFloat()
            val duckDb = -DUCK_DB * activity[h] + GAP_BOOST_DB * gap[h] * (1f - activity[h])
            // Swells are for gaps: under speech they are mostly cancelled by the duck.
            val swell = swellDb[h] * (1f - 0.7f * activity[h])
            val intro = Curves.smoothstep(0f, INTRO_FADE_MS, t)
            val outro = 1f - Curves.smoothstep(outroStart, totalMs.toFloat(), t)
            control[h] = Curves.dbToLinear(duckDb + swell) * drop[h] * intro * outro
        }
        return control
    }

    private suspend fun renderMusic(
        out: FloatArray,
        totalFrames: Int,
        segments: List<MusicSegment>,
        control: FloatArray,
        settings: AppSettings,
        seed: Long,
        relativeToFinal: Float,
        volumeDb: Float,
        log: StepLogger,
        progress: (Float) -> Unit,
    ) {
        val halfXf = msToFrames(CROSSFADE_MS) / 2
        val block = FloatArray(BLOCK * 2)
        val totalWork = segments.sumOf { (it.endFrame - it.startFrame).toLong() }.coerceAtLeast(1)
        var done = 0L
        segments.forEachIndexed { i, seg ->
            val bedDb = BED_MIN_DB + (seg.intensity - 1) / 9f * (BED_MAX_DB - BED_MIN_DB)
            // Stream is at NOMINAL; bring it to bedDb on the final scale (voice-relative before normalization).
            val gainDb = bedDb + relativeToFinal - com.autotube.ai.pipeline.audio.synth.ProceduralMusicSynth.NOMINAL_LUFS + volumeDb
            val segGain = Curves.dbToLinear(gainDb)
            val stream: MusicStream = library.openMusic(
                seg.emotion, seg.intensity, settings.musicStyle,
                seed + seg.emotion.ordinal * 7919L, log, SR,
            )
            val from = if (i == 0) 0 else (seg.startFrame - halfXf).coerceAtLeast(0)
            val to = if (i == segments.lastIndex) totalFrames else (seg.endFrame + halfXf).coerceAtMost(totalFrames)
            val fadeInStart = seg.startFrame - halfXf
            val fadeOutStart = seg.endFrame - halfXf
            val xfLen = (halfXf * 2).coerceAtLeast(1).toFloat()
            var pos = from
            while (pos < to) {
                currentCoroutineContext().ensureActive()
                val n = min(BLOCK, to - pos)
                stream.read(block, n)
                for (k in 0 until n) {
                    val f = pos + k
                    var g = segGain
                    if (i > 0 && f < seg.startFrame + halfXf) g *= Curves.equalPowerIn((f - fadeInStart) / xfLen)
                    if (i < segments.lastIndex && f >= fadeOutStart) g *= Curves.equalPowerOut((f - fadeOutStart) / xfLen)
                    val h = f / HOP
                    val frac = (f - h * HOP).toFloat() / HOP
                    val c0 = control[h]
                    val c1 = control[min(h + 1, control.size - 1)]
                    g *= c0 + (c1 - c0) * frac
                    out[f * 2] += block[k * 2] * g
                    out[f * 2 + 1] += block[k * 2 + 1] * g
                }
                pos += n
                done += n
                progress((done.toFloat() / totalWork).coerceAtMost(1f))
            }
        }
    }

    // ------------------------------------------------------------------ sfx + ambience

    private suspend fun placeSfx(out: FloatArray, totalFrames: Int, edl: EditDecisionList, seed: Long, voiceLufs: Float, log: StepLogger) {
        val cues = edl.sfx.sortedBy { it.atMs }
        val lastByType = HashMap<SfxType, Long>()
        var placed = 0
        cues.forEachIndexed { i, cue ->
            val last = lastByType[cue.type]
            if (last != null && cue.atMs - last < 120) return@forEachIndexed
            lastByType[cue.type] = cue.atMs
            val riserMs = 1_500L + (abs(seed + i) % 1_500L)
            val audio = try {
                library.sfx(cue.type, seed + i * 31L, riserMs, log, SR)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.w("Sound effect ${cue.type} skipped", e)
                return@forEachIndexed
            }
            val frames = audio.frames
            if (frames == 0) return@forEachIndexed
            val at = msToFrames(cue.atMs)
            val start = when (cue.type) {
                SfxType.WHOOSH, SfxType.SWOOSH_SOFT -> at - frames / 2
                SfxType.RISER -> at - frames
                SfxType.IMPACT -> at - msToFrames(5)
                SfxType.POP, SfxType.CLICK -> at
            }
            val offsetDb = when (cue.type) {
                SfxType.IMPACT -> -12f
                SfxType.WHOOSH -> -15f
                SfxType.RISER -> -16f
                SfxType.POP -> -16f
                SfxType.SWOOSH_SOFT -> -18f
                SfxType.CLICK -> -20f
            }
            val cueDb = (20f * log10(cue.gain.coerceIn(0.05f, 1f) / 0.5f)).coerceIn(-12f, 4f)
            var peak = 0f
            for (s in audio.samples) peak = max(peak, abs(s))
            if (peak < 1e-5f) return@forEachIndexed
            // Typical peak-to-loudness ratio of speech is ~9 dB; place the SFX peak relative to that.
            val targetPeakDb = (voiceLufs + 9f + offsetDb + cueDb).coerceAtMost(-3f)
            val g = Curves.dbToLinear(targetPeakDb) / peak
            val src = audio.samples
            val ch = audio.channels
            for (k in 0 until frames) {
                val f = start + k
                if (f < 0) continue
                if (f >= totalFrames) break
                val l = src[k * ch]
                val r = if (ch > 1) src[k * ch + 1] else l
                out[f * 2] += l * g
                out[f * 2 + 1] += r * g
            }
            placed++
        }
        log.i("Placed $placed sound effects")
    }

    private suspend fun addAmbience(out: FloatArray, totalFrames: Int, activity: FloatArray, seed: Long, relativeToFinal: Float) {
        // Final level ~ -45 dBFS in quiet parts, ~6 dB lower under speech.
        val stream = ambienceSynth.open(seed, SR, AMBIENCE_DBFS + relativeToFinal)
        val block = FloatArray(BLOCK * 2)
        val underVoice = Curves.dbToLinear(-6f)
        var pos = 0
        while (pos < totalFrames) {
            currentCoroutineContext().ensureActive()
            val n = min(BLOCK, totalFrames - pos)
            stream.read(block, n)
            for (k in 0 until n) {
                val f = pos + k
                val a = activity[f / HOP]
                val g = 1f + (underVoice - 1f) * a
                out[f * 2] += block[k * 2] * g
                out[f * 2 + 1] += block[k * 2 + 1] * g
            }
            pos += n
        }
    }

    // ------------------------------------------------------------------ helpers

    private fun applyGain(samples: FloatArray, gain: Float) {
        if (gain == 1f) return
        for (i in samples.indices) samples[i] *= gain
    }

    private fun msToFrames(ms: Long): Int = (ms * SR / 1000).coerceIn(Int.MIN_VALUE.toLong() / 4, Int.MAX_VALUE.toLong() / 4).toInt()

    private fun checkMemory(totalFrames: Int) {
        val runtime = Runtime.getRuntime()
        val available = runtime.maxMemory() - (runtime.totalMemory() - runtime.freeMemory())
        val needed = totalFrames.toLong() * 2 * 4
        if (needed > available * 0.8) {
            throw PipelineException(
                "Not enough memory to mix",
                "This video is too long to mix on this phone (needs ${needed / (1024 * 1024)} MB). " +
                    "Close other apps and retry, or make a shorter video.",
                retryable = true,
                step = PipelineStep.MUSIC,
            )
        }
    }

    private fun nextIndex(dir: File): Int {
        val used = dir.listFiles()?.mapNotNull { MIX_NAME.matchEntire(it.name)?.groupValues?.get(2)?.toIntOrNull() }.orEmpty()
        return (used.maxOrNull() ?: 0) + 1
    }

    private fun cleanupOld(dir: File, keep: Set<String>) {
        dir.listFiles()?.forEach { file ->
            if (file.name !in keep && (MIX_NAME.matches(file.name) || file.name.endsWith(".wav.part"))) {
                runCatching { file.delete() }
            }
        }
    }

    private companion object {
        const val SR = 48_000
        const val HOP = 480
        const val HOP_MS = 10L
        const val BLOCK = 4096
        const val CEILING_DBTP = -1f
        const val DUCK_DB = 10.5f
        const val GAP_BOOST_DB = 3f
        const val GAP_MIN_HOPS = 60
        const val SWELL_DB = 4f
        const val INTRO_FADE_MS = 1_500f
        const val CROSSFADE_MS = 1_500L
        const val MIN_SEGMENT_MS = 3_000L
        const val FIRST_CHANGE_SLACK_MS = 3_000L
        /** Music bed loudness on the final (-14 LUFS voice) scale, from intensity 1 to 10. */
        const val BED_MIN_DB = -26f
        const val BED_MAX_DB = -18f
        const val AMBIENCE_DBFS = -45f
        val MIX_NAME = Regex("^(mix|voice)_(\\d+)\\.wav$")
    }
}
