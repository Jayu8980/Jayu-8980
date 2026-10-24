package com.pianoscribe.core.synth

import com.pianoscribe.core.model.Note
import com.pianoscribe.core.theory.Pitch
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Lightweight polyphonic piano synth. Plays an SF2 preset when one is loaded, otherwise falls
 * back to a small additive piano model, so playback always works. Output is mono float.
 */
class PianoSynth(private val preset: Sf2Preset?, val sampleRate: Int, private val maxVoices: Int = 64) {
    private val voices = ArrayList<Voice>()
    var masterGain = 0.5f

    val usesSoundFont: Boolean get() = preset != null

    fun noteOn(key: Int, velocity: Int) {
        val vel = velocity.coerceIn(1, 127)
        // Retrigger: release a voice already sounding this key.
        voices.filter { it.key == key && !it.releasing }.forEach { it.release() }
        val newVoices: List<Voice> = if (preset != null) {
            preset.regionsFor(key, vel).map { SampleVoice(it, key, vel, sampleRate) }
        } else listOf(AdditiveVoice(key, vel, sampleRate))
        voices += newVoices
        while (voices.size > maxVoices) {
            val victim = voices.minByOrNull { if (it.releasing) 0.0 else 1.0 + it.age } ?: break
            voices.remove(victim)
        }
    }

    fun noteOff(key: Int) {
        voices.filter { it.key == key && !it.releasing }.forEach { it.release() }
    }

    fun allNotesOff(immediate: Boolean = false) {
        if (immediate) voices.clear() else voices.forEach { it.release() }
    }

    val activeVoices: Int get() = voices.size

    /** Mixes [len] frames into out[off..]. */
    fun render(out: FloatArray, off: Int, len: Int) {
        val it = voices.iterator()
        while (it.hasNext()) {
            val v = it.next()
            if (!v.render(out, off, len, masterGain)) it.remove()
        }
    }

    abstract class Voice(val key: Int) {
        var releasing = false
            protected set
        var age = 0.0
            protected set
        open fun release() { releasing = true }
        /** Adds samples; returns false when the voice has finished. */
        abstract fun render(out: FloatArray, off: Int, len: Int, gain: Float): Boolean
    }

    private class SampleVoice(private val r: Sf2Region, key: Int, vel: Int, private val outRate: Int) : Voice(key) {
        private val step = 2.0.pow(((key - r.rootKey) * 100.0 + r.tuneCents) / 1200.0) * r.sampleRate / outRate
        private var pos = 0.0
        private val amp = (vel / 127.0).pow(1.6) * 10.0.pow(-r.attenuationCb / 200.0)
        private val sustain = 10.0.pow(-r.sustainCb / 200.0)
        private var env = 0.0
        private var stage = 0 // 0 attack, 1 hold, 2 decay, 3 sustain, 4 release
        private var stageTime = 0.0
        private val dt = 1.0 / outRate
        private var releaseFactor = 1.0
        private val looping get() = (r.loopMode == 1 || (r.loopMode == 3 && !releasing)) && r.loopEnd > r.loopStart + 4

        override fun release() {
            if (releasing) return
            super.release()
            stage = 4
            releaseFactor = exp(-dt * 6.9 / r.release.coerceAtLeast(0.01))
        }

        override fun render(out: FloatArray, off: Int, len: Int, gain: Float): Boolean {
            val s = r.sample
            val decayFactor = exp(-dt * 6.9 / r.decay.coerceAtLeast(0.001))
            for (i in 0 until len) {
                when (stage) {
                    0 -> { env = if (r.attack <= dt) 1.0 else env + dt / r.attack; if (env >= 1.0) { env = 1.0; stage = 1; stageTime = 0.0 } }
                    1 -> { stageTime += dt; if (stageTime >= r.hold) stage = 2 }
                    2 -> { env = sustain + (env - sustain) * decayFactor; if (env - sustain < 1e-4) stage = 3 }
                    3 -> {}
                    4 -> { env *= releaseFactor; if (env < 1e-4) return false }
                }
                val ip = pos.toInt()
                if (ip + 1 >= s.size) return false
                val f = pos - ip
                val v = s[ip] + (s[ip + 1] - s[ip]) * f
                out[off + i] += (v / 32768.0 * env * amp * gain).toFloat()
                pos += step
                if (looping && pos >= r.loopEnd) pos -= (r.loopEnd - r.loopStart)
            }
            age += len * dt
            return true
        }
    }

