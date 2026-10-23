package com.pianoscribe.core.synth

import java.io.IOException
import kotlin.math.pow

/** Random-access byte source for SoundFont files (an asset, a file, or a byte array). */
interface ByteSource {
    val size: Long
    fun read(pos: Long, dst: ByteArray, off: Int, len: Int)
}

class ArrayByteSource(private val bytes: ByteArray) : ByteSource {
    override val size: Long get() = bytes.size.toLong()
    override fun read(pos: Long, dst: ByteArray, off: Int, len: Int) {
        System.arraycopy(bytes, pos.toInt(), dst, off, len)
    }
}

/** One playable key/velocity region of a preset, with generators already combined. */
class Sf2Region(
    val keyLo: Int, val keyHi: Int, val velLo: Int, val velHi: Int,
    val sample: ShortArray,
    val sampleRate: Int,
    val rootKey: Int,
    /** Total tuning in cents (coarse, fine and the sample's pitch correction). */
    val tuneCents: Double,
    /** Positions relative to [sample]. */
    val loopStart: Int, val loopEnd: Int,
    /** 0 = no loop, 1 = loop, 3 = loop until release then play to end. */
    val loopMode: Int,
    val attenuationCb: Double,
    val attack: Double, val hold: Double, val decay: Double,
    /** Sustain attenuation in centibels (0 = full level). */
    val sustainCb: Double,
    val release: Double,
) {
    fun matches(key: Int, vel: Int) = key in keyLo..keyHi && vel in velLo..velHi
}

class Sf2Preset(val name: String, val bank: Int, val program: Int, val regions: List<Sf2Region>) {
    fun regionsFor(key: Int, vel: Int): List<Sf2Region> {
        val exact = regions.filter { it.matches(key, vel) }
        if (exact.isNotEmpty()) return exact
        // Some fonts leave gaps; use the region whose key range is nearest.
        val near = regions.minByOrNull { r -> if (key < r.keyLo) r.keyLo - key else if (key > r.keyHi) key - r.keyHi else 0 }
        return listOfNotNull(near)
    }
}

/**
 * SoundFont 2 reader. Loads the generator tree for one preset and only the sample data that
 * preset uses, so large General MIDI fonts can be used without loading everything.
 */
object Sf2Reader {
    private const val GEN_START_OFS = 0
    private const val GEN_END_OFS = 1
    private const val GEN_STARTLOOP_OFS = 2
    private const val GEN_ENDLOOP_OFS = 3
    private const val GEN_START_COARSE = 4
    private const val GEN_END_COARSE = 12
    private const val GEN_ATTACK = 34
    private const val GEN_HOLD = 35
    private const val GEN_DECAY = 36
    private const val GEN_SUSTAIN = 37
    private const val GEN_RELEASE = 38
    private const val GEN_INSTRUMENT = 41
    private const val GEN_KEYRANGE = 43
    private const val GEN_VELRANGE = 44
    private const val GEN_STARTLOOP_COARSE = 45
    private const val GEN_ATTENUATION = 48
    private const val GEN_ENDLOOP_COARSE = 50
    private const val GEN_COARSE_TUNE = 51
    private const val GEN_FINE_TUNE = 52
    private const val GEN_SAMPLE_ID = 53
    private const val GEN_SAMPLE_MODES = 54
    private const val GEN_ROOT_KEY = 58

    private class Chunk(val id: String, val pos: Long, val size: Int)

    private class Hdr(val name: String, val start: Long, val end: Long, val loopStart: Long, val loopEnd: Long, val rate: Int, val pitch: Int, val correction: Int)

    data class PresetInfo(val name: String, val bank: Int, val program: Int)

    fun listPresets(src: ByteSource): List<PresetInfo> {
        val pdta = readPdta(src)
        val phdr = pdta.getValue("phdr")
        return (0 until phdr.size / 38 - 1).map { i ->
            val b = phdr; val o = i * 38
            PresetInfo(str(b, o, 20), u16(b, o + 22), u16(b, o + 20))
        }
    }

