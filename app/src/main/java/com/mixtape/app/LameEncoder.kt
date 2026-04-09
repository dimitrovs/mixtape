package com.mixtape.app

import java.io.Closeable
import java.io.OutputStream

/**
 * Kotlin wrapper around the LAME MP3 encoder via JNI.
 * Produces real MP3 files that play on any car stereo.
 */
class LameEncoder(
    inSampleRate: Int,
    inChannels: Int,
    outSampleRate: Int = 44100,
    outBitRate: Int = 192000,
    quality: Int = 2
) : Closeable {

    companion object {
        init {
            System.loadLibrary("lame_jni")
        }

        // MP3 buffer size recommendation: 1.25 * numSamples + 7200
        fun bufferSize(numSamples: Int): Int = (1.25 * numSamples + 7200).toInt()
    }

    private var closed = false

    init {
        nativeInit(inSampleRate, inChannels, outSampleRate, outBitRate, quality)
    }

    /**
     * Encode interleaved PCM samples (16-bit signed) to MP3.
     * For stereo: samples are L,R,L,R,...
     * numSamples is per-channel (total shorts / channels).
     */
    fun encodeInterleaved(
        pcm: ShortArray,
        numSamples: Int,
        outputStream: OutputStream
    ): Int {
        check(!closed) { "Encoder is closed" }
        val mp3Buf = ByteArray(bufferSize(numSamples))
        val bytesEncoded = nativeEncodeInterleaved(pcm, numSamples, mp3Buf)
        if (bytesEncoded > 0) {
            outputStream.write(mp3Buf, 0, bytesEncoded)
        }
        return bytesEncoded
    }

    /**
     * Flush remaining MP3 data. Must be called before close.
     */
    fun flush(outputStream: OutputStream): Int {
        check(!closed) { "Encoder is closed" }
        val mp3Buf = ByteArray(7200)
        val bytesEncoded = nativeFlush(mp3Buf)
        if (bytesEncoded > 0) {
            outputStream.write(mp3Buf, 0, bytesEncoded)
        }
        return bytesEncoded
    }

    override fun close() {
        if (!closed) {
            closed = true
            nativeClose()
        }
    }

    // JNI methods
    private external fun nativeInit(
        inSampleRate: Int, inChannels: Int,
        outSampleRate: Int, outBitRate: Int, quality: Int
    )

    private external fun nativeEncode(
        leftChannel: ShortArray, rightChannel: ShortArray?,
        numSamples: Int, mp3Buffer: ByteArray
    ): Int

    private external fun nativeEncodeInterleaved(
        pcmData: ShortArray, numSamples: Int, mp3Buffer: ByteArray
    ): Int

    private external fun nativeFlush(mp3Buffer: ByteArray): Int
    private external fun nativeClose()
}
