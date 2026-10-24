package com.pianoscribe.app

import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.pianoscribe.app.audio.AudioDecoder
import com.pianoscribe.app.audio.Draft
import com.pianoscribe.app.ui.studio.StudioScreen
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** The audio studio screen: check, clean, separate, undo and finish without crashing. */
@RunWith(AndroidJUnit4::class)
class StudioUiTest {
    @get:Rule val compose = createComposeRule()

    private val container get() = (TestFixtures.app.applicationContext as PianoScribeApp).container

    private fun waitForText(text: String, substring: Boolean = false, timeoutMs: Long = 60_000) {
        try {
            compose.waitUntil(timeoutMs) { compose.onAllNodesWithText(text, substring = substring).fetchSemanticsNodes().isNotEmpty() }
        } catch (e: androidx.compose.ui.test.ComposeTimeoutException) {
            // Say what was on screen instead, so a failure explains itself.
            val shown = compose.onAllNodes(androidx.compose.ui.test.isRoot()).fetchSemanticsNodes().flatMap { root ->
                fun texts(n: androidx.compose.ui.semantics.SemanticsNode): List<String> =
                    n.config.getOrElseNullable(androidx.compose.ui.semantics.SemanticsProperties.Text) { null }.orEmpty().map { t -> t.text } +
                        n.children.flatMap { c -> texts(c) }
                texts(root)
            }
            throw AssertionError("Waited for \"$text\"; the screen shows: ${shown.joinToString(" | ")}", e)
        }
    }

    private fun button(text: String) = compose.onNode(hasText(text) and hasClickAction())

    @Test fun studioScreenWorks() {
        val ctx = TestFixtures.app
        val mono = File(ctx.cacheDir, "ui_studio.f32")
        val l = File(ctx.cacheDir, "ui_studio_l.f32")
        val r = File(ctx.cacheDir, "ui_studio_r.f32")
        val res = runBlocking { AudioDecoder(ctx).decode(TestFixtures.wavUri(), mono, l to r) {} }
        container.draft.value = Draft(mono, "UI studio", "Test", res.samples, left = l, right = r)
        var done = false
        compose.setContent { StudioScreen(container, onDone = { done = true }) }

        waitForText("Check the recording")
        button("Check").performScrollTo().performClick()
        waitForText("Background noise", substring = true)
        waitForText("No mains hum")

        // Separate: drums removed (fast, no model).
        compose.onNodeWithText("  Remove drums").performScrollTo().performClick()
        button("Separate").performScrollTo().performClick()
        waitForText("Done:", substring = true)
        compose.onNodeWithText("1. Remove drums").performScrollTo()

        // Undo brings the original back, and stereo options are available again.
        compose.onNodeWithContentDescription("Undo").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("  Remove vocals (stereo centre)").performScrollTo().performClick()
        button("Separate").performScrollTo().performClick()
        // The earlier "Done:" message is still showing, so wait for the history entry instead.
        waitForText("1. Remove vocals", substring = true)

        // Edit the whole recording.
        compose.onNodeWithText("Normalise").performScrollTo().performClick()
        waitForText("2. Normalise", substring = true)

        button("Use this audio").performScrollTo().performClick()
        compose.waitForIdle()
        assertTrue(done)
        assertTrue("draft now points at the edited version", container.draft.value!!.file != mono)
        container.draft.value = null
    }
}
