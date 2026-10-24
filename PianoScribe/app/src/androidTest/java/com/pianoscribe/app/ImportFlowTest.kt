package com.pianoscribe.app

import android.content.Intent
import android.net.Uri
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.core.content.FileProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
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
        compose.waitUntil(timeoutMs) { compose.onAllNodesWithText(text, substring = substring).fetchSemanticsNodes().isNotEmpty() }

    @Test fun openConvertAndShowEveryFormat() {
        val app = TestFixtures.app
        for (asset in listOf("test_melody.mp3", "test_melody_48k.mp3", "test_melody.m4a", "test_melody.wav")) {
            val intent = Intent(Intent.ACTION_VIEW).setDataAndType(sharedAudio(asset), mimeOf(asset))
                .setClass(app, MainActivity::class.java).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            ActivityScenario.launch<MainActivity>(intent).use {
                waitForText("Trim the start and end", 60_000, substring = true)
                compose.onNode(hasText("Convert to piano notes") and hasClickAction()).performScrollTo().performClick()
                waitForText("Piano Roll", 180_000)
                android.util.Log.i("PianoScribeTest", "Imported and converted $asset")
            }
        }
        // A 3-minute song must open (decoding runs off the main thread) and convert.
        val long = Intent(Intent.ACTION_VIEW).setDataAndType(sharedLongSong(), "audio/wav")
            .setClass(app, MainActivity::class.java).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        ActivityScenario.launch<MainActivity>(long).use {
            waitForText("Trim the start and end", 120_000, substring = true)
            compose.onNode(hasText("Convert to piano notes") and hasClickAction()).performScrollTo().performClick()
            waitForText("Piano Roll", 600_000)
        }
        // Remove the projects this test made.
        runBlocking {
            val repo = (app.applicationContext as PianoScribeApp).container.repository
            repo.projects.first().filter { it.name.startsWith("test_melody") || it.name.startsWith("long_song") }.forEach { repo.delete(it.id) }
        }
    }
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
