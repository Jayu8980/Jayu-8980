@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package com.pianoscribe.app

import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.pianoscribe.app.audio.AudioDecoder
import com.pianoscribe.app.data.ProjectEntity
import com.pianoscribe.core.model.Hand
import com.pianoscribe.core.model.Note
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Opens the real UI: home, a project, every result tab and the main dialogs, checking nothing crashes. */
@RunWith(AndroidJUnit4::class)
class UiSmokeTest {
    @get:Rule val compose = createEmptyComposeRule()

    private val container get() = (TestFixtures.app.applicationContext as PianoScribeApp).container

    private fun waitForText(text: String, timeoutMs: Long = 20_000, substring: Boolean = false) =
        compose.waitUntilAtLeastOneExists(hasText(text, substring = substring), timeoutMs)

    /** A project with the test recording and its expected notes, as if it had just been converted. */
    private fun seedProject(name: String): Long = runBlocking {
        val repo = container.repository
        val now = System.currentTimeMillis()
        val id = repo.create(ProjectEntity(name = name, createdAt = now, updatedAt = now, audioPath = "", durationSec = TestFixtures.DURATION_SEC,
            bpm = 120.0, beatsPerBar = 4, beatUnit = 4, gridOffset = 0.25, splitPoint = 60, quantizeDivision = 0, keyName = "C major", noteCount = 0, source = "Test"))
        val rel = "projects/$id/audio.f32"
        val audio = File(TestFixtures.app.filesDir, rel).apply { parentFile?.mkdirs() }
        AudioDecoder(TestFixtures.app).decode(TestFixtures.wavUri(), audio) {}
        val notes = TestFixtures.expected.mapIndexed { i, e -> Note(i + 1L, e.pitch, e.start, e.duration, 85, 0.9f, if (e.pitch >= 60) Hand.RIGHT else Hand.LEFT) }
        repo.save(repo.load(id)!!.entity.copy(audioPath = rel), notes)
        id
    }

    @Test fun homeShowsImportAndRecord() {
        ActivityScenario.launch(MainActivity::class.java).use {
            waitForText("Import Audio")
            compose.onNodeWithText("Import Audio").assertIsDisplayed()
            compose.onNodeWithText("Record").assertIsDisplayed()
            compose.onNodeWithContentDescription("Settings").performClick()
            waitForText("Transcription")
            compose.onNodeWithText("Audio type").assertIsDisplayed()
        }
    }

    /** The note count shown above the piano roll ("14 notes · 120 BPM ..."). */
    private fun shownNoteCount(): Int? = compose.onAllNodes(hasText(" notes · ", substring = true)).fetchSemanticsNodes()
        .firstNotNullOfOrNull { n -> n.config.getOrNull(SemanticsProperties.Text)?.joinToString { it.text } }
        ?.let { Regex("""(\d+) notes""").find(it)?.groupValues?.get(1)?.toInt() }

    @Test fun difficultyLevels() {
        val name = "Easy song ${System.currentTimeMillis() % 100000}"
        val id = seedProject(name)
        try {
            ActivityScenario.launch(MainActivity::class.java).use {
                waitForText(name)
                compose.onNodeWithText(name).performClick()
                waitForText("Piano Roll")
                val all = TestFixtures.expected.size
                compose.waitUntil(10_000) { shownNoteCount() == all }
                compose.onNodeWithText("Beginner").performClick()
                waitForText("The tune alone", substring = true)
                // The test piece's tune: the 8 notes from 72 up; the left-hand chords go.
                compose.waitUntil(10_000) { shownNoteCount() == TestFixtures.expected.count { it.pitch >= 72 } }
                // Editing waits for the Advanced version.
                compose.onNodeWithContentDescription("Editing off").performClick()
                waitForText("Switch to Advanced to edit the notes")
                // Intermediate: the tune and a simpler left hand (the chords become bass + one note).
                compose.onNodeWithText("Intermediate").performClick()
                compose.waitUntil(10_000) { (shownNoteCount() ?: 0) in 9 until TestFixtures.expected.size }
                for (tab in listOf("Falling Notes", "Sheet", "Analyser", "Piano Roll")) {
                    compose.onNode(hasText(tab) and SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab)).performScrollTo().performClick()
                    compose.waitForIdle()
                }
                // Advanced again: every note is back, nothing was lost.
                compose.onNodeWithText("Advanced").performClick()
                compose.waitUntil(10_000) { shownNoteCount() == all }
            }
        } finally {
            runBlocking { container.repository.delete(id) }
        }
    }

    @Test fun makesASongFromTheMelody() {
        val name = "Song maker ${System.currentTimeMillis() % 100000}"
        val id = seedProject(name)
        try {
            ActivityScenario.launch(MainActivity::class.java).use {
                waitForText(name)
                compose.onNodeWithText(name).performClick()
                waitForText("Piano Roll")
                compose.onNodeWithContentDescription("More").performClick()
                compose.onNodeWithText("Make a song (chords, bass, drums)…").performClick()
                waitForText("Make song")
                compose.onNodeWithText("Make song").performClick()
                waitForText("Chords: ", timeoutMs = 120_000, substring = true)
                waitForText("Stop") // it plays straight away
                compose.onNodeWithText("Stop").performClick()
                waitForText("Play")
                compose.onNodeWithText("Close").performClick()
            }
        } finally {
            runBlocking { container.repository.delete(id) }
        }
    }

    @Test fun everyResultTabOpens() {
        val name = "UI smoke song ${System.currentTimeMillis() % 100000}"
        val id = seedProject(name)
        try {
            ActivityScenario.launch(MainActivity::class.java).use {
                waitForText(name)
                compose.onNodeWithText(name).performClick()
                waitForText("Piano Roll")
                for (tab in listOf("Falling Notes", "Sheet", "Guitar Tab", "Notes", "Analyser", "Compare", "Piano Roll")) {
                    // "Notes" is also the play-mode button, so match the tab itself.
                    compose.onNode(hasText(tab) and SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab)).performScrollTo().performClick()
                    compose.waitForIdle()
                }
                // The engraved score: drawn, with its editing hint.
                compose.onNode(hasText("Sheet") and SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab)).performScrollTo().performClick()
                waitForText("Tap a note to select it")
                compose.onNode(hasText("Piano Roll") and SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab)).performScrollTo().performClick()
                // Instrument picker.
                compose.onNodeWithText("Piano").performClick()
                waitForText("Flute  · melody")
                compose.onNodeWithText("Flute  · melody").performClick()
                waitForText("Flute (built-in)")
                // MIDI dialog, then close it.
                compose.onNodeWithText(" MIDI").performClick()
                waitForText("Performed version")
                compose.onNodeWithText("Close").performClick()
                // Overflow menu dialogs.
                compose.onNodeWithContentDescription("More").performClick()
                compose.onNodeWithText("Transpose…").performClick()
                waitForText("Apply")
                compose.onNodeWithText("Cancel").performClick()
            }
        } finally {
            runBlocking { container.repository.delete(id) }
            container.settings.update { it.copy(instrument = com.pianoscribe.core.perform.Instrument.PIANO) }
        }
    }
}