    /** Loads bank/program, falling back to the first preset in the file. */
    fun load(src: ByteSource, bank: Int = 0, program: Int = 0): Sf2Preset {
        val (sdtaPos, sdtaSize) = findSmpl(src)
        val pdta = readPdta(src)
        val phdr = pdta.getValue("phdr"); val pbag = pdta.getValue("pbag"); val pgen = pdta.getValue("pgen")
        val inst = pdta.getValue("inst"); val ibag = pdta.getValue("ibag"); val igen = pdta.getValue("igen")
        val shdr = pdta.getValue("shdr")

        val nPresets = phdr.size / 38 - 1
        if (nPresets <= 0) throw IOException("SoundFont has no presets")
        var pi = (0 until nPresets).firstOrNull { u16(phdr, it * 38 + 22) == bank && u16(phdr, it * 38 + 20) == program }
            ?: (0 until nPresets).minBy { u16(phdr, it * 38 + 22) * 1000 + u16(phdr, it * 38 + 20) }
        val presetName = str(phdr, pi * 38, 20)
        val bagStart = u16(phdr, pi * 38 + 24)
        val bagEnd = u16(phdr, (pi + 1) * 38 + 24)

        val headers = (0 until shdr.size / 46).map { i ->
            val o = i * 46
            Hdr(str(shdr, o, 20), u32(shdr, o + 20), u32(shdr, o + 24), u32(shdr, o + 28), u32(shdr, o + 32), u32(shdr, o + 36).toInt(), shdr[o + 40].toInt() and 0xFF, shdr[o + 41].toInt())
        }
        val sampleCache = HashMap<Int, ShortArray>()
        val regions = ArrayList<Sf2Region>()

        fun gens(genBuf: ByteArray, bagBuf: ByteArray, bag: Int): Map<Int, Int> {
            val g0 = u16(bagBuf, bag * 4)
            val g1 = u16(bagBuf, (bag + 1) * 4)
            val m = LinkedHashMap<Int, Int>()
            for (g in g0 until g1) m[u16(genBuf, g * 4)] = u16(genBuf, g * 4 + 2)
            return m
        }

        var presetGlobal: Map<Int, Int> = emptyMap()
        for (pb in bagStart until bagEnd) {
            val pg = gens(pgen, pbag, pb)
            val instIdx = pg[GEN_INSTRUMENT]
            if (instIdx == null) { if (pb == bagStart) presetGlobal = pg; continue }
            val p = presetGlobal + pg
            val ib0 = u16(inst, instIdx * 22 + 20)
            val ib1 = u16(inst, (instIdx + 1) * 22 + 20)
            var instGlobal: Map<Int, Int> = emptyMap()
            for (ib in ib0 until ib1) {
                val ig = gens(igen, ibag, ib)
                val sid = ig[GEN_SAMPLE_ID]
                if (sid == null) { if (ib == ib0) instGlobal = ig; continue }
                val z = instGlobal + ig
                val h = headers.getOrNull(sid) ?: continue
                val (pkLo, pkHi) = range(p[GEN_KEYRANGE])
                val (ikLo, ikHi) = range(z[GEN_KEYRANGE])
                val (pvLo, pvHi) = range(p[GEN_VELRANGE])
                val (ivLo, ivHi) = range(z[GEN_VELRANGE])
                val kLo = maxOf(pkLo, ikLo); val kHi = minOf(pkHi, ikHi)
                val vLo = maxOf(pvLo, ivLo); val vHi = minOf(pvHi, ivHi)
                if (kLo > kHi || vLo > vHi) continue

                fun s(gen: Int) = (z[gen]?.toShort()?.toInt() ?: 0)
                fun ps(gen: Int) = (p[gen]?.toShort()?.toInt() ?: 0)
                val start = h.start + s(GEN_START_OFS) + 32768L * s(GEN_START_COARSE)
                val end = h.end + s(GEN_END_OFS) + 32768L * s(GEN_END_COARSE)
                val ls = h.loopStart + s(GEN_STARTLOOP_OFS) + 32768L * s(GEN_STARTLOOP_COARSE)
                val le = h.loopEnd + s(GEN_ENDLOOP_OFS) + 32768L * s(GEN_ENDLOOP_COARSE)
                if (end <= start || end * 2 > sdtaSize) continue
                val key = "$sid:$start:$end".hashCode()
                val data = sampleCache.getOrPut(key) { readSamples(src, sdtaPos, start, end) }
                val root = z[GEN_ROOT_KEY]?.toShort()?.toInt()?.takeIf { it in 0..127 } ?: h.pitch.takeIf { it in 0..127 } ?: 60
                fun tc(gen: Int, def: Int = -12000): Double {
                    val v = (z[gen]?.toShort()?.toInt() ?: def) + ps(gen)
                    return 2.0.pow(v.coerceIn(-12000, 8000) / 1200.0)
                }
                regions += Sf2Region(
                    keyLo = kLo, keyHi = kHi, velLo = vLo, velHi = vHi,
                    sample = data, sampleRate = h.rate.coerceIn(1000, 192000), rootKey = root,
                    tuneCents = (s(GEN_COARSE_TUNE) + ps(GEN_COARSE_TUNE)) * 100.0 + s(GEN_FINE_TUNE) + ps(GEN_FINE_TUNE) + h.correction,
                    loopStart = (ls - start).toInt().coerceIn(0, data.size - 1),
                    loopEnd = (le - start).toInt().coerceIn(0, data.size),
                    loopMode = (z[GEN_SAMPLE_MODES] ?: 0) and 3,
                    attenuationCb = (s(GEN_ATTENUATION) + ps(GEN_ATTENUATION)).toDouble().coerceIn(0.0, 1440.0),
                    attack = tc(GEN_ATTACK), hold = tc(GEN_HOLD), decay = tc(GEN_DECAY),
                    sustainCb = (s(GEN_SUSTAIN) + ps(GEN_SUSTAIN)).toDouble().coerceIn(0.0, 1440.0),
                    release = tc(GEN_RELEASE).coerceAtMost(8.0),
                )
            }
        }
        if (regions.isEmpty()) throw IOException("Preset '$presetName' has no usable samples")
        return Sf2Preset(presetName, u16(phdr, pi * 38 + 22), u16(phdr, pi * 38 + 20), regions)
    }

