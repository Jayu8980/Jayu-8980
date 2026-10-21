package com.autotube.ai.pipeline.audio.synth

/**
 * An endless (or looping) source of 48 kHz-style interleaved stereo music, pulled block by block so
 * that long videos never need a full-length music buffer.
 */
interface MusicStream {
    val sampleRate: Int
    /** Human-readable origin for logs, e.g. "procedural calm/lofi" or "imported: rain.mp3". */
    val label: String
    /** Writes exactly [frames] interleaved stereo frames into dst[0 until frames * 2] (overwriting). */
    fun read(dst: FloatArray, frames: Int)
}
