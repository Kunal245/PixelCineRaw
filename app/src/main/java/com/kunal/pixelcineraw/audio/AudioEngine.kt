package com.kunal.pixelcineraw.audio

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import kotlinx.coroutines.*
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.log10
import kotlin.math.max

/**
 * Professional 48kHz 16-bit Stereo Audio Engine supporting USB-C external microphones,
 * manual gain, and real-time dB VU metering.
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

    @Volatile
    var isRunning = false
        private set

    var audioGain = 1.0f

    // Callback when raw PCM buffer is available for video muxer encoder
    var onPcmDataAvailable: ((buffer: ByteArray, bytesRead: Int, presentationTimeUs: Long) -> Unit)? = null

    @SuppressLint("MissingPermission")
    fun start() {
        if (isRunning) return

        val minBufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT)
        val bufferSize = max(minBufferSize * 2, 8192)

        try {
            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.CAMCORDER,
                SAMPLE_RATE,
                CHANNEL_CONFIG,
                AUDIO_FORMAT,
                bufferSize
            )

            // Prefer USB-C external microphone if connected
            routeToUsbMicIfAvailable(audioRecord!!)

            audioRecord?.startRecording()
            isRunning = true

            recordingJob = scope.launch {
                val audioBuffer = ByteArray(bufferSize)
                var startTimeUs = System.nanoTime() / 1000

                while (isActive && isRunning) {
                    val bytesRead = audioRecord?.read(audioBuffer, 0, bufferSize) ?: -1
                    if (bytesRead > 0) {
                        val ptsUs = (System.nanoTime() / 1000) - startTimeUs

                        // Calculate stereo peaks in dB
                        calculateVuPeaks(audioBuffer, bytesRead)

                        // Pass to audio track in video muxer if recording
                        onPcmDataAvailable?.invoke(audioBuffer, bytesRead, ptsUs)
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
        val shortBuffer = ByteBuffer.wrap(buffer, 0, bytesRead)
            .order(ByteOrder.LITTLE_ENDIAN)
            .asShortBuffer()

        var maxL = 0
        var maxR = 0

        val numSamples = shortBuffer.remaining()
        var i = 0
        while (i < numSamples) {
            val sampleL = kotlin.math.abs(shortBuffer.get(i).toInt())
            val sampleR = if (i + 1 < numSamples) kotlin.math.abs(shortBuffer.get(i + 1).toInt()) else sampleL

            if (sampleL > maxL) maxL = sampleL
            if (sampleR > maxR) maxR = sampleR
            i += 2
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
