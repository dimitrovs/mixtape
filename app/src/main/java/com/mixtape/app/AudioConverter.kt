package com.mixtape.app

import android.content.Context
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import java.io.OutputStream
import java.nio.ByteBuffer

class AudioConverter(private val context: Context) {

    companion object {
        private const val MP3_MIME = "audio/mpeg"
        private const val AAC_MIME = "audio/mp4a-latm"
        private const val OUTPUT_SAMPLE_RATE = 44100
        private const val OUTPUT_CHANNEL_COUNT = 2
        private const val OUTPUT_BIT_RATE = 192000
        private const val TIMEOUT_US = 10_000L
    }

    sealed class ConversionResult {
        data object Success : ConversionResult()
        data class Error(val message: String) : ConversionResult()
    }

    /**
     * Converts an audio file to MP3 format by decoding to PCM then encoding.
     *
     * Android's MediaCodec does not include a built-in MP3 encoder on most devices.
     * This method decodes audio to PCM and then re-encodes to AAC in an ADTS container
     * (.mp3 extension for compatibility), which is universally playable.
     * For true MP3, an external library like LAME would be needed, but this approach
     * keeps the app dependency-free while producing universally-playable output.
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
            val mime = inputFormat.getString(MediaFormat.KEY_MIME) ?: return ConversionResult.Error("Unknown MIME type")

            // Decode to PCM
            val decoder = MediaCodec.createDecoderByType(mime)
            decoder.configure(inputFormat, null, null, 0)
            decoder.start()

            // Encode to AAC (universally supported encoder on Android)
            val encoderFormat = MediaFormat.createAudioFormat(
                AAC_MIME,
                OUTPUT_SAMPLE_RATE,
                OUTPUT_CHANNEL_COUNT
            ).apply {
                setInteger(MediaFormat.KEY_BIT_RATE, OUTPUT_BIT_RATE)
                setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384)
            }

            val encoder = MediaCodec.createEncoderByType(AAC_MIME)
            encoder.configure(encoderFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            encoder.start()

            return try {
                transcode(extractor, decoder, encoder, outputStream)
                ConversionResult.Success
            } finally {
                decoder.stop()
                decoder.release()
                encoder.stop()
                encoder.release()
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

    private fun transcode(
        extractor: MediaExtractor,
        decoder: MediaCodec,
        encoder: MediaCodec,
        outputStream: OutputStream
    ) {
        val bufferInfo = MediaCodec.BufferInfo()
        var inputDone = false
        var decodeDone = false
        val adtsHeader = ByteArray(7)

        while (true) {
            // Feed input to decoder
            if (!inputDone) {
                val inputIndex = decoder.dequeueInputBuffer(TIMEOUT_US)
                if (inputIndex >= 0) {
                    val inputBuffer = decoder.getInputBuffer(inputIndex)!!
                    val sampleSize = extractor.readSampleData(inputBuffer, 0)
                    if (sampleSize < 0) {
                        decoder.queueInputBuffer(inputIndex, 0, 0, 0,
                            MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        inputDone = true
                    } else {
                        decoder.queueInputBuffer(inputIndex, 0, sampleSize,
                            extractor.sampleTime, 0)
                        extractor.advance()
                    }
                }
            }

            // Get decoded PCM from decoder, feed to encoder
            if (!decodeDone) {
                val decoderOutIndex = decoder.dequeueOutputBuffer(bufferInfo, TIMEOUT_US)
                if (decoderOutIndex >= 0) {
                    val decodedBuffer = decoder.getOutputBuffer(decoderOutIndex)!!
                    val isEos = (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0

                    if (bufferInfo.size > 0) {
                        // Feed PCM data to encoder
                        feedEncoder(encoder, decodedBuffer, bufferInfo, isEos)
                    } else if (isEos) {
                        // Signal EOS to encoder with empty buffer
                        signalEncoderEos(encoder)
                    }

                    decoder.releaseOutputBuffer(decoderOutIndex, false)
                    if (isEos) decodeDone = true
                }
            }

            // Read encoded data from encoder
            val encoderOutIndex = encoder.dequeueOutputBuffer(bufferInfo, TIMEOUT_US)
            if (encoderOutIndex >= 0) {
                val encodedBuffer = encoder.getOutputBuffer(encoderOutIndex)!!
                val isEos = (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0

                if (bufferInfo.size > 0) {
                    // Write ADTS header + AAC frame
                    val frameLength = bufferInfo.size + 7
                    buildAdtsHeader(adtsHeader, frameLength)
                    outputStream.write(adtsHeader)

                    val data = ByteArray(bufferInfo.size)
                    encodedBuffer.get(data)
                    outputStream.write(data)
                }

                encoder.releaseOutputBuffer(encoderOutIndex, false)
                if (isEos) break
            } else if (decodeDone && encoderOutIndex == MediaCodec.INFO_TRY_AGAIN_LATER) {
                // If decode is done and encoder has nothing, we might be done
                break
            }
        }

        outputStream.flush()
    }

    private fun feedEncoder(
        encoder: MediaCodec,
        pcmBuffer: ByteBuffer,
        info: MediaCodec.BufferInfo,
        isEos: Boolean
    ) {
        val remaining = pcmBuffer.remaining()
        if (remaining <= 0 && !isEos) return

        val inputIndex = encoder.dequeueInputBuffer(TIMEOUT_US)
        if (inputIndex >= 0) {
            val encoderInput = encoder.getInputBuffer(inputIndex)!!
            val bytesToCopy = minOf(remaining, encoderInput.capacity())
            val tempBytes = ByteArray(bytesToCopy)
            pcmBuffer.get(tempBytes, 0, bytesToCopy)
            encoderInput.clear()
            encoderInput.put(tempBytes)

            val flags = if (isEos) MediaCodec.BUFFER_FLAG_END_OF_STREAM else 0
            encoder.queueInputBuffer(inputIndex, 0, bytesToCopy, info.presentationTimeUs, flags)
        }
    }

    private fun signalEncoderEos(encoder: MediaCodec) {
        val inputIndex = encoder.dequeueInputBuffer(TIMEOUT_US)
        if (inputIndex >= 0) {
            encoder.queueInputBuffer(inputIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
        }
    }

    /**
     * Build an ADTS header for an AAC frame.
     * This makes individual AAC frames playable as a stream (like MP3 frames).
     */
    private fun buildAdtsHeader(header: ByteArray, frameLength: Int) {
        val profile = 2 // AAC-LC
        val sampleRateIndex = 4 // 44100 Hz
        val channelConfig = OUTPUT_CHANNEL_COUNT

        header[0] = 0xFF.toByte()
        header[1] = 0xF9.toByte() // MPEG-4, Layer 0, no CRC
        header[2] = (((profile - 1) shl 6) or (sampleRateIndex shl 2) or (channelConfig shr 2)).toByte()
        header[3] = (((channelConfig and 3) shl 6) or (frameLength shr 11)).toByte()
        header[4] = ((frameLength shr 3) and 0xFF).toByte()
        header[5] = (((frameLength and 7) shl 5) or 0x1F).toByte()
        header[6] = 0xFC.toByte()
    }
}
