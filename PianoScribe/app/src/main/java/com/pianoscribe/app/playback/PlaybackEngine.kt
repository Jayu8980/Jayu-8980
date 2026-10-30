package com.pianoscribe.app.playback

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import com.pianoscribe.app.audio.PcmFile
import com.pianoscribe.core.audio.TimeStretch
import com.pianoscribe.core.model.Note
import com.pianoscribe.core.model.TimeGrid
import com.pianoscribe.core.perform.Instrument
import com.pianoscribe.core.perform.Performer
import com.pianoscribe.core.perform.PlayedNote
import com.pianoscribe.core.synth.NoteSequencer
import com.pianoscribe.core.synth.InstrumentSynth
import com.pianoscribe.core.synth.Sf2Preset
import com.pianoscribe.core.transcribe.BasicPitch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.io.RandomAccessFile

enum class PlayMode { NOTES, ORIGINAL, BOTH }

/**
 * Streams audio through one AudioTrack: synthesised notes, the original recording (time-stretched
 * so speed changes keep the pitch), or both mixed. Song time is the master clock; loops and
 * speed changes apply on the fly.
 */
class PlaybackEngine(preset: Sf2Preset?, private val audioFile: File?, instrument: Instrument = Instrument.PIANO) {
    companion object {
        const val OUT_RATE = 44100
        private const val BLOCK = 1024
        private const val CLICK_LEN = OUT_RATE / 25 // 40 ms
        private const val SRC_RATE = BasicPitch.SAMPLE_RATE
    }

    data class State(val playing: Boolean = false, val position: Double = 0.0)

    private var synth = InstrumentSynth(preset, OUT_RATE, instrument)
    private var previewSynth = InstrumentSynth(preset, OUT_RATE, instrument)
    private var currentPreset = preset
    private val lock = Object()
    private var notes: List<PlayedNote> = emptyList()
    private var sequencer = NoteSequencer(synth, emptyList())
    private var preview: NoteSequencer? = null
    private var previewEnd = 0.0
    private val liveKeys = HashSet<Int>()

    @Volatile var mode: PlayMode = PlayMode.NOTES
    @Volatile var speed: Double = 1.0
        set(v) { field = v.coerceIn(0.25, 2.0) }
    @Volatile var loop: ClosedFloatingPointRange<Double>? = null
    @Volatile var duration: Double = 0.0
    /** Beats to click on while playing (null = metronome off). */
    @Volatile var metronome: TimeGrid? = null
    /** Samples into the current click (past [CLICK_LEN] = silent), and its pitch. */
    private var clickPos = Int.MAX_VALUE
    private var clickHz = 1000.0

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    @Volatile private var playing = false
    /** Whether the AudioTrack is started; a seek pauses and flushes it, so the render loop restarts it. */
    @Volatile private var trackPlaying = false
    @Volatile private var running = true
    private var songTime = 0.0
    private var framesWritten = 0L

    // Original audio path.
    private val raf: RandomAccessFile? = audioFile?.takeIf { it.exists() }?.let { RandomAccessFile(it, "r") }
    private val srcLength = audioFile?.let { PcmFile.length(it) } ?: 0
    private var srcPos = 0
    private val stretch = TimeStretch(SRC_RATE, 1.0)
    private val fifo = FloatFifo()
    private var lastSrcSample = 0f
    private var upPhase = 0.0

