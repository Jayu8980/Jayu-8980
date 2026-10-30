@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package com.pianoscribe.app

import android.content.Intent
import android.net.Uri
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performScrollTo
import androidx.core.content.FileProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Copies a test asset to the shareable folder and returns a content:// URI for it, like a file manager would give. */
fun sharedAudio(asset: String): Uri {
    val app = TestFixtures.app
    val dir = File(app.cacheDir, "exports").apply { mkdirs() }
    val f = File(dir, asset)
    InstrumentationRegistry.getInstrumentation().context.assets.open(asset).use { i -> f.outputStream().use { i.copyTo(it) } }
    return FileProvider.getUriForFile(app, app.packageName + ".files", f)
}

/** A 3-minute stereo WAV (the test melody repeated), shared like [sharedAudio]: long enough that slow work shows. */
fun sharedLongSong(): Uri {
    val app = TestFixtures.app
    val src = InstrumentationRegistry.getInstrumentation().context.assets.open("test_melody.wav").use { it.readBytes() }
    val pcm = src.copyOfRange(44, src.size)
    val repeats = 38
    val f = File(File(app.cacheDir, "exports").apply { mkdirs() }, "long_song.wav")
    f.outputStream().buffered().use { o ->
        val dataBytes = pcm.size * repeats
        fun i32(v: Int) = byteArrayOf(v.toByte(), (v shr 8).toByte(), (v shr 16).toByte(), (v shr 24).toByte())
        o.write(src.copyOfRange(0, 4)); o.write(i32(36 + dataBytes)); o.write(src.copyOfRange(8, 40)); o.write(i32(dataBytes))
        repeat(repeats) { o.write(pcm) }
    }
    return FileProvider.getUriForFile(app, app.packageName + ".files", f)
}

fun mimeOf(asset: String) = when (asset.substringAfterLast('.')) { "mp3" -> "audio/mpeg"; "m4a" -> "audio/mp4"; else -> "audio/wav" }

/** The whole user flow: open an audio file with the app, reach the trim screen, convert, see the result. */
@RunWith(AndroidJUnit4::class)
class ImportFlowTest {
    @get:Rule val compose = createEmptyComposeRule()

    private fun waitForText(text: String, timeoutMs: Long, substring: Boolean = false) =
        compose.waitUntilAtLeastOneExists(hasText(text, substring = substring), timeoutMs)

