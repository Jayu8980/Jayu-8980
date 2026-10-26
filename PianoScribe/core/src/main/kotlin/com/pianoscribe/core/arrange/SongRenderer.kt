package com.pianoscribe.core.arrange

import com.pianoscribe.core.midi.MidiWriter
import com.pianoscribe.core.perform.Instrument
import com.pianoscribe.core.perform.PlayedNote
import com.pianoscribe.core.synth.DrumSynth
import com.pianoscribe.core.synth.InstrumentSynth
import com.pianoscribe.core.synth.NoteSequencer
import com.pianoscribe.core.synth.OfflineRenderer
import com.pianoscribe.core.synth.Sf2Preset
import java.io.OutputStream
import kotlin.math.abs
import kotlin.math.roundToInt

/** Turns a [Song] into a mixed WAV, or a multi-track MIDI file. */
object SongRenderer {
    /** Level of each part in the mix. */
    private const val MELODY = 1.0f
    private const val CHORDS = 0.55f
    private const val BASS = 0.8f
    private const val DRUMS = 0.7f

    /**
     * Renders the whole song to a 16-bit mono WAV, block by block. [preset] gives the SoundFont
     * preset for an instrument, or null for its built-in model.
     */
    fun renderWav(song: Song, preset: (Instrument) -> Sf2Preset?, rate: Int, out: OutputStream, onProgress: (Float) -> Unit = {}) {
        fun seq(notes: List<PlayedNote>, instrument: Instrument) =
            NoteSequencer(InstrumentSynth(preset(instrument), rate, instrument).apply { expression = 0.5f }, notes).apply { seek(0.0) }
        val parts = listOf(
            seq(song.melody, song.melodyInstrument) to MELODY,
            seq(song.chords, song.style.chordInstrument) to CHORDS,
            seq(song.bass, Instrument.PIANO) to BASS,
        )
        val drums = DrumSynth(rate, song.drums)
        val total = ((song.end + OfflineRenderer.TAIL_SEC) * rate).roundToInt()
        OfflineRenderer.writeHeader(out, total, rate)
        val mix = FloatArray(4096)
        val part = FloatArray(mix.size)
        val bytes = ByteArray(mix.size * 2)
        var done = 0
        var lastReport = 0
        while (done < total) {
            val n = minOf(mix.size, total - done)
            mix.fill(0f, 0, n)
            for ((s, gain) in parts) {
                part.fill(0f, 0, n)
                s.render(part, 0, n, 1.0)
                for (i in 0 until n) mix[i] += gain * part[i]
            }
            drums.render(mix, 0, n, done.toLong(), DRUMS)
            for (i in 0 until n) {
                // The same gentle limiter as single-instrument renders.
                val x = mix[i] * 0.8f
                val y = if (abs(x) <= 0.8f) x else Math.copySign(0.8f + 0.2f * kotlin.math.tanh((abs(x) - 0.8f) / 0.2f), x)
                val v = (y * 32767).roundToInt().coerceIn(-32768, 32767)
                bytes[2 * i] = v.toByte(); bytes[2 * i + 1] = (v shr 8).toByte()
            }
            out.write(bytes, 0, n * 2)
            done += n
            if (done - lastReport > rate) { lastReport = done; onProgress(done.toFloat() / total) }
        }
        out.flush()
        onProgress(1f)
    }

    /** Melody, chords and bass on their General MIDI instruments, drums on channel 10. */
    fun midi(song: Song, title: String): ByteArray = MidiWriter.writeTracks(
        listOf(
            MidiWriter.Track("Melody", 0, song.melodyInstrument.gmProgram, song.melody),
            MidiWriter.Track("Chords", 1, song.style.chordInstrument.gmProgram, song.chords),
            MidiWriter.Track("Bass", 2, BASS_PROGRAM, song.bass),
            MidiWriter.Track("Drums", MidiWriter.DRUM_CHANNEL, 0, song.drums.map { PlayedNote(it.drum.gmNote, it.time, 0.1, it.velocity) }),
        ).filter { it.notes.isNotEmpty() },
        song.grid, title,
    )

    /** General MIDI Acoustic Bass, so a DAW plays the bass part as a bass. */
    private const val BASS_PROGRAM = 32
}
