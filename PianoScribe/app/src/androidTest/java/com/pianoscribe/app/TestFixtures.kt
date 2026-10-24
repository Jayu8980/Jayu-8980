package com.pianoscribe.app

import android.content.Context
import android.net.Uri
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File

/** Test recording bundled with the test APK: a piano melody over two chords, stereo 44.1 kHz WAV. */
object TestFixtures {
    data class Expected(val pitch: Int, val start: Double, val duration: Double)

    val app: Context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val testAssets get() = InstrumentationRegistry.getInstrumentation().context.assets

    fun wavUri(): Uri {
        val f = File(app.cacheDir, "test_melody.wav")
        testAssets.open("test_melody.wav").use { input -> f.outputStream().use { input.copyTo(it) } }
        return Uri.fromFile(f)
    }

    val expected: List<Expected> by lazy {
        testAssets.open("test_melody_notes.txt").bufferedReader().readLines().filter { it.isNotBlank() }.map {
            val p = it.split(" ")
            Expected(p[0].toInt(), p[1].toDouble(), p[2].toDouble())
        }
    }

    const val DURATION_SEC = 4.8
}