    /**
     * Waits for the conversion by watching the database (a project made since [since] whose audio
     * is saved, the last step) instead of polling the Compose test rule for minutes: polling the
     * rule while a conversion updates the screen ran into its multithreaded measure/draw checks
     * on loaded emulators. The app itself never lays out or draws off the main thread.
     */
    private fun waitForProject(since: Long, timeoutMs: Long) {
        val repo = (TestFixtures.app.applicationContext as PianoScribeApp).container.repository
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (runBlocking { repo.projects.first() }.any { it.createdAt >= since && it.audioPath.isNotEmpty() }) return
            Thread.sleep(1000)
        }
        throw AssertionError("conversion did not finish within ${timeoutMs / 1000} s; on screen: ${screenText()}; app log: ${appLog()}")
    }

    private fun openAndConvert(uri: Uri, mime: String, openTimeoutMs: Long, convertTimeoutMs: Long) {
        val app = TestFixtures.app
        val intent = Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime)
            .setClass(app, MainActivity::class.java).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        ActivityScenario.launch<MainActivity>(intent).use {
            waitForText("Trim the start and end", openTimeoutMs, substring = true)
            val since = System.currentTimeMillis()
            // Press the button through its click action: an injected touch at its centre can land on
            // whatever covers that spot once the long trim screen is scrolled (the release check taps for real).
            compose.onNode(hasText("Convert to piano notes") and hasClickAction()).performScrollTo().performSemanticsAction(SemanticsActions.OnClick)
            waitForProject(since, convertTimeoutMs)
            waitForText("Piano Roll", 60_000)
        }
        android.util.Log.i("PianoScribeTest", "Imported and converted $uri")
    }

    /** The app's own recent log lines (this process's), for failure messages. */
    private fun appLog(): String = runCatching {
        val p = ProcessBuilder("logcat", "-d", "-v", "brief", "-s", "PianoScribe:*", "AndroidRuntime:*").redirectErrorStream(true).start()
        p.inputStream.bufferedReader().readLines().takeLast(30).joinToString(" / ")
    }.getOrElse { "(unreadable: $it)" }

    /** Every text on screen, read through accessibility (not the Compose test rule), for failure messages. */
    private fun screenText(): String = runCatching {
        val out = ArrayList<String>()
        fun walk(n: android.view.accessibility.AccessibilityNodeInfo?) {
            if (n == null) return
            n.text?.toString()?.takeIf { it.isNotBlank() }?.let { out += it }
            for (i in 0 until n.childCount) walk(n.getChild(i))
        }
        walk(InstrumentationRegistry.getInstrumentation().uiAutomation.rootInActiveWindow)
        out.joinToString(" | ")
    }.getOrElse { "(unreadable: $it)" }

    @After fun removeTestProjects() = runBlocking {
        val repo = (TestFixtures.app.applicationContext as PianoScribeApp).container.repository
        repo.projects.first().filter { it.name.startsWith("test_melody") || it.name.startsWith("long_song") }.forEach { repo.delete(it.id) }
    }

    @Test fun mp3() = openAndConvert(sharedAudio("test_melody.mp3"), "audio/mpeg", 60_000, 180_000)
    @Test fun mp3At48k() = openAndConvert(sharedAudio("test_melody_48k.mp3"), "audio/mpeg", 60_000, 180_000)
    @Test fun m4a() = openAndConvert(sharedAudio("test_melody.m4a"), "audio/mp4", 60_000, 180_000)
    @Test fun wav() = openAndConvert(sharedAudio("test_melody.wav"), "audio/wav", 60_000, 180_000)

    /** A MIDI file opens straight into a project with its notes (no listening needed). */
    @Test fun midiFile() {
        val app = TestFixtures.app
        val notes = TestFixtures.expected.mapIndexed { i, e ->
            com.pianoscribe.core.model.Note(i + 1L, e.pitch, e.start, e.duration, 90, 1f,
                if (e.pitch >= 60) com.pianoscribe.core.model.Hand.RIGHT else com.pianoscribe.core.model.Hand.LEFT)
        }
        val f = File(File(app.cacheDir, "exports").apply { mkdirs() }, "test_melody_midi.mid")
        f.writeBytes(com.pianoscribe.core.midi.MidiWriter.write(notes, com.pianoscribe.core.model.TimeGrid(bpm = 120.0), "test_melody MIDI"))
        val uri = FileProvider.getUriForFile(app, app.packageName + ".files", f)
        val intent = Intent(Intent.ACTION_VIEW).setDataAndType(uri, "audio/midi")
            .setClass(app, MainActivity::class.java).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        ActivityScenario.launch<MainActivity>(intent).use {
            waitForText("Piano Roll", 60_000)
        }
        val repo = (app.applicationContext as PianoScribeApp).container.repository
        val project = runBlocking { repo.projects.first() }.first { it.name == "test_melody MIDI" }
        org.junit.Assert.assertEquals("MIDI file", project.source)
        org.junit.Assert.assertEquals(notes.size, runBlocking { repo.load(project.id)!!.notes.size })
    }

    /** A 3-minute song must open (decoding runs off the main thread) and convert. */
    @Test fun threeMinuteSong() = openAndConvert(sharedLongSong(), "audio/wav", 600_000, 900_000)
}

/**
 * Opens a test file in the installed release build (CI runs it with `-e release true`), so the
 * minified APK goes through the same import as on a phone. Skipped in normal test runs.
 */
@RunWith(AndroidJUnit4::class)
class ReleaseImportTest {
    @Test fun openInReleaseApp() {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("release") == "true")
        val asset = args.getString("asset") ?: "test_melody.mp3"
        val uri = sharedAudio(asset)
        val intent = Intent(Intent.ACTION_VIEW).setDataAndType(uri, mimeOf(asset))
            .setClassName("com.pianoscribe.app", "com.pianoscribe.app.MainActivity")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        TestFixtures.app.startActivity(intent)
        // This process serves the file: stay alive while the release app reads and converts it
        // (the CI script stops it when done).
        Thread.sleep((args.getString("holdSec") ?: "300").toLong() * 1000)
    }
}
