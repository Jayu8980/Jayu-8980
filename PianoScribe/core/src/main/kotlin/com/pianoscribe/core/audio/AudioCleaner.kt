package com.pianoscribe.core.audio

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** What to clean. Amounts are 0 (off) to 1 (strongest). */
data class CleanOptions(
    val noiseReduction: Float = 0f,
    val echoReduction: Float = 0f,
    val removeHum: Boolean = false,
    val removeRumble: Boolean = false,
    val normalize: Boolean = false,
    /** Room decay time to remove, overriding the measured one (seconds). */
    val echoTimeSec: Double? = null,
) {
    val isEmpty: Boolean get() = noiseReduction <= 0f && echoReduction <= 0f && !removeHum && !removeRumble && !normalize
}

/** Measurements of a recording, used to suggest clean-up and to explain it to the user. */
class AudioReport(
    val durationSec: Double,
    val rmsDb: Double,
    val peakDb: Double,
    /** Share of samples stuck at full scale (clipped). */
    val clippedShare: Double,
    /** Background noise level in dBFS (whole spectrum). */
    val noiseFloorDb: Double,
    /** Music level over the background noise, in dB. */
    val snrDb: Double,
    /** Mains hum frequency (50 or 60), or 0 when there is none. */
    val humHz: Int,
    /** Harmonic numbers of [humHz] that stand out (1 = the fundamental). */
    val humHarmonics: List<Int>,
    /** Estimated reverberation time (60 dB decay) of the room, or null when it can't be measured. */
    val rt60Sec: Double?,
    /** Background noise power per STFT bin (AudioCleaner.FFT_SIZE), for the noise reducer. */
    internal val noisePsd: DoubleArray,
) {
    val hasNoise: Boolean get() = snrDb < AudioCleaner.NOISY_SNR_DB
    val hasHum: Boolean get() = humHz != 0
    val hasEcho: Boolean get() = (rt60Sec ?: 0.0) >= AudioCleaner.ECHO_RT60_SEC
    val isClipped: Boolean get() = clippedShare > 0.001
    val isQuiet: Boolean get() = peakDb < -12

    /**
     * Clean-up that helps the note model, or nothing for a clean recording. Measured with the
     * model on synthetic recordings: removing hum helps every time; noise reduction and echo
     * reduction do not reliably help (the model copes with steady noise, and echo reduction also
     * removes some of the sustain of held notes), so they are only offered for listening.
     */
    fun suggested(): CleanOptions = CleanOptions(
        removeHum = hasHum,
        removeRumble = hasHum || hasNoise,
        normalize = isQuiet,
    )

    /** Clean-up for listening and saving: also reduces background noise and room echo. */
    fun suggestedForListening(): CleanOptions = suggested().copy(
        noiseReduction = when {
            snrDb < 12 -> 0.8f
            snrDb < 20 -> 0.6f
            hasNoise -> 0.4f
            else -> 0f
        },
        echoReduction = if (hasEcho) 0.5f else 0f,
        normalize = true,
    )

    /** Plain-language findings for the UI. */
    fun findings(): List<String> = buildList {
        add(if (hasNoise) "Background noise: music is about ${snrDb.roundToInt()} dB above it" else "Background noise: low")
        add(if (hasHum) "Mains hum at $humHz Hz" else "No mains hum")
        add(
            when {
                rt60Sec == null -> "Echo: couldn't measure (no clear note endings)"
                hasEcho -> "Room echo: sound takes about ${"%.1f".format(rt60Sec)} s to die away"
                else -> "Room echo: low"
            },
        )
        if (isClipped) add("Clipping: ${"%.1f".format(clippedShare * 100)}% of the audio is distorted (too loud when recorded)")
        if (isQuiet) add("Level: quiet (peak ${peakDb.roundToInt()} dB)")
    }
}

/**
 * Clean-up for recordings before transcription, all on the phone:
 *  - rumble: 4th-order high-pass at 22 Hz (below the lowest piano note, A0 = 27.5 Hz);
 *  - mains hum: narrow notches at each 50/60 Hz harmonic that was found;
 *  - background noise: Wiener gain with decision-directed SNR (Ephraim-Malah) against a noise
 *    spectrum measured from the quietest moments of each frequency;
 *  - room echo: late-reverberation spectral subtraction (Lebart 2001) using a blind estimate of
 *    the room's decay time from how fast notes die away;
 *  - normalise: peak to -1 dBFS.
 */
object AudioCleaner {
    const val FFT_SIZE = 1024
    const val HOP = 256
    const val NOISY_SNR_DB = 30.0
    const val ECHO_RT60_SEC = 0.55
    /** Room decay time assumed when echo reduction is asked for but none could be measured. */
    const val DEFAULT_RT60_SEC = 0.8

