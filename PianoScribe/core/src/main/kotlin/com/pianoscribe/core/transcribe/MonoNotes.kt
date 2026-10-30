package com.pianoscribe.core.transcribe

import com.pianoscribe.core.model.Hand
import com.pianoscribe.core.model.Note
import com.pianoscribe.core.model.NoteIdGenerator
import kotlin.math.log10
import kotlin.math.roundToInt

/**
 * Notes from a pitch track (one voice or melody instrument, [Crepe]): frames where a pitch is
 * sounding, the pitch smoothed and rounded to the nearest semitone, cut where the note changes
 * (a vibrato or a slide does not change it) and, for the same note played again, where the sound
 * starts afresh (a new attack after a dip in loudness, or an onset the general model heard).
 */
object MonoNotes {
    data class Options(
        /** A frame sounds when the pitch network is at least this sure. */
        val minPeriodicity: Float = 0.5f,
        val minNoteSec: Double = 0.06,
        /** Frames quieter than this (dB below the loudest) are silence. */
        val silenceDb: Double = 45.0,
        /** A new attack on the same note: loudness rises this much (dB) within 30 ms after a dip. */
        val reattackDb: Double = 6.0,
    )

    /**
     * [track] at [Crepe.FRAME_SEC] per frame; [rmsDb] the loudness per frame (same frames);
     * [onset] optionally the general model's onset probability per frame and MIDI pitch.
     */
    fun notes(
        track: Crepe.Track,
        rmsDb: FloatArray,
        ids: NoteIdGenerator = NoteIdGenerator(),
        onset: ((frame: Int, pitch: Int) -> Float)? = null,
        options: Options = Options(),
    ): List<Note> {
        val n = track.size
        if (n == 0) return emptyList()
        val loudest = rmsDb.maxOrNull() ?: 0f
        val voiced = BooleanArray(n) { track.periodicity[it] >= options.minPeriodicity && rmsDb[it] > loudest - options.silenceDb }
        // Median of 5 frames (50 ms) over voiced frames, then the nearest semitone.
        val smooth = FloatArray(n)
        for (i in 0 until n) {
            if (!voiced[i]) continue
            val w = (maxOf(0, i - 2)..minOf(n - 1, i + 2)).filter { voiced[it] }.map { track.midi[it] }.sorted()
            smooth[i] = w[w.size / 2]
        }
        val semis = IntArray(n) { if (voiced[it]) smooth[it].roundToInt() else -1 }
        // Short flickers (a different semitone for under the minimum length inside a note) take the surrounding note.
        val minFrames = (options.minNoteSec / Crepe.FRAME_SEC).roundToInt().coerceAtLeast(1)
        var i = 0
        while (i < n) {
            var j = i
            while (j < n && semis[j] == semis[i]) j++
            if (semis[i] >= 0 && j - i < minFrames) {
                val before = if (i > 0) semis[i - 1] else -1
                val after = if (j < n) semis[j] else -1
                val fill = if (before >= 0) before else after
                if (fill >= 0) for (k in i until j) semis[k] = fill
            }
            i = j
        }
        val out = ArrayList<Note>()
        i = 0
        while (i < n) {
            if (semis[i] < 0) { i++; continue }
            var j = i + 1
            while (j < n && semis[j] == semis[i] && !reattack(j, i, semis[i], rmsDb, onset, options)) j++
            if (j - i >= minFrames) {
                val pitch = semis[i]
                // The pitch network is only sure once the attack has settled: start the note where the
                // loudness rose, up to 100 ms earlier, unless it follows straight on from another note.
                var startFrame = i
                val prevEnd = out.lastOrNull()?.let { (it.end / Crepe.FRAME_SEC).roundToInt() } ?: -1
                if (prevEnd < i - 1) {
                    var bestRise = 3.0f; var at = -1
                    for (k in maxOf(i - 10, prevEnd + 1, 1)..i) {
                        val rise = rmsDb[k] - rmsDb[k - 1]
                        if (rise > bestRise) { bestRise = rise; at = k }
                    }
                    if (at >= 0) startFrame = at
                }
                val conf = (i until j).map { track.periodicity[it] }.average().toFloat()
                val peakDb = (i until j).maxOf { rmsDb[it] }
                val vel = (127 + (peakDb - loudest) * 2.0).roundToInt().coerceIn(20, 127)
                // Tuning: how far the sung or played pitch sits from the written note.
                val cents = ((i until j).map { smooth[it] - pitch }.sorted()[(j - i) / 2] * 100).coerceIn(-50f, 50f)
                out += Note(ids.next(), pitch, startFrame * Crepe.FRAME_SEC, (j - startFrame) * Crepe.FRAME_SEC, vel, conf, if (pitch >= Note.MIDDLE_C) Hand.RIGHT else Hand.LEFT, cents)
            }
            i = j
        }
        return out
    }

    /** Frame [j] starts the same note again: a dip then a rise in loudness, or a strong onset. */
    private fun reattack(j: Int, start: Int, pitch: Int, db: FloatArray, onset: ((Int, Int) -> Float)?, o: Options): Boolean {
        if (j - start < 8) return false // at least 80 ms into the note
        val rise = db[minOf(db.size - 1, j + 2)] - db[j - 1]
        val dip = db[j - 1] < (maxOf(db[maxOf(start, j - 10)], db[j - 2]) - 1.0)
        if (rise >= o.reattackDb && dip) return true
        val p = onset?.invoke(j, pitch) ?: return false
        return p >= 0.6f && (onset(j - 1, pitch) < p) && (onset(j + 1, pitch) <= p)
    }

    /** Loudness in dB per [Crepe] frame of 16 kHz [audio]. */
    fun rmsDb(audio: FloatArray, frames: Int): FloatArray = FloatArray(frames) { f ->
        val start = f * Crepe.HOP - Crepe.WINDOW / 2
        var ss = 0.0
        for (k in 0 until Crepe.WINDOW) { val j = start + k; if (j in audio.indices) ss += audio[j].toDouble() * audio[j] }
        (10 * log10(ss / Crepe.WINDOW + 1e-12)).toFloat()
    }
}
