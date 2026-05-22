package com.example.myapplication.core

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.util.Log
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.OutputStream
import java.io.RandomAccessFile
import kotlin.io.DEFAULT_BUFFER_SIZE
import kotlin.math.absoluteValue
import kotlin.math.max
import kotlin.math.min

object AudioWavUtils {
    private const val DEFAULT_SAMPLE_RATE_HZ = 16000
    private const val PCM_16BIT_MAX_F = 32768f

    data class WavMetadata(
        val sampleRate: Int,
        val channels: Int,
        val bitsPerSample: Int,
        val dataOffset: Long,
        val dataLength: Long
    ) {
        val bytesPerFrame: Int get() = channels * (bitsPerSample / 8)
        val durationMs: Long
            get() = if (bytesPerFrame <= 0 || sampleRate <= 0) 0L
            else ((dataLength.toDouble() / bytesPerFrame.toDouble()) / sampleRate.toDouble() * 1000.0).toLong()
    }

    fun maxAmplitude(bytes: ByteArray, size: Int): Int {
        var max = 0
        var i = 0
        while (i + 1 < size) {
            val low = bytes[i].toInt() and 0xFF
            val high = bytes[i + 1].toInt()
            val sample = ((high shl 8) or low).toShort().toInt().absoluteValue
            if (sample > max) max = sample
            i += 2
        }
        return max
    }

    fun writePcmAsWav(
        pcmFile: File,
        output: OutputStream,
        sampleRate: Int = DEFAULT_SAMPLE_RATE_HZ,
        channels: Int = 1,
        bitsPerSample: Int = 16
    ) {
        val totalDataLen = pcmFile.length().toInt()
        val byteRate = sampleRate * channels * bitsPerSample / 8
        val totalFileLen = totalDataLen + 36

        output.write(wavHeader(totalDataLen, sampleRate, channels, bitsPerSample))
        pcmFile.inputStream().use { input ->
            input.copyTo(output)
        }
    }

    fun extractSegmentToWav(
        sourceFile: File,
        targetFile: File,
        startMs: Long,
        endMs: Long,
        shouldContinue: (() -> Boolean)? = null
    ): Boolean {
        val metadata = readMetadata(sourceFile) ?: return false
        if (metadata.bytesPerFrame <= 0) return false

        val startFrame = ((startMs.coerceAtLeast(0L) * metadata.sampleRate) / 1000L)
        val endFrame = ((endMs.coerceAtLeast(startMs) * metadata.sampleRate) / 1000L)
        val alignedStart = metadata.dataOffset + (startFrame * metadata.bytesPerFrame)
        val alignedEnd = metadata.dataOffset + (endFrame * metadata.bytesPerFrame)
        val startByte = alignedStart.coerceIn(metadata.dataOffset, metadata.dataOffset + metadata.dataLength)
        val endByte = alignedEnd.coerceIn(startByte, metadata.dataOffset + metadata.dataLength)
        val bytesToCopy = endByte - startByte
        if (bytesToCopy <= 0L) return false

        targetFile.parentFile?.mkdirs()
        RandomAccessFile(sourceFile, "r").use { input ->
            targetFile.outputStream().use { output ->
                output.write(
                    wavHeader(
                        totalDataLen = bytesToCopy.toInt(),
                        sampleRate = metadata.sampleRate,
                        channels = metadata.channels,
                        bitsPerSample = metadata.bitsPerSample
                    )
                )
                input.seek(startByte)
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                var remaining = bytesToCopy
                while (remaining > 0) {
                    if (shouldContinue?.invoke() == false) return false
                    val read = input.read(buffer, 0, min(buffer.size.toLong(), remaining).toInt())
                    if (read <= 0) break
                    output.write(buffer, 0, read)
                    remaining -= read
                }
            }
        }
        return true
    }

    fun createWaveformBitmap(
        file: File,
        width: Int,
        height: Int,
        shouldContinue: (() -> Boolean)? = null
    ): Bitmap? {
        val metadata = readMetadata(file) ?: return null
        if (width <= 0 || height <= 0 || metadata.bitsPerSample != 16) return null

        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.TRANSPARENT)

