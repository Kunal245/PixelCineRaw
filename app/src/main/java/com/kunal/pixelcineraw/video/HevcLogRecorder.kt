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
import com.kunal.pixelcineraw.camera.CustomResolution
import com.kunal.pixelcineraw.camera.IntraFrameMode
import com.kunal.pixelcineraw.camera.VideoCodec
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Max-Out 10-Bit HEVC Cinema Video Recorder for Google Pixel 9a (Tensor G4).
 * Supports:
 * - 10-bit 4:2:2 (HEVCProfileMain42210) & 10-bit 4:2:0 (HEVCProfileMain10)
 * - All-Intra (ALL-I) intra-frame encoding (I_FRAME_INTERVAL = 0) at up to 280 Mbps peak VBR
 * - Full HDR suite: HDR10+ (SMPTE ST.2084 PQ) & HLG10 (ARIB STD-B67 / BT.2020)
 * - Studio-grade 48kHz Stereo 320 kbps AAC-LC muxed into MP4 + simultaneous 48kHz uncompressed PCM .wav sidecar
 */
class HevcLogRecorder(
    private val context: Context,
    private val audioEngine: AudioEngine
) {
    companion object {
        private const val TAG = "HevcLogRecorder"
        private const val TIMEOUT_USEC = 10_000L
        const val HEVC_PROFILE_MAIN_422_10 = 0x4000 // Android framework HEVC 10-bit 4:2:2 profile constant

        fun probeHasMain42210(): Boolean {
            try {
                val codecList = MediaCodecList(MediaCodecList.ALL_CODECS)
                for (codecInfo in codecList.codecInfos) {
                    if (!codecInfo.isEncoder) continue
                    for (type in codecInfo.supportedTypes) {
                        if (type.equals(MediaFormat.MIMETYPE_VIDEO_HEVC, ignoreCase = true)) {
                            val caps = codecInfo.getCapabilitiesForType(type)
                            val profileLevels = caps.profileLevels ?: continue
                            for (pl in profileLevels) {
                                if (pl.profile == HEVC_PROFILE_MAIN_422_10) {
                                    return true
                                }
                            }
                        }
                    }
                }
            } catch (e: Throwable) {
                Log.w(TAG, "Failed to probe HEVC 4:2:2 profile", e)
            }
            return false
        }

        fun getChromaBadge(intraFrameMode: IntraFrameMode): String {
            val chroma = if (probeHasMain42210()) "10B 4:2:2" else "10B 4:2:0"
            return if (intraFrameMode == IntraFrameMode.ALL_INTRA) "$chroma ALL-I" else "$chroma GOP"
        }
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

    private var isHlgRecording = false
    private var activeColorProfile: ColorProfile = ColorProfile.REC709
    private var shouldRecordWavSidecar: Boolean = true

    private val isRecording = AtomicBoolean(false)
    private var recordingThread: Thread? = null

    private var fileDescriptor: ParcelFileDescriptor? = null
    private var outputUri: Uri? = null

    private var wavPfd: ParcelFileDescriptor? = null
    private var wavUri: Uri? = null
    private var wavFileOutputStream: FileOutputStream? = null
    private var wavBytesWritten: Long = 0L

    var onMetricsUpdate: ((seconds: Long, bytes: Long, droppedFrames: Int) -> Unit)? = null

    val encoderSurface: Surface? get() = inputSurface

    /**
     * Initializes the cinema hardware encoder (HEVC 10-bit 4:2:2 / 4:2:0 ALL-I or AVC)
     * and creates the Camera2 recording Surface.
     */
    fun prepare(
        resolution: CustomResolution,
        fps: Int,
        bitrateMbps: Int,
        colorProfile: ColorProfile,
        codec: VideoCodec = VideoCodec.HEVC_10BIT,
        intraFrameMode: IntraFrameMode = IntraFrameMode.ALL_INTRA,
        recordWavSidecar: Boolean = true
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

        val width = maxOf(resolution.width, resolution.height)
        val height = minOf(resolution.width, resolution.height)
        activeColorProfile = colorProfile
        shouldRecordWavSidecar = recordWavSidecar

        val isHdr = colorProfile == ColorProfile.HLG_10BIT || colorProfile == ColorProfile.HDR10_PLUS
        isHlgRecording = isHdr
        val targetMime = if (isHdr) MediaFormat.MIMETYPE_VIDEO_HEVC else codec.mimeType

        val targetBitrate = if (intraFrameMode == IntraFrameMode.ALL_INTRA) {
            maxOf(bitrateMbps * 1_000_000, 200_000_000).coerceAtMost(280_000_000)
        } else {
            (bitrateMbps * 1_000_000).coerceIn(20_000_000, 280_000_000)
        }

        val iFrameInterval = if (intraFrameMode == IntraFrameMode.ALL_INTRA) 0 else 1
        val is422 = probeHasMain42210()
        val defaultProfile = if (is422) {
            HEVC_PROFILE_MAIN_422_10
        } else if (codec.is10Bit || isHdr) {
            MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10
        } else {
            MediaCodecInfo.CodecProfileLevel.HEVCProfileMain
        }

        val bitrateAttempts = if (intraFrameMode == IntraFrameMode.ALL_INTRA) {
            listOf(targetBitrate, 240_000_000, 200_000_000, 150_000_000)
        } else {
            listOf(targetBitrate, 200_000_000, 100_000_000)
        }

        var configured = false
        var lastEx: Exception? = null

        for (b in bitrateAttempts) {
            val profilesToTry = if (is422) {
                listOf(defaultProfile, MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10)
            } else {
                listOf(defaultProfile)
            }

            for (prof in profilesToTry) {
                try {
                    val videoFormat = createVideoFormat(
                        targetMime, width, height, fps, b, iFrameInterval, prof, colorProfile, intraFrameMode
                    )
                    val encoder = MediaCodec.createEncoderByType(targetMime)
                    encoder.configure(videoFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                    inputSurface = encoder.createInputSurface()
                    videoEncoder = encoder
                    configured = true
                    Log.i(TAG, "Configured video encoder: $targetMime, ${width}x${height}, ${fps}fps, ${b / 1_000_000}Mbps, prof=$prof, iFrameInt=$iFrameInterval")
                    break
                } catch (e: Exception) {
                    lastEx = e
                    videoEncoder?.release()
                    videoEncoder = null
                }
            }
            if (configured) break
        }

        if (!configured) {
            throw lastEx ?: IllegalStateException("Failed to configure video encoder")
        }

        // Configure Studio-grade Audio Encoder (AAC 48kHz Stereo 320kbps)
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

    private fun createVideoFormat(
        mime: String,
        width: Int,
        height: Int,
        fps: Int,
        bitrate: Int,
        iFrameInterval: Int,
        profile: Int,
        colorProfile: ColorProfile,
        intraFrameMode: IntraFrameMode
    ): MediaFormat {
        return MediaFormat.createVideoFormat(mime, width, height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
            setInteger(MediaFormat.KEY_FRAME_RATE, fps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, iFrameInterval)
            if (intraFrameMode == IntraFrameMode.ALL_INTRA) {
                setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR)
            }
            setLong(MediaFormat.KEY_REPEAT_PREVIOUS_FRAME_AFTER, 1_000_000L / fps)

            if (mime == MediaFormat.MIMETYPE_VIDEO_HEVC) {
                setInteger(MediaFormat.KEY_PROFILE, profile)
            } else if (mime == MediaFormat.MIMETYPE_VIDEO_AVC) {
                setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.AVCProfileHigh)
            }

            when (colorProfile) {
                ColorProfile.HLG_10BIT -> {
                    setInteger(MediaFormat.KEY_COLOR_STANDARD, MediaFormat.COLOR_STANDARD_BT2020)
                    setInteger(MediaFormat.KEY_COLOR_TRANSFER, MediaFormat.COLOR_TRANSFER_HLG)
                    setInteger(MediaFormat.KEY_COLOR_RANGE, MediaFormat.COLOR_RANGE_LIMITED)
                    try {
                        setFeatureEnabled(MediaCodecInfo.CodecCapabilities.FEATURE_HdrEditing, true)
                    } catch (_: Exception) {}
                }
                ColorProfile.HDR10_PLUS -> {
                    setInteger(MediaFormat.KEY_COLOR_STANDARD, MediaFormat.COLOR_STANDARD_BT2020)
                    setInteger(MediaFormat.KEY_COLOR_TRANSFER, MediaFormat.COLOR_TRANSFER_ST2084)
                    setInteger(MediaFormat.KEY_COLOR_RANGE, MediaFormat.COLOR_RANGE_LIMITED)
                    try {
                        setFeatureEnabled(MediaCodecInfo.CodecCapabilities.FEATURE_HdrEditing, true)
                    } catch (_: Exception) {}
                }
                else -> {
                    setInteger(MediaFormat.KEY_COLOR_STANDARD, MediaFormat.COLOR_STANDARD_BT709)
                    setInteger(MediaFormat.KEY_COLOR_RANGE, MediaFormat.COLOR_RANGE_FULL)
                }
            }
        }
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

        mediaMuxer = MediaMuxer(pfd.fileDescriptor, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4).apply {
            setOrientationHint(0)
        }

        // Setup simultaneous 48kHz uncompressed PCM .wav sidecar recording if enabled
        if (shouldRecordWavSidecar) {
            try {
                val wavFileName = "PIXELCINE_${timestamp}.wav"
                val wavValues = ContentValues().apply {
                    put(MediaStore.Audio.Media.DISPLAY_NAME, wavFileName)
                    put(MediaStore.Audio.Media.MIME_TYPE, "audio/wav")
                    put(MediaStore.Audio.Media.RELATIVE_PATH, "${Environment.DIRECTORY_MOVIES}/PixelCineRaw")
                    put(MediaStore.Audio.Media.IS_PENDING, 1)
                }
                val wUri = resolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, wavValues)
                if (wUri != null) {
                    wavUri = wUri
                    val wpfd = resolver.openFileDescriptor(wUri, "rw")
                    wavPfd = wpfd
                    if (wpfd != null) {
                        wavFileOutputStream = FileOutputStream(wpfd.fileDescriptor)
                        wavBytesWritten = 0L
                        writeWavHeader(wavFileOutputStream!!, AudioEngine.SAMPLE_RATE, 2, 16)
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to start WAV sidecar recording", e)
            }
        }

        videoEncoder?.start()
        audioEncoder?.start()

        isRecording.set(true)

        // Connect audio engine callback to feed PCM data to audio encoder and WAV sidecar
        audioEngine.onPcmDataAvailable = { buffer, bytesRead, _ ->
            if (isRecording.get()) {
                feedAudioEncoder(buffer, bytesRead)
                wavFileOutputStream?.let { wavOut ->
                    try {
                        wavOut.write(buffer, 0, bytesRead)
                        wavBytesWritten += bytesRead
                    } catch (e: Exception) {
                        Log.w(TAG, "Error writing PCM to WAV sidecar", e)
                    }
                }
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

    private fun writeWavHeader(out: FileOutputStream, sampleRate: Int, channels: Int, bitsPerSample: Int) {
        val header = ByteArray(44)
        val byteRate = sampleRate * channels * bitsPerSample / 8
        val blockAlign = channels * bitsPerSample / 8

        // RIFF/WAVE header
        header[0] = 'R'.code.toByte(); header[1] = 'I'.code.toByte(); header[2] = 'F'.code.toByte(); header[3] = 'F'.code.toByte()
        // File length - 8 filled later (bytes 4..7)
        header[8] = 'W'.code.toByte(); header[9] = 'A'.code.toByte(); header[10] = 'V'.code.toByte(); header[11] = 'E'.code.toByte()
        header[12] = 'f'.code.toByte(); header[13] = 'm'.code.toByte(); header[14] = 't'.code.toByte(); header[15] = ' '.code.toByte()
        header[16] = 16; header[17] = 0; header[18] = 0; header[19] = 0 // Subchunk1Size = 16
        header[20] = 1; header[21] = 0 // PCM = 1
        header[22] = channels.toByte(); header[23] = 0
        header[24] = (sampleRate and 0xff).toByte()
        header[25] = ((sampleRate shr 8) and 0xff).toByte()
        header[26] = ((sampleRate shr 16) and 0xff).toByte()
        header[27] = ((sampleRate shr 24) and 0xff).toByte()
        header[28] = (byteRate and 0xff).toByte()
        header[29] = ((byteRate shr 8) and 0xff).toByte()
        header[30] = ((byteRate shr 16) and 0xff).toByte()
        header[31] = ((byteRate shr 24) and 0xff).toByte()
        header[32] = blockAlign.toByte(); header[33] = 0
        header[34] = bitsPerSample.toByte(); header[35] = 0
        header[36] = 'd'.code.toByte(); header[37] = 'a'.code.toByte(); header[38] = 't'.code.toByte(); header[39] = 'a'.code.toByte()
        // Data length filled later (bytes 40..43)
        out.write(header, 0, 44)
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
                val format = vEncoder.outputFormat
                if (activeColorProfile == ColorProfile.HLG_10BIT) {
                    format.setInteger(MediaFormat.KEY_COLOR_STANDARD, MediaFormat.COLOR_STANDARD_BT2020)
                    format.setInteger(MediaFormat.KEY_COLOR_TRANSFER, MediaFormat.COLOR_TRANSFER_HLG)
                    format.setInteger(MediaFormat.KEY_COLOR_RANGE, MediaFormat.COLOR_RANGE_LIMITED)
                } else if (activeColorProfile == ColorProfile.HDR10_PLUS) {
                    format.setInteger(MediaFormat.KEY_COLOR_STANDARD, MediaFormat.COLOR_STANDARD_BT2020)
                    format.setInteger(MediaFormat.KEY_COLOR_TRANSFER, MediaFormat.COLOR_TRANSFER_ST2084)
                    format.setInteger(MediaFormat.KEY_COLOR_RANGE, MediaFormat.COLOR_RANGE_LIMITED)
                }
                videoTrackIndex = muxer.addTrack(format)
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
     * Stops recording and finalizes the MP4 file and WAV sidecar in MediaStore.
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

        // Finalize WAV sidecar file header with exact length
        try {
            wavFileOutputStream?.flush()
            val wpfd = wavPfd
            if (wpfd != null) {
                val channel = FileOutputStream(wpfd.fileDescriptor).channel
                val buf = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN)
                buf.putInt((wavBytesWritten + 36).toInt())
                buf.flip()
                channel.position(4)
                channel.write(buf)

                buf.clear()
                buf.putInt(wavBytesWritten.toInt())
                buf.flip()
                channel.position(40)
                channel.write(buf)
                channel.close()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error finalizing WAV sidecar", e)
        } finally {
            try { wavFileOutputStream?.close() } catch (_: Exception) {}
            wavFileOutputStream = null
            try { wavPfd?.close() } catch (_: Exception) {}
            wavPfd = null

            wavUri?.let { uri ->
                val vals = ContentValues().apply {
                    put(MediaStore.Audio.Media.IS_PENDING, 0)
                }
                context.contentResolver.update(uri, vals, null, null)
            }
            wavUri = null
        }

        // Mark MP4 file as complete in MediaStore (remove IS_PENDING)
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