    private const val RUMBLE_HZ = 22.0
    private const val NOISE_PERCENTILE = 0.1
    /** Converts the 10th percentile of smoothed noise power to its mean (measured on white noise). */
    private const val NOISE_BIAS = 2.2
    private const val MAX_NOISE_FRAMES = 1500
    private const val HUM_FFT = 8192
    private const val HUM_PEAK_DB = 8.0
    /** Late reverberation starts this long after the direct sound. */
    private const val LATE_DELAY_SEC = 0.05
    private const val RT60_CALIBRATION = 1.2
    private const val RT60_PERCENTILE = 0.75

    fun analyze(x: FloatArray, sampleRate: Int): AudioReport {
        val n = x.size
        var sum = 0.0
        var peak = 0f
        for (v in x) { sum += v.toDouble() * v; if (abs(v) > peak) peak = abs(v) }
        val rms = sqrt(sum / n.coerceAtLeast(1))
        val clipped = clippedShare(x, peak)

        // Spectral statistics in one STFT pass.
        val stft = Stft(FFT_SIZE, HOP)
        val frames = stft.frameCount(n)
        val step = maxOf(1, (frames + MAX_NOISE_FRAMES - 1) / MAX_NOISE_FRAMES)
        val kept = (frames + step - 1) / step
        val samples = Array(stft.bins) { FloatArray(kept) }
        val smooth = DoubleArray(stft.bins)
        val frameTotal = DoubleArray(frames)
        val bands = bandEdges(sampleRate)
        val bandDb = Array(bands.size - 1) { FloatArray(frames) }
        stft.analyze(x) { k, p ->
            var total = 0.0
            for (j in 0 until stft.bins) {
                val local = (p[maxOf(0, j - 1)] + p[j] + p[minOf(stft.bins - 1, j + 1)]) / 3
                smooth[j] = if (k == 0) local else 0.5 * smooth[j] + 0.5 * local
                total += p[j]
            }
            frameTotal[k] = total
            if (k % step == 0) for (j in 0 until stft.bins) samples[j][k / step] = smooth[j].toFloat()
            for (b in 0 until bands.size - 1) {
                var e = 0.0
                for (j in bands[b] until bands[b + 1]) e += p[j]
                bandDb[b][k] = (10 * log10(e + 1e-12)).toFloat()
            }
        }
        val noise = DoubleArray(stft.bins) { j -> percentile(samples[j], NOISE_PERCENTILE) * NOISE_BIAS }
        val noiseTotal = noise.sum()
        // Music level: mean power of the frames clearly above the noise.
        var active = 0.0
        var activeCount = 0
        for (t in frameTotal) if (t > noiseTotal * 4) { active += t; activeCount++ }
        val snr = if (activeCount == 0) 0.0 else 10 * log10(maxOf(active / activeCount - noiseTotal, 1e-12) / maxOf(noiseTotal, 1e-20))
        // Power of a full-scale sine in this STFT, to express the noise in dBFS.
        val fullScale = (FFT_SIZE / 2.0).pow(2) / 2
        val noiseDb = 10 * log10(maxOf(noiseTotal, 1e-20) / fullScale)

        val (humHz, harmonics) = detectHum(x, sampleRate)
        val bandNoiseDb = DoubleArray(bands.size - 1) { b -> 10 * log10((bands[b] until bands[b + 1]).sumOf { noise[it] } + 1e-12) }
        val rt60 = estimateRt60(bandDb, bandNoiseDb, sampleRate.toDouble() / HOP)
        return AudioReport(
            durationSec = n.toDouble() / sampleRate,
            rmsDb = 20 * log10(rms + 1e-12),
            peakDb = 20 * log10(peak + 1e-12),
            clippedShare = clipped,
            noiseFloorDb = noiseDb,
            snrDb = snr,
            humHz = humHz,
            humHarmonics = harmonics,
            rt60Sec = rt60,
            noisePsd = noise,
        )
    }

