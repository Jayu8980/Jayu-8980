package com.pianoscribe.core

import com.pianoscribe.core.arrange.PianoCover
import com.pianoscribe.core.model.Hand
import com.pianoscribe.core.model.Note
import com.pianoscribe.core.model.TimeGrid
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PianoCoverTest {
    private var id = 0L
    private fun n(p: Int, s: Double, d: Double, v: Int = 80) = Note(++id, p, s, d, v, 0.9f, if (p >= 60) Hand.RIGHT else Hand.LEFT)
    private val grid = TimeGrid(120.0, 4, 4, 0.0)

    /** Two bars of C then two of G7-ish (G B D F), with a tune on top. */
    private fun song(): List<Note> {
        val out = ArrayList<Note>()
        listOf(listOf(48, 52, 55), listOf(48, 52, 55), listOf(43, 47, 50, 53), listOf(43, 47, 50, 53)).forEachIndexed { bar, ch ->
            ch.forEach { out += n(it, bar * 2.0, 1.9, 60) }
            listOf(72, 74, 76, 77).forEachIndexed { k, p -> out += n(p + if (bar >= 2) 2 else 0, bar * 2.0 + k * 0.5, 0.45, 90) }
        }
        return out
    }

    @Test fun followsTheSongsChords() {
        val cover = PianoCover.make(song(), grid, null, PianoCover.Pattern.BLOCK)
        val left = cover.filter { it.hand == Hand.LEFT }
        // Bar 1: C major (bass C2), bar 3: G major (bass G2).
        assertEquals(36, left.filter { it.start < 0.01 }.minOf { it.pitch })
        assertEquals(43, left.filter { it.start in 3.99..4.01 }.minOf { it.pitch })
        assertTrue(left.filter { it.start < 0.01 }.map { it.pitch % 12 }.toSet() == setOf(0, 4, 7))
    }

    @Test fun tuneStaysInTheRightHandAsPlayed() {
        val cover = PianoCover.make(song(), grid, null, PianoCover.Pattern.ALBERTI)
        val right = cover.filter { it.hand == Hand.RIGHT }
        assertEquals(16, right.size)
        assertEquals(72, right.first().pitch)
        // Alberti: eight eighths per 4/4 bar, root-fifth-third-fifth.
        val bar1 = cover.filter { it.hand == Hand.LEFT && it.start < 1.99 }.map { it.pitch }
        assertEquals(listOf(48, 55, 52, 55, 48, 55, 52, 55), bar1)
    }

    @Test fun waltzPatternInThree() {
        val g3 = TimeGrid(120.0, 3, 4, 0.0)
        val notes = (0 until 4).flatMap { bar -> listOf(n(48, bar * 1.5, 1.4, 60), n(52, bar * 1.5, 1.4, 60), n(55, bar * 1.5, 1.4, 60), n(76, bar * 1.5, 1.4, 90)) }
        val left = PianoCover.make(notes, g3, null, PianoCover.Pattern.OOM_PAH).filter { it.hand == Hand.LEFT && it.start < 1.49 }
        // Bass on 1, two-note chords on 2 and 3.
        assertEquals(listOf(0.0, 0.5, 0.5, 1.0, 1.0), left.map { it.start })
        assertEquals(36, left.first().pitch)
    }

    @Test fun aTuneAloneGetsChordsThatFit() {
        val tune = listOf(60, 64, 67, 72, 71, 67, 65, 62).mapIndexed { i, p -> n(p, i * 0.5, 0.45) }
        val cover = PianoCover.make(tune, grid, null, PianoCover.Pattern.BLOCK)
        assertTrue(cover.any { it.hand == Hand.LEFT })
    }
}