    private val track: AudioTrack = AudioTrack.Builder()
        .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
        .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_FLOAT).setSampleRate(OUT_RATE).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
        .setBufferSizeInBytes(maxOf(AudioTrack.getMinBufferSize(OUT_RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_FLOAT), BLOCK * 4 * 4))
        .setTransferMode(AudioTrack.MODE_STREAM)
        .build()

    private val thread = Thread({ loop() }, "PianoScribe-playback").apply { priority = Thread.MAX_PRIORITY }

    /** The part to play: transcribed notes as performed on the current instrument. */
    fun setNotes(n: List<PlayedNote>) = synchronized(lock) {
        notes = n
        sequencer = NoteSequencer(synth, n)
        sequencer.seek(songTime)
    }

    /** Switches the instrument; [expression] scales vibrato of the built-in wind and string models. */
    fun setInstrument(instrument: Instrument, preset: Sf2Preset?, expression: Float) = synchronized(lock) {
        if (synth.instrument != instrument || currentPreset !== preset) {
            currentPreset = preset
            synth = InstrumentSynth(preset, OUT_RATE, instrument)
            previewSynth = InstrumentSynth(preset, OUT_RATE, instrument)
            liveKeys.clear()
            preview = null
            sequencer = NoteSequencer(synth, notes)
            sequencer.seek(songTime)
        }
        synth.expression = expression
        previewSynth.expression = expression
    }

    fun play() = synchronized(lock) {
        if (songTime >= duration - 0.05 && loop == null) seekLocked(0.0)
        playing = true
        _state.value = State(true, songTime)
    }

    fun pause() = synchronized(lock) {
        playing = false
        synth.allNotesOff()
        _state.value = State(false, currentPosition())
    }

    fun toggle() = if (playing) pause() else play()

    fun seek(t: Double) = synchronized(lock) {
        seekLocked(t.coerceIn(0.0, maxOf(duration, 0.0)))
        _state.value = State(playing, songTime)
    }

    private fun seekLocked(t: Double) {
        songTime = t
        sequencer.seek(t)
        srcPos = (t * SRC_RATE).toInt().coerceIn(0, srcLength)
        stretch.reset()
        fifo.clear()
        upPhase = 0.0
        runCatching { track.pause(); track.flush() }
        trackPlaying = false
        framesWritten = 0
        basePosition = t
    }

    /** Plays just these notes (a note or chord in the analyser), independent of the transport. */
    fun preview(n: List<Note>) = synchronized(lock) {
        if (n.isEmpty()) return@synchronized
        val t0 = n.minOf { it.start }
        val shifted = n.map { PlayedNote.of(it).copy(start = it.start - t0, pitch = Performer.fold(it.pitch, previewSynth.instrument)) }
        previewSynth.allNotesOff(immediate = true)
        preview = NoteSequencer(previewSynth, shifted).also { it.seek(0.0) }
        previewEnd = shifted.maxOf { it.end }.coerceAtMost(4.0) + 1.0
    }

    /** Sounds a key from a connected MIDI keyboard. */
    fun liveNoteOn(key: Int, vel: Int) = synchronized(lock) { previewSynth.noteOn(key, vel); liveKeys += key }
    fun liveNoteOff(key: Int) = synchronized(lock) { previewSynth.noteOff(key); liveKeys -= key }

    private var basePosition = 0.0

    /** Song position actually being heard (rendered position minus what is still buffered). */
    private fun currentPosition(): Double {
        val head = runCatching { track.playbackHeadPosition.toLong() and 0xFFFFFFFFL }.getOrDefault(0L)
        val buffered = (framesWritten - head).coerceAtLeast(0)
        return (songTime - buffered.toDouble() / OUT_RATE * speed).coerceAtLeast(basePosition.coerceAtMost(songTime))
    }

    private fun loop() {
        val out = FloatArray(BLOCK)
        var lastPublish = 0L
        while (running) {
            var wrote = false
            synchronized(lock) {
                out.fill(0f)
                val previewing = preview != null || liveKeys.isNotEmpty() || previewSynth.activeVoices > 0
                if (playing) {
                    val sp = speed
                    if (mode != PlayMode.ORIGINAL) sequencer.render(out, 0, BLOCK, sp)
                    else { scratch.fill(0f); sequencer.render(scratch, 0, BLOCK, sp) }
                    if (mode != PlayMode.NOTES) renderOriginal(out, sp, if (mode == PlayMode.BOTH) 0.8f else 1f)
                    metronome?.let { clicks(out, songTime, sequencer.songTime, sp, it) }
                    songTime = sequencer.songTime
                    val l = loop
                    if (l != null && songTime >= l.endInclusive) {
                        seekLockedKeepTrack(l.start)
                    } else if (l == null && songTime >= duration + 1.0) {
                        playing = false
                        synth.allNotesOff()
                        _state.value = State(false, duration)
                    }
                }
                preview?.let { p ->
                    p.render(out, 0, BLOCK, 1.0)
                    if (p.songTime >= previewEnd) preview = null
                } ?: run { if (previewing) previewSynth.render(out, 0, BLOCK) }
                if (playing || previewing) {
                    for (i in out.indices) out[i] = softClip(out[i])
                    wrote = true
                }
            }
            if (wrote) {
                synchronized(lock) { if (!trackPlaying) { track.play(); trackPlaying = true } }
                val n = track.write(out, 0, BLOCK, AudioTrack.WRITE_BLOCKING)
                synchronized(lock) { framesWritten += n.coerceAtLeast(0) }
                // A short write means the track was flushed by a seek or the audio device went away;
                // wait one block so song time never runs ahead of real time.
                if (n < BLOCK) try { Thread.sleep(BLOCK * 1000L / OUT_RATE) } catch (_: InterruptedException) { }
                val now = System.nanoTime()
                if (playing && now - lastPublish > 16_000_000L) {
                    lastPublish = now
                    _state.value = State(true, synchronized(lock) { currentPosition() })
                }
            } else {
                if (trackPlaying) { runCatching { track.pause() }; trackPlaying = false }
                try { Thread.sleep(10) } catch (_: InterruptedException) { }
            }
        }
    }

    /**
     * Metronome: a short click at every beat between song times [t0] and [t1] of this block, at the
     * exact sample (so it stays in time at any speed); the first beat of a bar is higher.
     */
    private fun clicks(out: FloatArray, t0: Double, t1: Double, sp: Double, grid: TimeGrid) {
        fun ring(from: Int) {
            var i = from
            while (i < out.size && clickPos < CLICK_LEN) {
                val t = clickPos.toDouble() / OUT_RATE
                out[i] += (0.3 * kotlin.math.sin(2 * Math.PI * clickHz * t) * kotlin.math.exp(-t * 90)).toFloat()
                clickPos++; i++
            }
        }
        ring(0) // the end of a click from the previous block
        val b0 = grid.timeToBeats(t0)
        val b1 = grid.timeToBeats(t1)
        var k = Math.floor(b0).toLong() + 1
        if (b0 == Math.floor(b0) && t0 <= 1e-9) k = Math.floor(b0).toLong() // the very first beat
        while (k <= b1) {
            val at = (((grid.beatsToTime(k.toDouble()) - t0) / sp) * OUT_RATE).toInt().coerceIn(0, out.size - 1)
            clickPos = 0
            clickHz = if (Math.floorMod(k, grid.beatsPerBar.toLong()) == 0L) 1600.0 else 1000.0
            ring(at)
            k++
        }
    }

    /** Seek used inside the render loop (keeps already-buffered audio to avoid a gap at the loop point). */
    private fun seekLockedKeepTrack(t: Double) {
        songTime = t
        sequencer.seek(t)
        srcPos = (t * SRC_RATE).toInt().coerceIn(0, srcLength)
        stretch.reset()
        fifo.clear()
        upPhase = 0.0
    }

    private val srcChunk = FloatArray(512)
    private val scratch = FloatArray(BLOCK)

    private fun renderOriginal(out: FloatArray, sp: Double, gain: Float) {
        val r = raf ?: return
        stretch.speed = sp
        // Produce 22050 Hz stretched samples until the 2x upsampler has enough for this block.
        val needed = (BLOCK / 2.0 + 4).toInt()
        var guard = 0
        while (fifo.size < needed && guard++ < 64) {
            val n = if (srcPos < srcLength) PcmFile.read(r, srcPos, srcChunk, 0, srcChunk.size) else 0
            if (n <= 0) {
                fifo.push(FloatArray(needed))
                break
            }
            srcPos += n
            fifo.push(stretch.process(srcChunk, 0, n))
        }
        // Linear upsampling 22050 -> 44100.
        for (i in out.indices) {
            if (upPhase >= 1.0) {
                lastSrcSample = fifo.pop()
                upPhase -= 1.0
            }
            val next = fifo.peek()
            out[i] += (lastSrcSample + (next - lastSrcSample) * upPhase.toFloat()) * gain
            upPhase += SRC_RATE.toDouble() / OUT_RATE
        }
    }

    private fun softClip(x: Float): Float = x.coerceIn(-1f, 1f)

    fun release() {
        running = false
        thread.interrupt()
        runCatching { thread.join(500) }
        runCatching { track.stop() }
        track.release()
        runCatching { raf?.close() }
    }

    // Declared last so every field above is initialised before the render thread runs.
    init { thread.start() }

    /** Growable float FIFO. */
    private class FloatFifo {
        private var buf = FloatArray(8192)
        private var head = 0
        var size = 0
            private set

        fun clear() { head = 0; size = 0 }

        fun push(x: FloatArray) {
            if (size + x.size > buf.size) {
                val nb = FloatArray(maxOf(buf.size * 2, size + x.size))
                for (i in 0 until size) nb[i] = buf[(head + i) % buf.size]
                buf = nb; head = 0
            }
            for (v in x) buf[(head + size++) % buf.size] = v
        }

        fun pop(): Float {
            if (size == 0) return 0f
            val v = buf[head]
            head = (head + 1) % buf.size
            size--
            return v
        }

        fun peek(): Float = if (size == 0) 0f else buf[head]
    }
}
