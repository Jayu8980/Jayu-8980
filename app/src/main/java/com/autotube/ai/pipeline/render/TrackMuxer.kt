package com.autotube.ai.pipeline.render

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import java.io.File
import java.nio.ByteBuffer

/**
 * Copies the video track of one MP4 and the audio track of another into a new MP4 without
 * re-encoding (MediaExtractor + MediaMuxer). Audio is cut at the end of the video.
 */
internal object TrackMuxer {

    private const val DEFAULT_BUFFER = 2 * 1024 * 1024

    fun mux(videoFile: File, audioFile: File, outFile: File) {
        val videoEx = MediaExtractor()
        val audioEx = MediaExtractor()
        var muxer: MediaMuxer? = null
        var started = false
        try {
            videoEx.setDataSource(videoFile.absolutePath)
            audioEx.setDataSource(audioFile.absolutePath)
            val vTrack = findTrack(videoEx, "video/") ?: error("No video track in ${videoFile.name}")
            val aTrack = findTrack(audioEx, "audio/") ?: error("No audio track in ${audioFile.name}")
            videoEx.selectTrack(vTrack)
            audioEx.selectTrack(aTrack)
            val vFormat = videoEx.getTrackFormat(vTrack)
            val aFormat = audioEx.getTrackFormat(aTrack)
            val videoDurationUs = if (vFormat.containsKey(MediaFormat.KEY_DURATION)) vFormat.getLong(MediaFormat.KEY_DURATION) else Long.MAX_VALUE

            if (outFile.exists()) outFile.delete()
            val m = MediaMuxer(outFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            muxer = m
            val rotation = if (vFormat.containsKey(MediaFormat.KEY_ROTATION)) vFormat.getInteger(MediaFormat.KEY_ROTATION) else 0
            if (rotation != 0) m.setOrientationHint(rotation)
            val outV = m.addTrack(vFormat)
            val outA = m.addTrack(aFormat)
            m.start()
            started = true

            val size = maxOf(bufferSize(vFormat), bufferSize(aFormat))
            val buffer = ByteBuffer.allocateDirect(size)
            val info = MediaCodec.BufferInfo()
            var videoDone = false
            var audioDone = false
            // Interleave by timestamp so players can stream the file.
            while (!videoDone || !audioDone) {
                val vt = if (videoDone) Long.MAX_VALUE else videoEx.sampleTime
                val at = if (audioDone) Long.MAX_VALUE else audioEx.sampleTime
                if (!videoDone && vt < 0) { videoDone = true; continue }
                if (!audioDone && (at < 0 || at > videoDurationUs)) { audioDone = true; continue }
                val useVideo = vt <= at
                val ex = if (useVideo) videoEx else audioEx
                buffer.clear()
                val read = ex.readSampleData(buffer, 0)
                if (read < 0) {
                    if (useVideo) videoDone = true else audioDone = true
                    continue
                }
                info.offset = 0
                info.size = read
                info.presentationTimeUs = ex.sampleTime
                info.flags = if (ex.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0
                m.writeSampleData(if (useVideo) outV else outA, buffer, info)
                ex.advance()
            }
        } finally {
            runCatching { if (started) muxer?.stop() }
            runCatching { muxer?.release() }
            runCatching { videoEx.release() }
            runCatching { audioEx.release() }
        }
    }

    private fun findTrack(ex: MediaExtractor, prefix: String): Int? {
        for (i in 0 until ex.trackCount) {
            val mime = ex.getTrackFormat(i).getString(MediaFormat.KEY_MIME) ?: continue
            if (mime.startsWith(prefix)) return i
        }
        return null
    }

    private fun bufferSize(format: MediaFormat): Int =
        if (format.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) maxOf(format.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE), DEFAULT_BUFFER) else DEFAULT_BUFFER
}
