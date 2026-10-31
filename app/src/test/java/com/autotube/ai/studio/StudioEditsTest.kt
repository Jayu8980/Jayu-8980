package com.autotube.ai.studio

import com.autotube.ai.core.common.AppJson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class StudioEditsTest {

    private fun video(id: String, lengthMs: Long, speed: Float = 1f) =
        StudioClip(id = id, path = "/$id.mp4", isImage = false, hasAudio = true, sourceDurationMs = lengthMs, speed = speed)

    private fun photo(id: String, lengthMs: Long) =
        StudioClip(id = id, path = "/$id.jpg", isImage = true, hasAudio = false, sourceDurationMs = lengthMs)

    private val project = StudioProject(clips = listOf(video("a", 4_000), photo("b", 3_000), video("c", 6_000, speed = 2f)))

    @Test
    fun durationsFollowTrimAndSpeed() {
        assertEquals(4_000 + 3_000 + 3_000L, project.durationMs)
        assertEquals(listOf(0L, 4_000L, 7_000L), project.clipStarts().toList())
        assertEquals(1, project.clipIndexAt(4_000))
        assertEquals(2, project.clipIndexAt(99_999))
    }

    @Test
    fun splitKeepsTotalLengthAndMapsSourceTimeThroughSpeed() {
        val (split, rightId) = StudioEdits.splitClipAt(project, 8_000)
        assertNotNull(rightId)
        assertEquals(project.durationMs, split.durationMs)
        assertEquals(4, split.clips.size)
        val left = split.clips[2]
        val right = split.clips[3]
        // 1 s into a 2x clip is 2 s into the source.
        assertEquals(2_000L, left.trimEndMs)
        assertEquals(2_000L, right.trimStartMs)
        assertEquals(ClipTransition.NONE, left.transition)
    }

    @Test
    fun splitTooCloseToEdgeIsIgnored() {
        val (same, id) = StudioEdits.splitClipAt(project, 4_050)
        assertNull(id)
        assertSame(project, same)
    }

    @Test
    fun trimIsClampedToSource() {
        val trimmed = StudioEdits.trimClip(project, "a", -500, 10_000)
        assertEquals(0L, trimmed.clip("a")!!.trimStartMs)
        assertEquals(4_000L, trimmed.clip("a")!!.trimEndMs)
        val tiny = StudioEdits.trimClip(project, "a", 3_990, 3_995)
        assertEquals(StudioEdits.MIN_MS, tiny.clip("a")!!.trimmedMs)
    }

    @Test
    fun trimToPlayheadDeletesLeftOrRight() {
        val keepRight = StudioEdits.trimToPlayhead(project, 1_000, keepRight = true)
        assertEquals(1_000L, keepRight.clip("a")!!.trimStartMs)
        val keepLeft = StudioEdits.trimToPlayhead(project, 5_000, keepRight = false)
        assertEquals(1_000L, keepLeft.clip("b")!!.trimmedMs)
    }

    @Test
    fun moveDuplicateDelete() {
        val moved = StudioEdits.moveClip(project, "a", 5)
        assertEquals(listOf("b", "c", "a"), moved.clips.map { it.id })
        val (dup, newId) = StudioEdits.duplicateClip(project, "b")
        assertEquals(listOf("a", "b", newId, "c"), dup.clips.map { it.id })
        assertEquals(listOf("a", "c"), StudioEdits.deleteClip(project, "b").clips.map { it.id })
    }

    @Test
    fun speedIsClamped() {
        val p = StudioEdits.updateClip(project, "a") { it.copy(speed = 10f) }
        assertEquals(StudioEdits.MAX_SPEED, p.clip("a")!!.speed)
        val photoSpeed = StudioEdits.updateClip(project, "b") { it.copy(speed = 3f) }
        assertEquals(1f, photoSpeed.clip("b")!!.speed)
    }

    @Test
    fun audioSplitAndLanes() {
        val music = AudioLayer(id = "m", path = "/m.mp3", name = "m", startMs = 1_000, trimStartMs = 500, durationMs = 5_000, sourceDurationMs = 20_000, fadeInMs = 300, fadeOutMs = 300)
        val p = StudioEdits.addAudio(project, music)
        val (split, rightId) = StudioEdits.splitAudio(p, "m", 3_000)
        val left = split.audioLayer("m")!!
        val right = split.audioLayer(rightId!!)!!
        assertEquals(2_000L, left.durationMs)
        assertEquals(0L, left.fadeOutMs)
        assertEquals(3_000L, right.startMs)
        assertEquals(2_500L, right.trimStartMs)
        assertEquals(3_000L, right.durationMs)
        assertEquals(0L, right.fadeInMs)

        val overlapping = StudioEdits.addAudio(p, music.copy(id = "v", startMs = 2_000))
        val lanes = StudioEdits.audioLanes(overlapping.audio)
        assertEquals(0, lanes["m"])
        assertEquals(1, lanes["v"])
    }

    @Test
    fun audioLengthCannotPassEndOfFile() {
        val music = AudioLayer(id = "m", path = "/m.mp3", name = "m", durationMs = 50_000, sourceDurationMs = 10_000, trimStartMs = 4_000)
        val layer = StudioEdits.addAudio(project, music).audioLayer("m")!!
        assertEquals(6_000L, layer.durationMs)
    }

    @Test
    fun textSplitAndClamp() {
        val p = StudioEdits.addText(project, TextLayer(id = "t", text = "Hi", startMs = 1_000, endMs = 4_000, x = 2f, size = 9f))
        val t = p.text("t")!!
        assertEquals(1f, t.x)
        assertEquals(0.3f, t.size)
        val (split, rightId) = StudioEdits.splitText(p, "t", 2_000)
        assertEquals(2_000L, split.text("t")!!.endMs)
        assertEquals(2_000L, split.text(rightId!!)!!.startMs)
    }

    @Test
    fun ratioSizesAreEven() {
        assertEquals(1080 to 1920, CanvasRatio.R9_16.sizeFor(1080))
        assertEquals(1920 to 1080, CanvasRatio.R16_9.sizeFor(1080))
        assertEquals(1080 to 1350, CanvasRatio.R4_5.sizeFor(1080))
        val (w, h) = CanvasRatio.R21_9.sizeFor(1080)
        assertEquals(0, w % 2)
        assertEquals(0, h % 2)
    }

    @Test
    fun projectRoundTripsThroughJson() {
        val p = StudioEdits.addText(project, TextLayer(text = "Hello", startMs = 0, endMs = 1_000, font = TextFont.SERIF))
        val json = AppJson.encodeToString(StudioProject.serializer(), p)
        assertEquals(p, AppJson.decodeFromString(StudioProject.serializer(), json))
    }
}
