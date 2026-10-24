package com.pianoscribe.core.transcribe

import com.pianoscribe.core.model.Note

/** How the audio should be treated. */
enum class AudioType { AUTO, SOLO_PIANO, FULL_SONG }

/**
 * Clean-up for transcriptions of full songs (vocals, drums, several instruments), where the model
 * hears much more than a pianist would play: overtone "ghosts" an octave or a fifth above real
 * notes, faint background notes, and far too many simultaneous notes.
 *
 * Thresholds were tuned on a synthetic piano + drums song scored against its known notes: real
 * notes were at least 72% as loud as the loudest nearby note in 90% of cases, wrong ones below 63%.
 * Joining fragmented same-pitch notes was tried and dropped: it merged genuine repeated notes.
 */
object SongCleanup {
    data class Options(
        /** Notes quieter than this fraction of the loudest note sounding nearby are dropped. */
        val relativeLevel: Float = 0.55f,
        /** Window around a note used to judge "nearby" loudness (seconds). */
        val levelWindow: Double = 1.0,
        /** Most notes kept sounding at once in the right and left hand. */
        val maxRightHand: Int = 4,
        val maxLeftHand: Int = 3,
        /** Split between hands used for the polyphony limit. */
        val splitPitch: Int = Note.MIDDLE_C,
    )

    fun apply(notes: List<Note>, o: Options = Options()): List<Note> {
        var n = removeOvertoneGhosts(notes)
        n = removeQuietNotes(n, o.relativeLevel, o.levelWindow)
        n = limitPolyphony(n, o.splitPitch, o.maxRightHand, o.maxLeftHand)
        return n.sortedWith(compareBy({ it.start }, { it.pitch }))
    }

    /** Joins consecutive notes of the same pitch whose gap is shorter than [gap]. */
    fun mergeFragments(notes: List<Note>, gap: Double): List<Note> {
        val out = ArrayList<Note>(notes.size)
        for ((_, list) in notes.groupBy { it.pitch }) {
            var cur: Note? = null
            for (x in list.sortedBy { it.start }) {
                val c = cur
                cur = if (c != null && x.start - c.end < gap) {
                    c.copy(
                        duration = maxOf(c.end, x.end) - c.start,
                        velocity = maxOf(c.velocity, x.velocity),
                        confidence = maxOf(c.confidence, x.confidence),
                    )
                } else {
                    c?.let { out += it }
                    x
                }
            }
            cur?.let { out += it }
        }
        return out
    }

    /**
     * Drops a note that sits 12, 19 or 24 semitones (octave, octave + fifth, two octaves: the
     * strongest overtones) above a clearly louder note that starts at about the same time and
     * covers it. A melody note that is about as loud as the bass below it is kept.
     */
    fun removeOvertoneGhosts(notes: List<Note>, onsetTolerance: Double = 0.05, maxRelativeLevel: Float = 0.7f): List<Note> {
        val sorted = notes.sortedBy { it.start }
        val drop = HashSet<Long>()
        for ((i, hi) in sorted.withIndex()) {
            var j = i - 1
            // Look back and forward over notes starting within the tolerance.
            val candidates = ArrayList<Note>()
            while (j >= 0 && hi.start - sorted[j].start <= onsetTolerance) { candidates += sorted[j]; j-- }
            j = i + 1
            while (j < sorted.size && sorted[j].start - hi.start <= onsetTolerance) { candidates += sorted[j]; j++ }
            val ghost = candidates.any { lo ->
                (hi.pitch - lo.pitch) in GHOST_INTERVALS &&
                    hi.velocity < lo.velocity * maxRelativeLevel &&
                    lo.end >= hi.end - 0.05
            }
            if (ghost) drop += hi.id
        }
        return notes.filterNot { it.id in drop }
    }

    /** Drops notes much quieter than the loudest note sounding within [window] seconds. */
    fun removeQuietNotes(notes: List<Note>, relative: Float, window: Double): List<Note> {
        val sorted = notes.sortedBy { it.start }
        return sorted.filter { n ->
            var loudest = 0
            for (m in sorted) {
                if (m.start > n.start + window) break
                if (m.end < n.start - window) continue
                if (m.velocity > loudest) loudest = m.velocity
            }
            n.velocity >= loudest * relative || n.verified
        }
    }

    /**
     * Keeps at most [maxRh] notes sounding at once at or above [split] and [maxLh] below it,
     * preferring louder and more confident notes, then the melody (highest) and the bass (lowest).
     */
    fun limitPolyphony(notes: List<Note>, split: Int, maxRh: Int, maxLh: Int): List<Note> {
        val keep = ArrayList<Note>()
        for (hand in listOf(true, false)) {
            val limit = if (hand) maxRh else maxLh
            val hn = notes.filter { (it.pitch >= split) == hand }
                .sortedWith(compareByDescending<Note> { it.velocity * (0.5f + it.confidence) }.thenByDescending { if (hand) it.pitch else -it.pitch })
            val kept = ArrayList<Note>()
            for (n in hn) {
                val overlapping = kept.count { it.start < n.end - 0.02 && n.start < it.end - 0.02 }
                if (overlapping < limit) kept += n
            }
            keep += kept
        }
        return keep
    }

    /** True when there are clearly too few notes for the audio length, so a more sensitive retry is worthwhile. */
    fun tooFewNotes(noteCount: Int, durationSec: Double): Boolean = noteCount < maxOf(3.0, durationSec * 0.4)

    /**
     * Percussive share above which "Auto" treats the audio as a full song with drums. Calibrated on
     * synthetic audio: solo piano scores about 0.16-0.18, piano with a drum kit about 0.30-0.32.
     */
    const val PERCUSSIVE_SONG_THRESHOLD = 0.24

    private val GHOST_INTERVALS = setOf(12, 19, 24)

    /** Settings for the retry when a song gave too few notes. */
    fun sensitive(s: TranscriptionSettings) = s.copy(
        onsetThreshold = minOf(s.onsetThreshold, 0.3f),
        frameThreshold = minOf(s.frameThreshold, 0.2f),
    )
}
