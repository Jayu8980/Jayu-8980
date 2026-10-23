package com.pianoscribe.core.synth

import java.io.ByteArrayOutputStream
import kotlin.math.log2
import kotlin.math.roundToInt

/** Writes a single-preset, single-instrument SoundFont 2.01 file. */
object Sf2Writer {
    class Sample(
        val name: String,
        val data: ShortArray,
        val sampleRate: Int,
        val rootKey: Int,
        val keyLo: Int,
        val keyHi: Int,
        val loopStart: Int = 0,
        val loopEnd: Int = 0,
        val loopMode: Int = 0,
    )

    fun write(presetName: String, samples: List<Sample>, releaseSec: Double = 0.35, bank: Int = 0, program: Int = 0): ByteArray {
        // --- sdta: samples back to back, each followed by 46 zero points as the spec requires.
        val smpl = ByteArrayOutputStream()
        val starts = IntArray(samples.size)
        var pos = 0
        for ((i, s) in samples.withIndex()) {
            starts[i] = pos
            for (v in s.data) { smpl.write(v.toInt() and 0xFF); smpl.write((v.toInt() shr 8) and 0xFF) }
            repeat(46) { smpl.write(0); smpl.write(0) }
            pos += s.data.size + 46
        }

        // --- pdta
        val phdr = ByteArrayOutputStream()
        phdr.write(name20(presetName)); phdr.write(u16(program)); phdr.write(u16(bank)); phdr.write(u16(0)); phdr.write(u32(0)); phdr.write(u32(0)); phdr.write(u32(0))
        phdr.write(name20("EOP")); phdr.write(u16(0)); phdr.write(u16(0)); phdr.write(u16(1)); phdr.write(u32(0)); phdr.write(u32(0)); phdr.write(u32(0))
        val pbag = ByteArrayOutputStream().apply { write(u16(0)); write(u16(0)); write(u16(1)); write(u16(0)) }
        val pmod = ByteArrayOutputStream().apply { write(ByteArray(10)) }
        val pgen = ByteArrayOutputStream().apply { write(gen(41, 0)); write(gen(0, 0)) }
        val inst = ByteArrayOutputStream().apply { write(name20(presetName)); write(u16(0)); write(name20("EOI")); write(u16(samples.size)) }

        val ibag = ByteArrayOutputStream()
        val igen = ByteArrayOutputStream()
        var genIdx = 0
        val releaseTc = (1200 * log2(releaseSec)).roundToInt()
        for ((i, s) in samples.withIndex()) {
            ibag.write(u16(genIdx)); ibag.write(u16(0))
            val gens = listOf(
                gen(43, s.keyLo or (s.keyHi shl 8)), // keyRange must come first
                gen(38, releaseTc),
                gen(54, s.loopMode),
                gen(58, s.rootKey),
                gen(53, i), // sampleID must come last
            )
            gens.forEach { igen.write(it) }
            genIdx += gens.size
        }
        ibag.write(u16(genIdx)); ibag.write(u16(0))
        igen.write(gen(0, 0))
        val imod = ByteArrayOutputStream().apply { write(ByteArray(10)) }

        val shdr = ByteArrayOutputStream()
        for ((i, s) in samples.withIndex()) {
            val st = starts[i]
            shdr.write(name20(s.name))
            shdr.write(u32(st)); shdr.write(u32(st + s.data.size))
            shdr.write(u32(st + s.loopStart)); shdr.write(u32(st + if (s.loopEnd > 0) s.loopEnd else s.data.size - 1))
            shdr.write(u32(s.sampleRate)); shdr.write(s.rootKey); shdr.write(0)
            shdr.write(u16(0)); shdr.write(u16(1)) // mono sample
        }
        shdr.write(name20("EOS")); repeat(26) { shdr.write(0) }

        val info = list("INFO", chunk("ifil", u16(2) + u16(1)), chunk("isng", zstr("EMU8000")), chunk("INAM", zstr(presetName)), chunk("ISFT", zstr("PianoScribe")))
        val sdta = list("sdta", chunk("smpl", smpl.toByteArray()))
        val pdta = list(
            "pdta",
            chunk("phdr", phdr.toByteArray()), chunk("pbag", pbag.toByteArray()), chunk("pmod", pmod.toByteArray()), chunk("pgen", pgen.toByteArray()),
            chunk("inst", inst.toByteArray()), chunk("ibag", ibag.toByteArray()), chunk("imod", imod.toByteArray()), chunk("igen", igen.toByteArray()),
            chunk("shdr", shdr.toByteArray()),
        )
        val body = "sfbk".toByteArray(Charsets.US_ASCII) + info + sdta + pdta
        return "RIFF".toByteArray(Charsets.US_ASCII) + u32(body.size) + body
    }

    private fun gen(op: Int, amount: Int) = u16(op) + u16(amount and 0xFFFF)
    private fun chunk(id: String, data: ByteArray): ByteArray {
        val pad = if (data.size % 2 == 1) byteArrayOf(0) else ByteArray(0)
        return id.toByteArray(Charsets.US_ASCII) + u32(data.size) + data + pad
    }
    private fun list(type: String, vararg chunks: ByteArray): ByteArray {
        val body = chunks.fold(type.toByteArray(Charsets.US_ASCII)) { a, b -> a + b }
        return "LIST".toByteArray(Charsets.US_ASCII) + u32(body.size) + body
    }
    private fun zstr(s: String): ByteArray {
        val b = s.toByteArray(Charsets.US_ASCII) + 0
        return if (b.size % 2 == 1) b + 0 else b
    }
    private fun name20(s: String): ByteArray = s.toByteArray(Charsets.US_ASCII).copyOf(20)
    private fun u16(v: Int) = byteArrayOf(v.toByte(), (v shr 8).toByte())
    private fun u32(v: Int) = byteArrayOf(v.toByte(), (v shr 8).toByte(), (v shr 16).toByte(), (v shr 24).toByte())
}