    /**
     * Returns a cleaned copy of [x]. [report] is the analysis of [x]; it is measured again after
     * the hum and rumble filters so the noise reducer sees what is left.
     */
    fun clean(x: FloatArray, sampleRate: Int, options: CleanOptions, report: AudioReport = analyze(x, sampleRate), onProgress: (Float) -> Unit = {}): FloatArray {
        val y = x.copyOf()
        var prefiltered = false
        if (options.removeRumble) {
            Biquad.butterworthHighPass4(RUMBLE_HZ, sampleRate).forEach { it.process(y) }
            prefiltered = true
        }
        if (options.removeHum && report.humHz != 0) {
            for (h in report.humHarmonics) {
                val f = h * report.humHz.toDouble()
                if (f < sampleRate / 2.0 - 100) Biquad.notch(f, 3.0, sampleRate).process(y)
            }
            prefiltered = true
        }
        onProgress(0.1f)
        var out = y
        if (options.noiseReduction > 0f || options.echoReduction > 0f) {
            val r = if (prefiltered && options.noiseReduction > 0f) analyze(y, sampleRate) else report
            onProgress(0.25f)
            out = spectralClean(y, sampleRate, options, r.noisePsd, options.echoTimeSec ?: report.rt60Sec ?: DEFAULT_RT60_SEC) { onProgress(0.25f + 0.7f * it) }
        }
        if (options.normalize) {
            val peak = out.maxOfOrNull { abs(it) } ?: 0f
            if (peak > 1e-6f) {
                val g = minOf(10.0.pow(-1.0 / 20) / peak, 10.0.pow(24.0 / 20)).toFloat()
                for (i in out.indices) out[i] *= g
            }
        }
        onProgress(1f)
        return out
    }

    private fun spectralClean(
        x: FloatArray, sampleRate: Int, options: CleanOptions, noisePsd: DoubleArray, rt60: Double, onProgress: (Float) -> Unit,
    ): FloatArray {
        val stft = Stft(FFT_SIZE, HOP)
        val bins = stft.bins
        val nr = options.noiseReduction.coerceIn(0f, 1f).toDouble()
        val er = options.echoReduction.coerceIn(0f, 1f).toDouble()
        val noiseFloor = 10.0.pow(-24 * nr / 20)
        val echoFloor = 10.0.pow(-20 * er / 20)
        // Over-subtraction: the tail's power fluctuates, so subtract up to twice the estimate.
        val echoScale = 2 * er
        // Decision-directed a priori SNR state.
        val prevClean = DoubleArray(bins)
        // Late reverberation: power that was present delay frames ago, decayed by the room.
        val delay = maxOf(1, (LATE_DELAY_SEC * sampleRate / HOP).roundToInt())
        val decay = exp(-2 * (3 * ln(10.0) / rt60) * delay * HOP / sampleRate)
        val history = Array(delay) { DoubleArray(bins) }
        val smoothed = DoubleArray(bins)
        return stft.process(x, onProgress) { k, re, im ->
            val past = history[k % delay]
            for (j in 0 until bins) {
                val p = re[j] * re[j] + im[j] * im[j]
                var g = 1.0
                if (nr > 0) {
                    val nk = maxOf(noisePsd[j], 1e-20)
                    val post = p / nk
                    val prio = 0.98 * prevClean[j] / nk + 0.02 * maxOf(post - 1, 0.0)
                    g = maxOf(prio / (1 + prio), noiseFloor)
                }
                if (er > 0) {
                    smoothed[j] = if (k == 0) p else 0.6 * smoothed[j] + 0.4 * p
                    val late = if (k >= delay) decay * past[j] else 0.0
                    val ratio = if (smoothed[j] > 1e-20) late / smoothed[j] else 0.0
                    g *= sqrt(maxOf(1 - echoScale * ratio, echoFloor * echoFloor))
                    past[j] = smoothed[j]
                }
                prevClean[j] = g * g * p
                re[j] *= g
                im[j] *= g
            }
        }
    }

    /** Share of samples in runs of 3+ at (nearly) the peak level, when the peak is near full scale. */
    private fun clippedShare(x: FloatArray, peak: Float): Double {
        if (peak < 0.89f) return 0.0
        val level = peak * 0.995f
        var clipped = 0L
        var run = 0
        for (v in x) {
            if (abs(v) >= level) run++ else { if (run >= 3) clipped += run; run = 0 }
        }
        if (run >= 3) clipped += run
        return clipped.toDouble() / x.size.coerceAtLeast(1)
    }

    /** Bin edges of ten bands from 150 Hz to 6 kHz, for the decay-time estimate. */
    private fun bandEdges(sampleRate: Int): IntArray {
        val lo = 150.0
        val hi = minOf(6000.0, sampleRate / 2.0 * 0.9)
        return IntArray(11) { i -> (lo * (hi / lo).pow(i / 10.0) * FFT_SIZE / sampleRate).roundToInt().coerceAtLeast(1) }
            .let { e -> for (i in 1 until e.size) if (e[i] <= e[i - 1]) e[i] = e[i - 1] + 1; e }
    }