    /** Additive fallback: inharmonic partials with per-partial decay plus a short hammer thump. */
    private class AdditiveVoice(key: Int, vel: Int, private val outRate: Int) : Voice(key) {
        private val f0 = Pitch.frequency(key)
        private val nPartials = minOf(10, (outRate / 2.2 / f0).toInt()).coerceAtLeast(1)
        private val freqs = DoubleArray(nPartials)
        private val amps = DoubleArray(nPartials)
        private val decays = DoubleArray(nPartials)
        private val phases = DoubleArray(nPartials)
        private var t = 0.0
        private var rel = 1.0
        private val dt = 1.0 / outRate
        private val relFactor = exp(-dt * 6.9 / 0.25)
        private val velAmp = (vel / 127.0).pow(1.5)
        private var level = 1.0

        init {
            val b = 0.0004 * (f0 / 261.6).pow(0.5)
            val baseDecay = 0.8 + 6.0 * (110.0 / f0).coerceAtMost(4.0)
            for (n in 1..nPartials) {
                freqs[n - 1] = 2 * PI * f0 * n * sqrt(1 + b * n * n)
                amps[n - 1] = (1.0 / n.toDouble().pow(1.1 - 0.4 * vel / 127.0)) * 0.35
                decays[n - 1] = exp(-dt / (baseDecay / (1 + 0.6 * (n - 1))))
            }
        }

        override fun render(out: FloatArray, off: Int, len: Int, gain: Float): Boolean {
            for (i in 0 until len) {
                var s = 0.0
                for (k in 0 until nPartials) {
                    phases[k] += freqs[k] * dt
                    s += amps[k] * sin(phases[k])
                    amps[k] *= decays[k]
                }
                if (t < 0.006) s *= t / 0.006
                if (releasing) { rel *= relFactor; if (rel < 1e-4) return false }
                level = rel
                out[off + i] += (s * velAmp * level * gain).toFloat()
                t += dt
            }
            age = t
            if (amps[0] < 1e-5) return false
            return true
        }
    }
}

/**
 * Turns a note list into synth events while rendering, so playback can run at any speed without
 * changing pitch: song time advances by speed * (1 / sampleRate) per output frame.
 */
class NoteSequencer(private val synth: PianoSynth, notes: List<Note>) {
    private class Ev(val time: Double, val on: Boolean, val key: Int, val vel: Int)

    private val events: List<Ev> = notes.flatMap {
        listOf(Ev(it.start, true, it.pitch, it.velocity), Ev(it.end, false, it.pitch, 0))
    }.sortedWith(compareBy<Ev>({ it.time }, { if (it.on) 1 else 0 }))
    private var next = 0
    var songTime = 0.0
        private set

    fun seek(t: Double) {
        synth.allNotesOff(immediate = true)
        songTime = t
        next = events.indexOfFirst { it.time >= t }.let { if (it < 0) events.size else it }
    }

    /** Renders [len] frames at [speed]; dispatches events with sub-block accuracy of 64 frames. */
    fun render(out: FloatArray, off: Int, len: Int, speed: Double) {
        val block = 64
        var done = 0
        val dtSong = speed / synth.sampleRate
        while (done < len) {
            val n = minOf(block, len - done)
            val blockEnd = songTime + n * dtSong
            while (next < events.size && events[next].time < blockEnd) {
                val e = events[next++]
                if (e.on) synth.noteOn(e.key, e.vel) else synth.noteOff(e.key)
            }
            synth.render(out, off + done, n)
            songTime = blockEnd
            done += n
        }
    }

    val finished: Boolean get() = next >= events.size && synth.activeVoices == 0
}