        val barPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#6606B6D4")
            strokeWidth = max(2f, width / 180f)
            strokeCap = Paint.Cap.ROUND
        }
        val midY = height / 2f
        val sampleWindow = max(1L, metadata.dataLength / max(width, 1))

        RandomAccessFile(file, "r").use { raf ->
            for (x in 0 until width) {
                if (shouldContinue?.invoke() == false) return null
                val chunkStart = metadata.dataOffset + (x * sampleWindow)
                val chunkEnd = min(metadata.dataOffset + metadata.dataLength, chunkStart + sampleWindow)
                val amplitude = peakAmplitudeInRange(raf, chunkStart, chunkEnd, shouldContinue)
                val normalized = amplitude / PCM_16BIT_MAX_F
                val barHeight = max(6f, normalized * (height * 0.48f))
                val px = x.toFloat()
                canvas.drawLine(px, midY - barHeight, px, midY + barHeight, barPaint)
            }
        }
        return bitmap
    }

    private fun peakAmplitudeInRange(
        raf: RandomAccessFile,
        start: Long,
        end: Long,
        shouldContinue: (() -> Boolean)? = null
    ): Int {
        if (end <= start) return 0
        raf.seek(start)
        val buffer = ByteArray(min(DEFAULT_BUFFER_SIZE.toLong(), end - start).toInt())
        var peak = 0
        var remaining = end - start
        while (remaining > 0) {
            if (shouldContinue?.invoke() == false) return peak
            val read = raf.read(buffer, 0, min(buffer.size.toLong(), remaining).toInt())
            if (read <= 0) break
            peak = max(peak, maxAmplitude(buffer, read))
            remaining -= read
        }
        return peak
    }

    private fun readMetadata(file: File): WavMetadata? {
        if (!file.exists() || file.length() < 44) return null
        RandomAccessFile(file, "r").use { raf ->
            val riff = ByteArray(4).also { raf.readFully(it) }
            raf.skipBytes(4)
            val wave = ByteArray(4).also { raf.readFully(it) }
            if (String(riff) != "RIFF" || String(wave) != "WAVE") return null

            var channels = 1
            var sampleRate = 16000
            var bitsPerSample = 16
            var dataOffset = 0L
            var dataLength = 0L

            while (raf.filePointer + 8 <= raf.length()) {
                val chunkId = ByteArray(4).also { raf.readFully(it) }
                val chunkSize = readLittleEndianInt(raf).toLong()
                val chunkStart = raf.filePointer
                when (String(chunkId)) {
                    "fmt " -> {
                        raf.skipBytes(2)
                        channels = readLittleEndianShort(raf)
                        sampleRate = readLittleEndianInt(raf)
                        raf.skipBytes(6)
                        bitsPerSample = readLittleEndianShort(raf)
                    }
                    "data" -> {
                        dataOffset = raf.filePointer
                        dataLength = chunkSize
                        break
                    }
                }
                raf.seek(chunkStart + chunkSize + (chunkSize % 2))
            }

            if (dataOffset <= 0L || dataLength <= 0L) return null
            return WavMetadata(sampleRate, channels, bitsPerSample, dataOffset, dataLength)
        }
    }

    private fun wavHeader(totalDataLen: Int, sampleRate: Int, channels: Int, bitsPerSample: Int): ByteArray {
        val byteRate = sampleRate * channels * bitsPerSample / 8
        val totalFileLen = totalDataLen + 36
        return ByteArray(44).apply {
            this[0] = 'R'.code.toByte(); this[1] = 'I'.code.toByte(); this[2] = 'F'.code.toByte(); this[3] = 'F'.code.toByte()
            this[4] = (totalFileLen and 0xff).toByte(); this[5] = ((totalFileLen shr 8) and 0xff).toByte()
            this[6] = ((totalFileLen shr 16) and 0xff).toByte(); this[7] = ((totalFileLen shr 24) and 0xff).toByte()
            this[8] = 'W'.code.toByte(); this[9] = 'A'.code.toByte(); this[10] = 'V'.code.toByte(); this[11] = 'E'.code.toByte()
            this[12] = 'f'.code.toByte(); this[13] = 'm'.code.toByte(); this[14] = 't'.code.toByte(); this[15] = ' '.code.toByte()
            this[16] = 16; this[20] = 1
            this[22] = channels.toByte(); this[23] = 0
            this[24] = (sampleRate and 0xff).toByte(); this[25] = ((sampleRate shr 8) and 0xff).toByte()
            this[26] = ((sampleRate shr 16) and 0xff).toByte(); this[27] = ((sampleRate shr 24) and 0xff).toByte()
            this[28] = (byteRate and 0xff).toByte(); this[29] = ((byteRate shr 8) and 0xff).toByte()
            this[30] = ((byteRate shr 16) and 0xff).toByte(); this[31] = ((byteRate shr 24) and 0xff).toByte()
            this[32] = (channels * bitsPerSample / 8).toByte(); this[34] = bitsPerSample.toByte()
            this[36] = 'd'.code.toByte(); this[37] = 'a'.code.toByte(); this[38] = 't'.code.toByte(); this[39] = 'a'.code.toByte()
            this[40] = (totalDataLen and 0xff).toByte(); this[41] = ((totalDataLen shr 8) and 0xff).toByte()
            this[42] = ((totalDataLen shr 16) and 0xff).toByte(); this[43] = ((totalDataLen shr 24) and 0xff).toByte()
        }
    }

    private fun readLittleEndianShort(raf: RandomAccessFile): Int {
        val low = raf.read()
        val high = raf.read()
        return (high shl 8) or low
    }

    private fun readLittleEndianInt(raf: RandomAccessFile): Int {
        val b1 = raf.read()
        val b2 = raf.read()
        val b3 = raf.read()
        val b4 = raf.read()
        return (b4 shl 24) or (b3 shl 16) or (b2 shl 8) or b1
    }

    fun convertToWav(input: File, output: File): Boolean {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        return try {
            extractor.setDataSource(input.absolutePath)

            // Find the first audio track
            var audioTrackIndex = -1
            var audioFormat: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME) ?: continue
                if (mime.startsWith("audio/")) {
                    audioTrackIndex = i
                    audioFormat = format
                    break
                }
            }
            if (audioTrackIndex < 0 || audioFormat == null) {
                Log.w("AudioWavUtils", "convertToWav: no audio track found in ${input.name}")
                return false
            }

            extractor.selectTrack(audioTrackIndex)

            val mime = audioFormat.getString(MediaFormat.KEY_MIME)!!
            val sampleRate = audioFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            val channels = audioFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            val bitsPerSample = 16 // MediaCodec PCM output is always 16-bit

            codec = MediaCodec.createDecoderByType(mime)
            codec.configure(audioFormat, null, null, 0)
            codec.start()

            val pcmBuffer = ByteArrayOutputStream()
            val timeoutUs = 10_000L
            var inputDone = false
            var outputDone = false
            val bufferInfo = MediaCodec.BufferInfo()

            while (!outputDone) {
                // Feed input
                if (!inputDone) {
                    val inIndex = codec.dequeueInputBuffer(timeoutUs)
                    if (inIndex >= 0) {
                        val inBuffer = codec.getInputBuffer(inIndex)!!
                        val sampleSize = extractor.readSampleData(inBuffer, 0)
                        if (sampleSize < 0) {
                            codec.queueInputBuffer(inIndex, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            val presentationTimeUs = extractor.sampleTime
                            codec.queueInputBuffer(inIndex, 0, sampleSize, presentationTimeUs, 0)
                            extractor.advance()
                        }
                    }
                }

                // Drain output
                val outIndex = codec.dequeueOutputBuffer(bufferInfo, timeoutUs)
                when {
                    outIndex == MediaCodec.INFO_TRY_AGAIN_LATER -> { /* no-op */ }
                    outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> { /* format change; continue */ }
                    outIndex >= 0 -> {
                        val outBuffer = codec.getOutputBuffer(outIndex)
                        if (outBuffer != null && bufferInfo.size > 0) {
                            val chunk = ByteArray(bufferInfo.size)
                            outBuffer.get(chunk)
                            pcmBuffer.write(chunk)
                        }
                        codec.releaseOutputBuffer(outIndex, false)
                        if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                            outputDone = true
                        }
                    }
                }
            }

            // Write WAV file
            val pcmBytes = pcmBuffer.toByteArray()
            output.parentFile?.mkdirs()
            output.outputStream().use { out ->
                out.write(wavHeader(pcmBytes.size, sampleRate, channels, bitsPerSample))
                out.write(pcmBytes)
            }
            true
        } catch (e: Exception) {
            Log.w("AudioWavUtils", "convertToWav failed for ${input.name}: ${e.message}")
            false
        } finally {
            try { codec?.stop() } catch (_: Exception) {}
            try { codec?.release() } catch (_: Exception) {}
            try { extractor.release() } catch (_: Exception) {}
        }
    }
}