    /**
     * Blind decay-time estimate: in each band, find free decays (a peak followed by a fall of at
     * least 20 dB with no new note) and fit their slope from -10 dB onwards. A sound can't die
     * away faster than the room lets it, so the fastest decays measure the room.
     */
    internal fun estimateRt60(bandDb: Array<FloatArray>, noiseDb: DoubleArray, frameRate: Double): Double? {
        val slopes = ArrayList<Double>()
        for (b in bandDb.indices) {
            val raw = bandDb[b]
            val n = raw.size
            if (n < 8) continue
            // 5-frame smoothing so the random dips of a reverberant tail don't look like fast decays.
            val e = DoubleArray(n) { t -> ((t - 2)..(t + 2)).sumOf { raw[it.coerceIn(0, n - 1)].toDouble() } / 5.0 }
            val floor = noiseDb[b] + 6
            var t = 1
            while (t < n - 1) {
                if (!(e[t] >= e[t - 1] && e[t] > e[t + 1] && e[t] - floor > 25)) { t++; continue }
                var minV = e[t]
                var j = t + 1
                while (j < n && e[j] < minV + 1.5 && e[j] > floor) { if (e[j] < minV) minV = e[j]; j++ }
                val drop = e[t] - minV
                if (drop >= 20) {
                    // Least squares over the part from 10 dB down to (at most) 40 dB down.
                    val top = e[t] - 10
                    val bottom = e[t] - minOf(drop, 40.0)
                    var sx = 0.0; var sy = 0.0; var sxx = 0.0; var sxy = 0.0; var c = 0
                    for (i in t until j) if (e[i] <= top && e[i] >= bottom) {
                        val xi = i / frameRate
                        sx += xi; sy += e[i]; sxx += xi * xi; sxy += xi * e[i]; c++
                    }
                    if (c >= 4) {
                        val slope = (c * sxy - sx * sy) / (c * sxx - sx * sx)
                        if (slope < 0) slopes += -slope
                    }
                }
                t = maxOf(j, t + 1)
            }
        }
        if (slopes.size < 5) return null
        slopes.sort()
        val fast = slopes[((slopes.size - 1) * RT60_PERCENTILE).toInt()]
        // The fastest decays still include some of the source's own decay; calibrated on
        // synthetic rooms of 0.5-1.5 s, the raw figure is about 0.85x the room's.
        return 60 / fast * RT60_CALIBRATION
    }

    /**
     * Mains hum: narrow peaks at multiples of 50 or 60 Hz that stay all the time, so they stand out
     * in the median spectrum (music comes and goes, hum doesn't).
     */
    private fun detectHum(x: FloatArray, sampleRate: Int): Pair<Int, List<Int>> {
        if (x.size < HUM_FFT * 2) return 0 to emptyList()
        val hop = HUM_FFT / 2
        val total = (x.size - HUM_FFT) / hop + 1
        val step = maxOf(1, total / 200)
        val count = (total + step - 1) / step
        val maxBin = minOf(HUM_FFT / 2, (1100.0 * HUM_FFT / sampleRate).toInt() + 20)
        val spectra = Array(maxBin) { FloatArray(count) }
        for (c in 0 until count) {
            val m = Fft.magnitudeSpectrum(x, c * step * hop, HUM_FFT, HUM_FFT)
            for (j in 0 until maxBin) spectra[j][c] = (m[j] * m[j]).toFloat()
        }
        val median = DoubleArray(maxBin) { percentile(spectra[it], 0.5) }
        val binHz = sampleRate.toDouble() / HUM_FFT
        var best = 0 to emptyList<Int>()
        var bestScore = 0.0
        for (f0 in listOf(50, 60)) {
            val found = ArrayList<Int>()
            var score = 0.0
            var h = 1
            while (h * f0 < 1000) {
                val b = (h * f0 / binHz).roundToInt()
                if (b + 14 >= maxBin) break
                val p = maxOf(median[b - 1], median[b], median[b + 1])
                val around = ((b - 14)..(b - 5)).map { median[it] } + ((b + 5)..(b + 14)).map { median[it] }
                val ref = around.sorted()[around.size / 2]
                val db = 10 * log10((p + 1e-20) / (ref + 1e-20))
                if (db > HUM_PEAK_DB) { found += h; score += db }
                h++
            }
            // Hum is always strongest in its first few harmonics; piano overtones are not.
            if (found.count { it <= 6 } >= 3 && score > bestScore) { bestScore = score; best = f0 to found }
        }
        return best
    }

    private fun percentile(v: FloatArray, q: Double): Double {
        if (v.isEmpty()) return 0.0
        val s = v.copyOf()
        s.sort()
        return s[((s.size - 1) * q).roundToInt()].toDouble()
    }
}
