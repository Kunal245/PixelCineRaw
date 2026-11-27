package com.kunal.pixelcineraw.audio

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTimestamp
import android.media.MediaRecorder
import android.util.Log
import kotlinx.coroutines.*
import kotlin.math.log10
import kotlin.math.max

/**
 * Professional 48kHz 16-bit Stereo Audio Engine supporting USB-C external microphones,
 * manual gain, real-time dB VU metering, and CLOCK_MONOTONIC AudioTimestamp alignment.
 */
class AudioEngine(
    private val context: Context,
    private val onLevelsUpdate: (peakL: Float, peakR: Float) -> Unit
) {
    companion object {
        private const val TAG = "PixelCineAudio"
        const val SAMPLE_RATE = 48000
        const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_STEREO
        const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
    }

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var audioRecord: AudioRecord? = null
    private var recordingJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val audioTimestamp = AudioTimestamp()

    @Volatile
    var isRunning = false
        private set

    var audioGain = 1.0f

    // Callback when raw PCM buffer is available for video muxer encoder (timestamp in CLOCK_MONOTONIC us)
    var onPcmDataAvailable: ((buffer: ByteArray, bytesRead: Int, presentationTimeUs: Long) -> Unit)? = null

    @SuppressLint("MissingPermission")
    fun start() {
        if (isRunning) return

        val minBufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT)
        val bufferSize = max(minBufferSize * 2, 8192)

        try {
            val record = AudioRecord(
                MediaRecorder.AudioSource.CAMCORDER,
                SAMPLE_RATE,
                CHANNEL_CONFIG,
                AUDIO_FORMAT,
                bufferSize
            )
            audioRecord = record

            // Prefer USB-C external microphone if connected
            routeToUsbMicIfAvailable(record)

            record.startRecording()
            isRunning = true

            recordingJob = scope.launch {
                val audioBuffer = ByteArray(bufferSize)
                var totalFramesRead = 0L

                while (isActive && isRunning) {
                    val bytesRead = record.read(audioBuffer, 0, bufferSize)
                    if (bytesRead > 0) {
                        val chunkFrames = bytesRead / 4L
                        val chunkStartFrame = totalFramesRead
                        totalFramesRead += chunkFrames

                        val hasTimestamp = try {
                            record.getTimestamp(audioTimestamp, AudioTimestamp.TIMEBASE_MONOTONIC) == AudioRecord.SUCCESS
                        } catch (_: Throwable) {
                            false
                        }
                        val chunkMonotonicNs = if (hasTimestamp && audioTimestamp.nanoTime > 0L) {
                            val frameDelta = chunkStartFrame - audioTimestamp.framePosition
                            audioTimestamp.nanoTime + (frameDelta * 1_000_000_000L) / SAMPLE_RATE
                        } else {
                            System.nanoTime() - (chunkFrames * 1_000_000_000L) / SAMPLE_RATE
                        }
                        val monotonicPtsUs = (chunkMonotonicNs / 1000L).coerceAtLeast(0L)

                        // Calculate stereo peaks in dB (zero allocation)
                        calculateVuPeaks(audioBuffer, bytesRead)

                        // Pass to audio track in video muxer if recording
                        onPcmDataAvailable?.invoke(audioBuffer, bytesRead, monotonicPtsUs)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start AudioEngine", e)
        }
    }

    fun stop() {
        isRunning = false
        recordingJob?.cancel()
        recordingJob = null

        try {
            audioRecord?.stop()
            audioRecord?.release()
        } catch (e: Exception) {
            Log.w(TAG, "Error releasing AudioRecord", e)
        } finally {
            audioRecord = null
        }

        onLevelsUpdate(-60f, -60f)
    }

    private fun routeToUsbMicIfAvailable(record: AudioRecord) {
        try {
            val devices = audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS)
            val usbDevice = devices.firstOrNull {
                it.type == AudioDeviceInfo.TYPE_USB_DEVICE ||
                it.type == AudioDeviceInfo.TYPE_USB_HEADSET
            }
            if (usbDevice != null) {
                record.setPreferredDevice(usbDevice)
                Log.i(TAG, "Routed audio recording to USB device: ${usbDevice.productName}")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to route to USB mic", e)
        }
    }

    private fun calculateVuPeaks(buffer: ByteArray, bytesRead: Int) {
        var maxL = 0
        var maxR = 0

        var byteIdx = 0
        while (byteIdx + 1 < bytesRead) {
            val rawL = ((buffer[byteIdx + 1].toInt() shl 8) or (buffer[byteIdx].toInt() and 0xFF)).toShort().toInt()
            val sampleL = kotlin.math.abs(rawL)
            val sampleR = if (byteIdx + 3 < bytesRead) {
                val rawR = ((buffer[byteIdx + 3].toInt() shl 8) or (buffer[byteIdx + 2].toInt() and 0xFF)).toShort().toInt()
                kotlin.math.abs(rawR)
            } else {
                sampleL
            }

            if (sampleL > maxL) maxL = sampleL
            if (sampleR > maxR) maxR = sampleR
            byteIdx += 4
        }

        // Apply software gain
        val scaledL = (maxL * audioGain).coerceIn(1f, 32767f)
        val scaledR = (maxR * audioGain).coerceIn(1f, 32767f)

        // Convert to dB: 20 * log10(val / 32768)
        val dbL = (20.0 * log10(scaledL / 32768.0)).toFloat().coerceIn(-60f, 0f)
        val dbR = (20.0 * log10(scaledR / 32768.0)).toFloat().coerceIn(-60f, 0f)

        onLevelsUpdate(dbL, dbR)
    }
}
