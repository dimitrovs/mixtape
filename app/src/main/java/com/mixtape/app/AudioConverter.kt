package com.mixtape.app

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Converts any audio file to real MP3 using:
 * 1. Android MediaCodec to decode the source audio to PCM
 * 2. LAME (native, via JNI) to encode PCM to MP3
 *
 * Produces standard MP3 files compatible with any car stereo.
 */
class AudioConverter(private val context: Context) {

    companion object {
        private const val OUTPUT_SAMPLE_RATE = 44100
        private const val OUTPUT_BIT_RATE = 192000  // 192 kbps
        private const val LAME_QUALITY = 2          // high quality
        private const val TIMEOUT_US = 10_000L
        private const val PCM_CHUNK_SAMPLES = 4096  // samples per LAME encode call
    }

    sealed class ConversionResult {
        data object Success : ConversionResult()
        data class Error(val message: String) : ConversionResult()
    }

    /**
     * Decode any supported audio file to PCM, then encode to MP3 via LAME.
     */
    fun convertToMp3(inputUri: Uri, outputStream: OutputStream): ConversionResult {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(context, inputUri, null)

            val audioTrackIndex = findAudioTrack(extractor)
            if (audioTrackIndex < 0) {
                return ConversionResult.Error("No audio track found")
            }

            extractor.selectTrack(audioTrackIndex)
            val inputFormat = extractor.getTrackFormat(audioTrackIndex)
            val mime = inputFormat.getString(MediaFormat.KEY_MIME)
                ?: return ConversionResult.Error("Unknown MIME type")

            val inSampleRate = inputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            val inChannels = inputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)

            // Set up MediaCodec decoder
            val decoder = MediaCodec.createDecoderByType(mime)
            decoder.configure(inputFormat, null, null, 0)
            decoder.start()

            // Set up LAME MP3 encoder
            val lame = LameEncoder(
                inSampleRate = inSampleRate,
                inChannels = inChannels,
                outSampleRate = OUTPUT_SAMPLE_RATE,
                outBitRate = OUTPUT_BIT_RATE,
                quality = LAME_QUALITY
            )

            return try {
                decodeAndEncode(extractor, decoder, lame, inChannels, outputStream)
                ConversionResult.Success
            } finally {
                decoder.stop()
                decoder.release()
                lame.flush(outputStream)
                lame.close()
                outputStream.flush()
            }
        } catch (e: Exception) {
            return ConversionResult.Error(e.message ?: "Conversion failed")
        } finally {
            extractor.release()
        }
    }

    private fun findAudioTrack(extractor: MediaExtractor): Int {
        for (i in 0 until extractor.trackCount) {
            val format = extractor.getTrackFormat(i)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: continue
            if (mime.startsWith("audio/")) return i
        }
        return -1
    }

    /**
     * Decode audio frames to PCM via MediaCodec, then feed PCM to LAME
     * for MP3 encoding. Works in a streaming fashion — no need to hold
     * the entire decoded audio in memory.
     */
    private fun decodeAndEncode(
        extractor: MediaExtractor,
        decoder: MediaCodec,
        lame: LameEncoder,
        channels: Int,
        outputStream: OutputStream
    ) {
        val bufferInfo = MediaCodec.BufferInfo()
        var inputDone = false

        while (true) {
            // Feed compressed audio to decoder
            if (!inputDone) {
                val inputIndex = decoder.dequeueInputBuffer(TIMEOUT_US)
                if (inputIndex >= 0) {
                    val inputBuffer = decoder.getInputBuffer(inputIndex)!!
                    val sampleSize = extractor.readSampleData(inputBuffer, 0)
                    if (sampleSize < 0) {
                        decoder.queueInputBuffer(
                            inputIndex, 0, 0, 0,
                            MediaCodec.BUFFER_FLAG_END_OF_STREAM
                        )
                        inputDone = true
                    } else {
                        decoder.queueInputBuffer(
                            inputIndex, 0, sampleSize,
                            extractor.sampleTime, 0
                        )
                        extractor.advance()
                    }
                }
            }

            // Get decoded PCM and feed to LAME
            val outputIndex = decoder.dequeueOutputBuffer(bufferInfo, TIMEOUT_US)
            if (outputIndex >= 0) {
                val isEos = (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0

                if (bufferInfo.size > 0) {
                    val pcmBuffer = decoder.getOutputBuffer(outputIndex)!!
                    encodePcmChunk(pcmBuffer, bufferInfo.size, channels, lame, outputStream)
                }

                decoder.releaseOutputBuffer(outputIndex, false)

                if (isEos) break
            } else if (inputDone && outputIndex == MediaCodec.INFO_TRY_AGAIN_LATER) {
                break
            }
        }
    }

    /**
     * Convert a ByteBuffer of PCM data (16-bit signed, little-endian, interleaved)
     * into a ShortArray and feed it to LAME in chunks.
     */
    private fun encodePcmChunk(
        pcmBuffer: ByteBuffer,
        size: Int,
        channels: Int,
        lame: LameEncoder,
        outputStream: OutputStream
    ) {
        // MediaCodec outputs 16-bit PCM in native byte order (little-endian on ARM)
        pcmBuffer.order(ByteOrder.LITTLE_ENDIAN)

        val totalShorts = size / 2
        val pcmShorts = ShortArray(totalShorts)

        // Read shorts from the buffer
        val shortBuffer = pcmBuffer.asShortBuffer()
        shortBuffer.get(pcmShorts, 0, totalShorts)

        // Feed to LAME in manageable chunks
        // numSamples for LAME is per-channel
        val totalSamplesPerChannel = totalShorts / channels
        var offset = 0

        while (offset < totalSamplesPerChannel) {
            val samplesThisChunk = minOf(PCM_CHUNK_SAMPLES, totalSamplesPerChannel - offset)
            val shortOffset = offset * channels
            val shortCount = samplesThisChunk * channels

            val chunk = if (shortOffset == 0 && shortCount == pcmShorts.size) {
                pcmShorts
            } else {
                pcmShorts.copyOfRange(shortOffset, shortOffset + shortCount)
            }

            lame.encodeInterleaved(chunk, samplesThisChunk, outputStream)
            offset += samplesThisChunk
        }
    }
}
