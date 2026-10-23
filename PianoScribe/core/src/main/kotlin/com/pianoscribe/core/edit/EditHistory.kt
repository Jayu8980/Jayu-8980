package com.pianoscribe.core.edit

import com.pianoscribe.core.model.Note
import com.pianoscribe.core.model.NoteIdGenerator

import com.pianoscribe.core.model.Hand

/** Snapshot-based undo/redo. Note lists are immutable, so snapshots are cheap to keep. */
class EditHistory<T>(initial: T, private val limit: Int = 200) {
    private val undo = ArrayDeque<T>()
    private val redo = ArrayDeque<T>()
    var current: T = initial
        private set

    val canUndo: Boolean get() = undo.isNotEmpty()
    val canRedo: Boolean get() = redo.isNotEmpty()

    fun push(next: T) {
        if (next == current) return
        undo.addLast(current)
        if (undo.size > limit) undo.removeFirst()
        redo.clear()
        current = next
    }

    fun undo(): T {
        if (undo.isEmpty()) return current
        redo.addLast(current)
        current = undo.removeLast()
        return current
    }

    fun redo(): T {
        if (redo.isEmpty()) return current
        undo.addLast(current)
        current = redo.removeLast()
        return current
    }

    fun reset(value: T) {
        undo.clear(); redo.clear(); current = value
    }
}

/** Pure editing operations on note lists. */
object NoteOps {
    fun sorted(notes: List<Note>) = notes.sortedWith(compareBy({ it.start }, { it.pitch }, { it.id }))

    fun transpose(notes: List<Note>, semitones: Int): List<Note> =
        notes.map { it.copy(pitch = (it.pitch + semitones).coerceIn(Note.MIN_PIANO, Note.MAX_PIANO)) }

    /** Changes the tempo of the transcription: times scale by oldBpm/newBpm around [anchor]. */
    fun changeTempo(notes: List<Note>, oldBpm: Double, newBpm: Double, anchor: Double = 0.0): List<Note> {
        val k = oldBpm / newBpm
        return notes.map { it.copy(start = anchor + (it.start - anchor) * k, duration = it.duration * k) }
    }

    fun replace(notes: List<Note>, updated: Note): List<Note> = sorted(notes.map { if (it.id == updated.id) updated else it })

    fun delete(notes: List<Note>, id: Long): List<Note> = notes.filterNot { it.id == id }

    fun add(notes: List<Note>, note: Note): List<Note> = sorted(notes + note)

    fun move(notes: List<Note>, id: Long, dt: Double, dPitch: Int): List<Note> = sorted(notes.map {
        if (it.id != id) it else it.copy(
            start = (it.start + dt).coerceAtLeast(0.0),
            pitch = (it.pitch + dPitch).coerceIn(Note.MIN_PIANO, Note.MAX_PIANO),
        )
    })

    fun resize(notes: List<Note>, id: Long, newDuration: Double): List<Note> =
        notes.map { if (it.id == id) it.copy(duration = newDuration.coerceAtLeast(0.03)) else it }

    /** Splits a note at [at] (seconds) into two notes. */
    fun split(notes: List<Note>, id: Long, at: Double, ids: NoteIdGenerator): List<Note> {
        val n = notes.firstOrNull { it.id == id } ?: return notes
        val t = if (at > n.start + 0.02 && at < n.end - 0.02) at else n.start + n.duration / 2
        val a = n.copy(duration = t - n.start)
        val b = n.copy(id = ids.next(), start = t, duration = n.end - t)
        return sorted(notes.filterNot { it.id == id } + a + b)
    }

    /** Merges a note with the next note of the same hand: one note spanning both, keeping the first pitch. */
    fun mergeWithNext(notes: List<Note>, id: Long): List<Note> {
        val s = sorted(notes)
        val idx = s.indexOfFirst { it.id == id }
        if (idx < 0) return notes
        val n = s[idx]
        val next = s.drop(idx + 1).firstOrNull { it.hand == n.hand && it.start >= n.start && it.id != n.id } ?: return notes
        val merged = n.copy(
            duration = maxOf(n.end, next.end) - n.start,
            velocity = maxOf(n.velocity, next.velocity),
            confidence = maxOf(n.confidence, next.confidence),
        )
        return sorted(s.filterNot { it.id == id || it.id == next.id } + merged)
    }

    fun setHandBySplit(notes: List<Note>, split: Int): List<Note> =
        notes.map { it.copy(hand = if (it.pitch >= split) Hand.RIGHT else Hand.LEFT) }

    /** Trims notes to [from, to) and shifts them so [from] becomes 0. */
    fun crop(notes: List<Note>, from: Double, to: Double): List<Note> =
        notes.filter { it.end > from && it.start < to }.map {
            val s = maxOf(it.start, from)
            val e = minOf(it.end, to)
            it.copy(start = s - from, duration = e - s)
        }

}