    private fun range(v: Int?): Pair<Int, Int> = if (v == null) 0 to 127 else (v and 0xFF) to ((v shr 8) and 0xFF)

    private fun readSamples(src: ByteSource, smplPos: Long, start: Long, end: Long): ShortArray {
        val n = (end - start).toInt()
        val bytes = ByteArray(n * 2)
        src.read(smplPos + start * 2, bytes, 0, bytes.size)
        return ShortArray(n) { ((bytes[2 * it + 1].toInt() shl 8) or (bytes[2 * it].toInt() and 0xFF)).toShort() }
    }

    private fun chunks(src: ByteSource, from: Long, to: Long): List<Chunk> {
        val out = ArrayList<Chunk>()
        var p = from
        val h = ByteArray(12)
        while (p + 8 <= to) {
            src.read(p, h, 0, 8)
            val id = String(h, 0, 4, Charsets.US_ASCII)
            val size = u32(h, 4).toInt()
            if (id == "LIST") {
                src.read(p + 8, h, 8, 4)
                out += Chunk("LIST:" + String(h, 8, 4, Charsets.US_ASCII), p + 12, size - 4)
            } else out += Chunk(id, p + 8, size)
            p += 8 + size + (size and 1)
        }
        return out
    }

    private fun top(src: ByteSource): List<Chunk> {
        val h = ByteArray(12)
        if (src.size < 12) throw IOException("Not a SoundFont")
        src.read(0, h, 0, 12)
        if (String(h, 0, 4, Charsets.US_ASCII) != "RIFF" || String(h, 8, 4, Charsets.US_ASCII) != "sfbk") throw IOException("Not a SoundFont 2 file")
        val size = u32(h, 4)
        return chunks(src, 12, minOf(src.size, 8 + size))
    }

    private fun findSmpl(src: ByteSource): Pair<Long, Long> {
        val sdta = top(src).firstOrNull { it.id == "LIST:sdta" } ?: throw IOException("SoundFont has no sample data")
        val smpl = chunks(src, sdta.pos, sdta.pos + sdta.size).firstOrNull { it.id == "smpl" } ?: throw IOException("SoundFont has no smpl chunk")
        return smpl.pos to smpl.size.toLong()
    }

    private fun readPdta(src: ByteSource): Map<String, ByteArray> {
        val pdta = top(src).firstOrNull { it.id == "LIST:pdta" } ?: throw IOException("SoundFont has no preset data")
        val m = HashMap<String, ByteArray>()
        for (c in chunks(src, pdta.pos, pdta.pos + pdta.size)) {
            val b = ByteArray(c.size)
            src.read(c.pos, b, 0, c.size)
            m[c.id] = b
        }
        for (k in listOf("phdr", "pbag", "pgen", "inst", "ibag", "igen", "shdr")) if (k !in m) throw IOException("SoundFont is missing $k")
        return m
    }

    private fun u16(b: ByteArray, o: Int) = (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8)
    private fun u32(b: ByteArray, o: Int) = (u16(b, o).toLong()) or (u16(b, o + 2).toLong() shl 16)
    private fun str(b: ByteArray, o: Int, n: Int): String {
        var e = o
        while (e < o + n && b[e] != 0.toByte()) e++
        return String(b, o, e - o, Charsets.US_ASCII).trim()
    }
}
