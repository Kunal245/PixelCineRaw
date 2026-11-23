package com.kunal.pixelcineraw.video

import android.content.ContentValues
import android.content.Context
import android.media.*
import android.net.Uri
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import android.util.Log
import android.view.Surface
import com.kunal.pixelcineraw.audio.AudioEngine
import com.kunal.pixelcineraw.camera.ColorProfile
import com.kunal.pixelcineraw.camera.VideoResolution
import java.nio.ByteBuffer
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 10-Bit 4K HEVC (H.265) Cinema Video Recorder with flat Log / HLG support,
 * high-bitrate encoding (up to 200 Mbps), and 48kHz audio track muxing.
 *
 * Implements strict zero-relative timestamp normalization across video and audio
 * and queues initial frames so IDR keyframes are never lost before muxer initialization.
 */
class HevcLogRecorder(
    private val context: Context,
    private val audioEngine: AudioEngine
) {
    companion object {
        private const val TAG = "HevcLogRecorder"
        private const val TIMEOUT_USEC = 10_000L
    }

    private data class PendingSample(
        val isAudio: Boolean,
        val data: ByteBuffer,
        val info: MediaCodec.BufferInfo
    )

    private var videoEncoder: MediaCodec? = null
    private var audioEncoder: MediaCodec? = null
    private var mediaMuxer: MediaMuxer? = null
    private var inputSurface: Surface? = null

    private var videoTrackIndex = -1
    private var audioTrackIndex = -1
    private var isMuxerStarted = false

    private val pendingVideoSamples = mutableListOf<PendingSample>()
    private val pendingAudioSamples = mutableListOf<PendingSample>()

    private var firstVideoPtsUs = -1L
    private var lastVideoPtsUs = -1L
    private var firstAudioPtsUs = -1L
    private var lastAudioPtsUs = -1L
    private var totalAudioBytesFed = 0L
    private var totalBytesWritten = 0L

    private val isRecording = AtomicBoolean(false)
    private var recordingThread: Thread? = null

    private var fileDescriptor: ParcelFileDescriptor? = null
    private var outputUri: Uri? = null

    var onMetricsUpdate: ((seconds: Long, bytes: Long, droppedFrames: Int) -> Unit)? = null

    val encoderSurface: Surface? get() = inputSurface

    /**
     * Initializes the HEVC encoder and creates the Camera2 recording Surface.
     */
    fun prepare(
        resolution: VideoResolution,
        fps: Int,
        bitrateMbps: Int,
        colorProfile: ColorProfile
    ): Surface {
        // Reset tracking state
        videoTrackIndex = -1
        audioTrackIndex = -1
        isMuxerStarted = false
        firstVideoPtsUs = -1L
        lastVideoPtsUs = -1L
        firstAudioPtsUs = -1L
        lastAudioPtsUs = -1L
        totalAudioBytesFed = 0L
        totalBytesWritten = 0L
        pendingVideoSamples.clear()
        pendingAudioSamples.clear()

        val width = resolution.width
        val height = resolution.height
        val bitrate = bitrateMbps * 1_000_000

        // 1. Configure Video Encoder (HEVC)
        val videoFormat = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_HEVC, width, height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
            setInteger(MediaFormat.KEY_FRAME_RATE, fps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1) // 1 second GOP for cinema scrubbing
            setLong(MediaFormat.KEY_REPEAT_PREVIOUS_FRAME_AFTER, 1_000_000L / fps)

            if (colorProfile == ColorProfile.HLG_10BIT) {
                setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10)
                setInteger(MediaFormat.KEY_COLOR_STANDARD, MediaFormat.COLOR_STANDARD_BT2020)
                setInteger(MediaFormat.KEY_COLOR_TRANSFER, MediaFormat.COLOR_TRANSFER_HLG)
                setInteger(MediaFormat.KEY_COLOR_RANGE, MediaFormat.COLOR_RANGE_FULL)
            } else {
                setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.HEVCProfileMain)
                setInteger(MediaFormat.KEY_COLOR_STANDARD, MediaFormat.COLOR_STANDARD_BT709)
                setInteger(MediaFormat.KEY_COLOR_RANGE, MediaFormat.COLOR_RANGE_FULL)
            }
        }

        videoEncoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_HEVC).apply {
            configure(videoFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            inputSurface = createInputSurface()
        }

        // 2. Configure Audio Encoder (AAC 48kHz Stereo 320kbps)
        val audioFormat = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, AudioEngine.SAMPLE_RATE, 2).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, 320_000)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384)
        }

        audioEncoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC).apply {
            configure(audioFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        }

        return inputSurface!!
    }

    /**
     * Starts recording to MediaStore (Movies/PixelCineRaw).
     */
    fun start(): String {
        if (isRecording.get()) return ""

        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val fileName = "PIXELCINE_${timestamp}.mp4"

        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, fileName)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, "${Environment.DIRECTORY_MOVIES}/PixelCineRaw")
            put(MediaStore.Video.Media.IS_PENDING, 1)
        }

        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
            ?: throw IllegalStateException("Failed to create MediaStore entry")
        outputUri = uri

        val pfd = resolver.openFileDescriptor(uri, "rw")
            ?: throw IllegalStateException("Failed to open file descriptor for $uri")
        fileDescriptor = pfd

        mediaMuxer = MediaMuxer(pfd.fileDescriptor, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)

        videoEncoder?.start()
        audioEncoder?.start()

        isRecording.set(true)

        // Connect audio engine callback to feed PCM data to audio encoder
        audioEngine.onPcmDataAvailable = { buffer, bytesRead, _ ->
            if (isRecording.get()) {
                feedAudioEncoder(buffer, bytesRead)
            }
        }

        recordingThread = Thread({
            drainEncoders()
        }, "PixelCineRecorderThread").apply {
            priority = Thread.MAX_PRIORITY
            start()
        }

        return fileName
    }

    /**
     * Feeds PCM buffer from AudioEngine to AAC encoder with sample-accurate timestamp calculation.
     */
    private fun feedAudioEncoder(buffer: ByteArray, bytesRead: Int) {
        val encoder = audioEncoder ?: return
        try {
            val inputBufferIndex = encoder.dequeueInputBuffer(TIMEOUT_USEC)
            if (inputBufferIndex >= 0) {
                val inputBuffer = encoder.getInputBuffer(inputBufferIndex)
                inputBuffer?.clear()
                inputBuffer?.put(buffer, 0, bytesRead)

                // 48kHz 16-bit stereo = 192,000 bytes per second
                val ptsUs = (totalAudioBytesFed * 1_000_000L) / (AudioEngine.SAMPLE_RATE * 4L)
                totalAudioBytesFed += bytesRead

                encoder.queueInputBuffer(inputBufferIndex, 0, bytesRead, ptsUs, 0)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error feeding audio encoder", e)
        }
    }

    /**
     * Drains output buffers from video and audio encoders and writes to MediaMuxer.
     */
    private fun drainEncoders() {
        val vEncoder = videoEncoder ?: return
        val aEncoder = audioEncoder ?: return
        val muxer = mediaMuxer ?: return

        val videoBufferInfo = MediaCodec.BufferInfo()
        val audioBufferInfo = MediaCodec.BufferInfo()

        val startTimeMs = System.currentTimeMillis()

        while (isRecording.get() || !isMuxerStarted) {
            // 1. Drain Video Encoder
            var videoOutIndex = vEncoder.dequeueOutputBuffer(videoBufferInfo, TIMEOUT_USEC)
            while (videoOutIndex >= 0) {
                val encodedData = vEncoder.getOutputBuffer(videoOutIndex)

                if (videoBufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                    videoBufferInfo.size = 0
                }

                if (videoBufferInfo.size > 0 && encodedData != null) {
                    // Normalize video PTS to zero-relative
                    if (firstVideoPtsUs < 0) {
                        firstVideoPtsUs = videoBufferInfo.presentationTimeUs
                    }
                    val normalizedPts = (videoBufferInfo.presentationTimeUs - firstVideoPtsUs).coerceAtLeast(0L)
                    val monotonicPts = if (normalizedPts <= lastVideoPtsUs) lastVideoPtsUs + 1000L else normalizedPts
                    lastVideoPtsUs = monotonicPts
                    videoBufferInfo.presentationTimeUs = monotonicPts

                    encodedData.position(videoBufferInfo.offset)
                    encodedData.limit(videoBufferInfo.offset + videoBufferInfo.size)

                    if (isMuxerStarted) {
                        muxer.writeSampleData(videoTrackIndex, encodedData, videoBufferInfo)
                        totalBytesWritten += videoBufferInfo.size
                    } else {
                        // Buffer keyframe and initial samples until muxer has started
                        val clone = ByteBuffer.allocate(videoBufferInfo.size)
                        clone.put(encodedData)
                        clone.flip()
                        val infoClone = MediaCodec.BufferInfo().apply {
                            set(0, videoBufferInfo.size, videoBufferInfo.presentationTimeUs, videoBufferInfo.flags)
                        }
                        pendingVideoSamples.add(PendingSample(isAudio = false, clone, infoClone))
                    }
                }

                vEncoder.releaseOutputBuffer(videoOutIndex, false)
                videoOutIndex = vEncoder.dequeueOutputBuffer(videoBufferInfo, 0)
            }

            if (videoOutIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED && !isMuxerStarted) {
                videoTrackIndex = muxer.addTrack(vEncoder.outputFormat)
                checkAndStartMuxer(muxer)
            }

            // 2. Drain Audio Encoder
            var audioOutIndex = aEncoder.dequeueOutputBuffer(audioBufferInfo, TIMEOUT_USEC)
            while (audioOutIndex >= 0) {
                val encodedData = aEncoder.getOutputBuffer(audioOutIndex)

                if (audioBufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                    audioBufferInfo.size = 0
                }

                if (audioBufferInfo.size > 0 && encodedData != null) {
                    // Normalize audio PTS to zero-relative
                    if (firstAudioPtsUs < 0) {
                        firstAudioPtsUs = audioBufferInfo.presentationTimeUs
                    }
                    val normalizedPts = (audioBufferInfo.presentationTimeUs - firstAudioPtsUs).coerceAtLeast(0L)
                    val monotonicPts = if (normalizedPts <= lastAudioPtsUs) lastAudioPtsUs + 1000L else normalizedPts
                    lastAudioPtsUs = monotonicPts
                    audioBufferInfo.presentationTimeUs = monotonicPts

                    encodedData.position(audioBufferInfo.offset)
                    encodedData.limit(audioBufferInfo.offset + audioBufferInfo.size)

                    if (isMuxerStarted) {
                        muxer.writeSampleData(audioTrackIndex, encodedData, audioBufferInfo)
                        totalBytesWritten += audioBufferInfo.size
                    } else {
                        val clone = ByteBuffer.allocate(audioBufferInfo.size)
                        clone.put(encodedData)
                        clone.flip()
                        val infoClone = MediaCodec.BufferInfo().apply {
                            set(0, audioBufferInfo.size, audioBufferInfo.presentationTimeUs, audioBufferInfo.flags)
                        }
                        pendingAudioSamples.add(PendingSample(isAudio = true, clone, infoClone))
                    }
                }

                aEncoder.releaseOutputBuffer(audioOutIndex, false)
                audioOutIndex = aEncoder.dequeueOutputBuffer(audioBufferInfo, 0)
            }

            if (audioOutIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED && !isMuxerStarted) {
                audioTrackIndex = muxer.addTrack(aEncoder.outputFormat)
                checkAndStartMuxer(muxer)
            }

            // Update metrics: use real video duration
            val elapsedSecs = if (firstVideoPtsUs >= 0) {
                lastVideoPtsUs / 1_000_000L
            } else {
                (System.currentTimeMillis() - startTimeMs) / 1000L
            }
            onMetricsUpdate?.invoke(elapsedSecs, totalBytesWritten, 0)
        }
    }

    private fun checkAndStartMuxer(muxer: MediaMuxer) {
        if (videoTrackIndex >= 0 && audioTrackIndex >= 0 && !isMuxerStarted) {
            muxer.start()
            isMuxerStarted = true
            Log.i(TAG, "MediaMuxer started with video track $videoTrackIndex and audio track $audioTrackIndex")

            // Flush buffered video frames (including the crucial IDR keyframe!)
            for (sample in pendingVideoSamples) {
                muxer.writeSampleData(videoTrackIndex, sample.data, sample.info)
                totalBytesWritten += sample.info.size
            }
            pendingVideoSamples.clear()

            // Flush buffered audio packets
            for (sample in pendingAudioSamples) {
                muxer.writeSampleData(audioTrackIndex, sample.data, sample.info)
                totalBytesWritten += sample.info.size
            }
            pendingAudioSamples.clear()
        }
    }

    /**
     * Stops recording and finalizes the MP4 file in MediaStore.
     */
    fun stop() {
        if (!isRecording.getAndSet(false)) return

        audioEngine.onPcmDataAvailable = null

        try {
            // Signal EOS to encoders
            videoEncoder?.signalEndOfInputStream()
        } catch (e: Exception) {
            Log.w(TAG, "Error signaling EOS to video encoder", e)
        }

        try {
            audioEncoder?.let { encoder ->
                val idx = encoder.dequeueInputBuffer(TIMEOUT_USEC)
                if (idx >= 0) {
                    encoder.queueInputBuffer(idx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error sending audio EOS", e)
        }

        try {
            recordingThread?.join(2000)
        } catch (e: InterruptedException) {
            Log.w(TAG, "Recording thread interrupted", e)
        }

        try {
            videoEncoder?.stop()
            videoEncoder?.release()
        } catch (e: Exception) {
            Log.w(TAG, "Error releasing video encoder", e)
        } finally {
            videoEncoder = null
        }

        try {
            audioEncoder?.stop()
            audioEncoder?.release()
        } catch (e: Exception) {
            Log.w(TAG, "Error releasing audio encoder", e)
        } finally {
            audioEncoder = null
        }

        try {
            if (isMuxerStarted) {
                mediaMuxer?.stop()
            }
            mediaMuxer?.release()
        } catch (e: Exception) {
            Log.w(TAG, "Error releasing MediaMuxer", e)
        } finally {
            mediaMuxer = null
            isMuxerStarted = false
        }

        fileDescriptor?.close()
        fileDescriptor = null

        // Mark file as complete in MediaStore (remove IS_PENDING)
        outputUri?.let { uri ->
            val values = ContentValues().apply {
                put(MediaStore.Video.Media.IS_PENDING, 0)
            }
            context.contentResolver.update(uri, values, null, null)
        }

        inputSurface?.release()
        inputSurface = null
    }
}
