@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package com.pianoscribe.app

import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
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

    private fun waitForText(text: String, timeoutMs: Long = 20_000) =
        compose.waitUntilAtLeastOneExists(hasText(text), timeoutMs)

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

    @Test fun everyResultTabOpens() {
        val name = "UI smoke song ${System.currentTimeMillis() % 100000}"
        val id = seedProject(name)
        try {
            ActivityScenario.launch(MainActivity::class.java).use {
                waitForText(name)
                compose.onNodeWithText(name).performClick()
                waitForText("Piano Roll")
                for (tab in listOf("Falling Notes", "Sheet", "Notes", "Analyser", "Compare", "Piano Roll")) {
                    // "Notes" is also the play-mode button, so match the tab itself.
                    compose.onNode(hasText(tab) and SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab)).performScrollTo().performClick()
                    compose.waitForIdle()
                }
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
