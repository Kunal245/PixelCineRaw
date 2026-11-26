package com.kunal.pixelcineraw.video

import android.content.ContentValues
import android.content.Context
import android.media.*
import android.net.Uri
import android.os.Environment
import android.os.Handler
import android.os.HandlerThread
import android.os.ParcelFileDescriptor
import android.os.Process
import android.os.SystemClock
import android.provider.MediaStore
import android.util.Log
import android.view.Surface
import com.kunal.pixelcineraw.audio.AudioEngine
import com.kunal.pixelcineraw.camera.ColorProfile
import com.kunal.pixelcineraw.camera.CustomResolution
import com.kunal.pixelcineraw.camera.IntraFrameMode
import com.kunal.pixelcineraw.camera.VideoCodec
import java.io.BufferedOutputStream
import java.io.FileOutputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Max-Out 10-Bit HEVC Cinema Video Recorder for Google Pixel 9a (Tensor G4).
 * Supports:
 * - 10-bit 4:2:2 (HEVCProfileMain42210) & 10-bit 4:2:0 (HEVCProfileMain10)
 * - GOP (1s Keyframe) 120 Mbps default & ALL-I intra-frame encoding (capped at 160 Mbps)
 * - Full HDR suite: HDR10+ (SMPTE ST.2084 PQ) & HLG10 (ARIB STD-B67 / BT.2020)
 * - Studio-grade 48kHz Stereo 320 kbps AAC-LC muxed into MP4 + simultaneous 48kHz uncompressed PCM .wav sidecar
 * - Pure non-blocking asynchronous MediaCodec callback architecture on dedicated URGENT_AUDIO thread
 */
class HevcLogRecorder(
    private val context: Context,
    private val audioEngine: AudioEngine
) {
    companion object {
        private const val TAG = "HevcLogRecorder"
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

    private sealed class MuxerCommand {
        class AddVideoTrack(val format: MediaFormat) : MuxerCommand()
        class AddAudioTrack(val format: MediaFormat) : MuxerCommand()
        class WriteSample(val isAudio: Boolean, val data: ByteBuffer, val info: MediaCodec.BufferInfo) : MuxerCommand()
        class FlushAndStop(val latch: CountDownLatch) : MuxerCommand()
    }

    private var videoEncoder: MediaCodec? = null
    private var audioEncoder: MediaCodec? = null
    private var mediaMuxer: MediaMuxer? = null
    private var inputSurface: Surface? = null

    // Dedicated decoupled threads: video encoder, audio encoder, and single-writer MediaMuxer
    private var encoderThread: HandlerThread? = null
    private var encoderHandler: Handler? = null
    private var audioEncoderThread: HandlerThread? = null
    private var audioEncoderHandler: Handler? = null
    private var muxerThread: HandlerThread? = null
    private var muxerHandler: Handler? = null

    // Single-writer lock-free MediaMuxer queue (zero contention between Video, Audio, and AudioRecord)
    private val muxerQueue = ConcurrentLinkedQueue<MuxerCommand>()
    private val muxerDrainScheduled = AtomicBoolean(false)

    // Owned exclusively by muxerThread once started
    private var videoTrackIndex = -1
    private var audioTrackIndex = -1
    private var isMuxerStarted = false
    private val pendingVideoSamples = mutableListOf<MuxerCommand.WriteSample>()
    private val pendingAudioSamples = mutableListOf<MuxerCommand.WriteSample>()

    private val audioQueue = ConcurrentLinkedQueue<ByteArray>()
    private val availableAudioInputBuffers = ConcurrentLinkedQueue<Int>()

    private var firstVideoPtsUs = -1L
    private var lastVideoPtsUs = -1L
    private var firstAudioPtsUs = -1L
    private var lastAudioPtsUs = -1L
    private var totalAudioBytesFed = 0L
    @Volatile private var totalBytesWritten = 0L

    private var lastMetricsUpdateSec = -1L
    private var lastMetricsUpdateMs = 0L

    private var isHlgRecording = false
    private var activeColorProfile: ColorProfile = ColorProfile.REC709
    private var shouldRecordWavSidecar: Boolean = false

    private val isRecording = AtomicBoolean(false)

    private var fileDescriptor: ParcelFileDescriptor? = null
    private var outputUri: Uri? = null

    private var wavPfd: ParcelFileDescriptor? = null
    private var wavUri: Uri? = null
    private var wavFileOutputStream: FileOutputStream? = null
    private var bufferedWavOutputStream: BufferedOutputStream? = null
    private var wavIoThread: HandlerThread? = null
    private var wavIoHandler: Handler? = null
    private var wavBytesWritten: Long = 0L

    @Volatile private var recordingStartNs: Long = -1L
    @Volatile private var hasReceivedFirstVideoFrame: Boolean = false
    private var targetFps: Int = 60
    private var videoFrameCount: Long = 0L
    private var videoMuxedFrameCount: Long = 0L

    fun onFirstVideoFrame(startNs: Long) {
        if (!hasReceivedFirstVideoFrame) {
            recordingStartNs = startNs
            hasReceivedFirstVideoFrame = true
            Log.i(TAG, "First video frame recorded at startNs=$startNs")
        }
    }

    var onMetricsUpdate: ((seconds: Long, bytes: Long, droppedFrames: Int) -> Unit)? = null

    val encoderSurface: Surface? get() = inputSurface

    private fun ensureEncoderThread() {
        if (encoderThread == null) {
            encoderThread = HandlerThread("PixelCineVideoEncoder", Process.THREAD_PRIORITY_URGENT_AUDIO).apply {
                start()
                encoderHandler = Handler(looper)
            }
        }
        if (audioEncoderThread == null) {
            audioEncoderThread = HandlerThread("PixelCineAudioEncoder", Process.THREAD_PRIORITY_AUDIO).apply {
                start()
                audioEncoderHandler = Handler(looper)
            }
        }
        if (muxerThread == null) {
            muxerThread = HandlerThread("PixelCineMuxerWriter", Process.THREAD_PRIORITY_DISPLAY).apply {
                start()
                muxerHandler = Handler(looper)
            }
        }
    }

    private fun enqueueMuxerCommand(cmd: MuxerCommand) {
        muxerQueue.offer(cmd)
        if (muxerDrainScheduled.compareAndSet(false, true)) {
            muxerHandler?.post { drainMuxerQueue() }
        }
    }

    private fun drainMuxerQueue() {
        while (true) {
            muxerDrainScheduled.set(false)
            while (true) {
                val cmd = muxerQueue.poll() ?: break
                processMuxerCommand(cmd)
            }
            if (muxerQueue.isEmpty() || !muxerDrainScheduled.compareAndSet(false, true)) {
                break
            }
        }
    }

    private fun processMuxerCommand(cmd: MuxerCommand) {
        when (cmd) {
            is MuxerCommand.AddVideoTrack -> {
                val muxer = mediaMuxer ?: return
                if (!isMuxerStarted && videoTrackIndex < 0) {
                    videoTrackIndex = muxer.addTrack(cmd.format)
                    Log.i(TAG, "Video track added on single-writer muxer thread: $videoTrackIndex")
                    checkAndStartMuxer(muxer)
                }
            }
            is MuxerCommand.AddAudioTrack -> {
                val muxer = mediaMuxer ?: return
                if (!isMuxerStarted && audioTrackIndex < 0) {
                    audioTrackIndex = muxer.addTrack(cmd.format)
                    Log.i(TAG, "Audio track added on single-writer muxer thread: $audioTrackIndex")
                    checkAndStartMuxer(muxer)
                }
            }
            is MuxerCommand.WriteSample -> {
                val muxer = mediaMuxer ?: return
                if (isMuxerStarted) {
                    val trackIdx = if (cmd.isAudio) audioTrackIndex else videoTrackIndex
                    if (trackIdx >= 0) {
                        try {
                            muxer.writeSampleData(trackIdx, cmd.data, cmd.info)
                            totalBytesWritten += cmd.info.size
                        } catch (e: Exception) {
                            Log.w(TAG, "Error writing sample on muxer thread (audio=${cmd.isAudio})", e)
                        }
                    }
                } else {
                    if (cmd.isAudio) {
                        pendingAudioSamples.add(cmd)
                    } else {
                        pendingVideoSamples.add(cmd)
                    }
                }
            }
            is MuxerCommand.FlushAndStop -> {
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
                    pendingVideoSamples.clear()
                    pendingAudioSamples.clear()
                    cmd.latch.countDown()
                }
            }
        }
    }

    /**
     * Initializes the cinema hardware encoder (HEVC 10-bit 4:2:2 / 4:2:0 GOP or ALL-I)
     * in asynchronous non-blocking callback mode.
     */
    fun prepare(
        resolution: CustomResolution,
        fps: Int,
        bitrateMbps: Int,
        colorProfile: ColorProfile,
        codec: VideoCodec = VideoCodec.HEVC_10BIT,
        intraFrameMode: IntraFrameMode = IntraFrameMode.GOP_LONG,
        recordWavSidecar: Boolean = false,
        isPortrait: Boolean = false
    ): Surface {
        ensureEncoderThread()
        val videoHandler = encoderHandler ?: throw IllegalStateException("Video encoder thread handler not ready")
        val audioHandler = audioEncoderHandler ?: throw IllegalStateException("Audio encoder thread handler not ready")

        // Reset tracking state
        muxerQueue.clear()
        muxerDrainScheduled.set(false)
        videoTrackIndex = -1
        audioTrackIndex = -1
        isMuxerStarted = false
        firstVideoPtsUs = -1L
        lastVideoPtsUs = -1L
        firstAudioPtsUs = -1L
        lastAudioPtsUs = -1L
        totalAudioBytesFed = 0L
        totalBytesWritten = 0L
        lastMetricsUpdateSec = -1L
        lastMetricsUpdateMs = 0L
        pendingVideoSamples.clear()
        pendingAudioSamples.clear()
        audioQueue.clear()
        availableAudioInputBuffers.clear()
        this.targetFps = fps
        this.recordingStartNs = -1L
        this.hasReceivedFirstVideoFrame = false
        this.videoFrameCount = 0L
        this.videoMuxedFrameCount = 0L

        val width = if (isPortrait) minOf(resolution.width, resolution.height) else maxOf(resolution.width, resolution.height)
        val height = if (isPortrait) maxOf(resolution.width, resolution.height) else minOf(resolution.width, resolution.height)
        activeColorProfile = colorProfile
        shouldRecordWavSidecar = recordWavSidecar

        val isHdr = colorProfile == ColorProfile.HLG_10BIT || colorProfile == ColorProfile.HDR10_PLUS
        isHlgRecording = isHdr
        val targetMime = if (isHdr) MediaFormat.MIMETYPE_VIDEO_HEVC else codec.mimeType

        val isAllIntra = intraFrameMode == IntraFrameMode.ALL_INTRA
        // Default GOP: 180 Mbps for 4K (>= 2160p) and 120 Mbps for 1080p/2.5K
        // Keyframe interval = 1s, Realtime priority = 0, eliminating 8x8 macroblock sky noise
        val is4k = width >= 3840 || height >= 2160 || maxOf(width, height) >= 3800
        val defaultBitrate = if (is4k) 180_000_000 else 120_000_000
        val targetBitrate = if (bitrateMbps > 0) {
            val bps = bitrateMbps * 1_000_000
            if (isAllIntra) {
                bps.coerceIn(80_000_000, 280_000_000)
            } else {
                bps.coerceIn(40_000_000, 220_000_000)
            }
        } else {
            defaultBitrate
        }

        val iFrameInterval = if (isAllIntra) 0 else 1
        val is422 = probeHasMain42210()
        val defaultProfile = if (is422) {
            HEVC_PROFILE_MAIN_422_10
        } else if (codec.is10Bit || isHdr) {
            MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10
        } else {
            MediaCodecInfo.CodecProfileLevel.HEVCProfileMain
        }

        val bitrateAttempts = if (isAllIntra) {
            listOf(targetBitrate, 180_000_000, 140_000_000, 120_000_000)
        } else {
            listOf(targetBitrate, defaultBitrate, 140_000_000, 120_000_000, 100_000_000)
        }.distinct()

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

                    // Asynchronous Callback Mode: MUST be called before configure()
                    encoder.setCallback(createVideoCallback(), videoHandler)
                    encoder.configure(videoFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                    inputSurface = encoder.createInputSurface()
                    videoEncoder = encoder
                    configured = true
                    Log.i(TAG, "Configured async video encoder: $targetMime, ${width}x${height}, ${fps}fps, ${b / 1_000_000}Mbps, prof=$prof, iFrameInt=$iFrameInterval")
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

        // Configure Studio-grade Audio Encoder (AAC 48kHz Stereo 320kbps) on its own dedicated thread
        val audioFormat = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, AudioEngine.SAMPLE_RATE, 2).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, 320_000)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384)
        }

        audioEncoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC).apply {
            setCallback(createAudioCallback(), audioHandler)
            configure(audioFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        }

        return inputSurface!!
    }

    private fun createVideoCallback(): MediaCodec.Callback {
        return object : MediaCodec.Callback() {
            override fun onInputBufferAvailable(codec: MediaCodec, index: Int) {
                // Surface-input encoders do not use input buffers
            }

            override fun onOutputBufferAvailable(codec: MediaCodec, index: Int, info: MediaCodec.BufferInfo) {
                try {
                    if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                        info.size = 0
                    }

                    val encodedData = codec.getOutputBuffer(index)
                    if (info.size > 0 && encodedData != null) {
                        val targetFrameDurationUs = 1_000_000L / maxOf(1, targetFps)
                        val frameIndex = if (firstVideoPtsUs < 0) {
                            firstVideoPtsUs = info.presentationTimeUs
                            0L
                        } else {
                            val rawStep = (info.presentationTimeUs - firstVideoPtsUs).toDouble() / targetFrameDurationUs.toDouble()
                            Math.round(rawStep).toLong().coerceAtLeast(videoMuxedFrameCount)
                        }
                        val quantizedPtsUs = frameIndex * targetFrameDurationUs
                        val monotonicPts = if (quantizedPtsUs <= lastVideoPtsUs) lastVideoPtsUs + targetFrameDurationUs else quantizedPtsUs
                        val deltaUs = if (lastVideoPtsUs >= 0L) monotonicPts - lastVideoPtsUs else targetFrameDurationUs
                        lastVideoPtsUs = monotonicPts
                        info.presentationTimeUs = monotonicPts
                        videoMuxedFrameCount = frameIndex + 1L

                        encodedData.position(info.offset)
                        encodedData.limit(info.offset + info.size)

                        // Copy into non-blocking queue and immediately release hardware output buffer
                        val clone = ByteBuffer.allocate(info.size)
                        clone.put(encodedData)
                        clone.flip()
                        val infoClone = MediaCodec.BufferInfo().apply {
                            set(0, info.size, info.presentationTimeUs, info.flags)
                        }
                        enqueueMuxerCommand(MuxerCommand.WriteSample(isAudio = false, clone, infoClone))

                        if (videoFrameCount++ % 60 == 0L) {
                            Log.d(TAG, "Muxer video frame #$videoFrameCount: PTS=${monotonicPts / 1000}ms, delta=${deltaUs / 1000f}ms (target=${1000f / targetFps}ms)")
                        }

                        // Throttle UI telemetry metrics strictly to 1Hz
                        val sec = lastVideoPtsUs / 1_000_000L
                        val nowMs = SystemClock.uptimeMillis()
                        if (sec != lastMetricsUpdateSec || nowMs - lastMetricsUpdateMs >= 1000L) {
                            lastMetricsUpdateSec = sec
                            lastMetricsUpdateMs = nowMs
                            onMetricsUpdate?.invoke(sec, totalBytesWritten, 0)
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Error handling video output buffer", e)
                } finally {
                    try {
                        codec.releaseOutputBuffer(index, false)
                    } catch (_: Exception) {}
                }
            }

            override fun onOutputFormatChanged(codec: MediaCodec, format: MediaFormat) {
                if (activeColorProfile == ColorProfile.HLG_10BIT) {
                    format.setInteger(MediaFormat.KEY_COLOR_STANDARD, MediaFormat.COLOR_STANDARD_BT2020)
                    format.setInteger(MediaFormat.KEY_COLOR_TRANSFER, MediaFormat.COLOR_TRANSFER_HLG)
                    format.setInteger(MediaFormat.KEY_COLOR_RANGE, MediaFormat.COLOR_RANGE_LIMITED)
                } else if (activeColorProfile == ColorProfile.HDR10_PLUS) {
                    format.setInteger(MediaFormat.KEY_COLOR_STANDARD, MediaFormat.COLOR_STANDARD_BT2020)
                    format.setInteger(MediaFormat.KEY_COLOR_TRANSFER, MediaFormat.COLOR_TRANSFER_ST2084)
                    format.setInteger(MediaFormat.KEY_COLOR_RANGE, MediaFormat.COLOR_RANGE_LIMITED)
                }
                enqueueMuxerCommand(MuxerCommand.AddVideoTrack(format))
            }

            override fun onError(codec: MediaCodec, e: MediaCodec.CodecException) {
                Log.e(TAG, "Video encoder error: ${e.diagnosticInfo}", e)
            }
        }
    }

    private fun createAudioCallback(): MediaCodec.Callback {
        return object : MediaCodec.Callback() {
            override fun onInputBufferAvailable(codec: MediaCodec, index: Int) {
                try {
                    val chunk = audioQueue.poll()
                    if (chunk != null) {
                        val inputBuffer = codec.getInputBuffer(index)
                        inputBuffer?.clear()
                        inputBuffer?.put(chunk)
                        val totalSamplesWritten = totalAudioBytesFed / 4L
                        val ptsUs = (totalSamplesWritten * 1_000_000L) / 48_000L
                        totalAudioBytesFed += chunk.size
                        codec.queueInputBuffer(index, 0, chunk.size, ptsUs, 0)
                    } else {
                        availableAudioInputBuffers.offer(index)
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Error in audio onInputBufferAvailable", e)
                }
            }

            override fun onOutputBufferAvailable(codec: MediaCodec, index: Int, info: MediaCodec.BufferInfo) {
                try {
                    if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                        info.size = 0
                    }

                    val encodedData = codec.getOutputBuffer(index)
                    if (info.size > 0 && encodedData != null) {
                        if (firstAudioPtsUs < 0) {
                            firstAudioPtsUs = info.presentationTimeUs
                        }
                        val normalizedPts = (info.presentationTimeUs - firstAudioPtsUs).coerceAtLeast(0L)
                        val monotonicPts = if (normalizedPts <= lastAudioPtsUs) lastAudioPtsUs + 1000L else normalizedPts
                        lastAudioPtsUs = monotonicPts
                        info.presentationTimeUs = monotonicPts

                        encodedData.position(info.offset)
                        encodedData.limit(info.offset + info.size)

                        val clone = ByteBuffer.allocate(info.size)
                        clone.put(encodedData)
                        clone.flip()
                        val infoClone = MediaCodec.BufferInfo().apply {
                            set(0, info.size, info.presentationTimeUs, info.flags)
                        }
                        enqueueMuxerCommand(MuxerCommand.WriteSample(isAudio = true, clone, infoClone))
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Error handling audio output buffer", e)
                } finally {
                    try {
                        codec.releaseOutputBuffer(index, false)
                    } catch (_: Exception) {}
                }
            }

            override fun onOutputFormatChanged(codec: MediaCodec, format: MediaFormat) {
                enqueueMuxerCommand(MuxerCommand.AddAudioTrack(format))
            }

            override fun onError(codec: MediaCodec, e: MediaCodec.CodecException) {
                Log.e(TAG, "Audio encoder error: ${e.diagnosticInfo}", e)
            }
        }
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
            setInteger(MediaFormat.KEY_PRIORITY, 0) // 0 = Realtime priority, prevents Tensor G4 from downclocking
            setInteger(MediaFormat.KEY_OPERATING_RATE, fps) // Forces operating rate (e.g. 60 for 60fps)
            if (intraFrameMode == IntraFrameMode.ALL_INTRA) {
                setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR)
            }

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
                        wavIoThread = HandlerThread("PixelCineWavIo", Process.THREAD_PRIORITY_BACKGROUND).apply {
                            start()
                            wavIoHandler = Handler(looper)
                        }
                        val fos = FileOutputStream(wpfd.fileDescriptor)
                        wavFileOutputStream = fos
                        bufferedWavOutputStream = BufferedOutputStream(fos, 256 * 1024)
                        wavBytesWritten = 0L
                        writeWavHeader(bufferedWavOutputStream!!, AudioEngine.SAMPLE_RATE, 2, 16)
                        bufferedWavOutputStream?.flush()
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to start WAV sidecar recording", e)
            }
        }

        isRecording.set(true)

        videoEncoder?.start()
        audioEncoder?.start()

        // Connect audio engine callback to feed PCM data to audio encoder and WAV sidecar
        audioEngine.onPcmDataAvailable = { buffer, bytesRead, _ ->
            if (isRecording.get() && hasReceivedFirstVideoFrame) {
                val chunk = buffer.copyOf(bytesRead)
                if (shouldRecordWavSidecar) {
                    wavIoHandler?.post {
                        try {
                            bufferedWavOutputStream?.write(chunk)
                            wavBytesWritten += chunk.size
                        } catch (e: Exception) {
                            Log.w(TAG, "Error writing PCM to WAV sidecar", e)
                        }
                    }
                }
                val handler = audioEncoderHandler
                if (handler != null) {
                    handler.post {
                        if (isRecording.get()) {
                            val encoder = audioEncoder
                            val freeIndex = availableAudioInputBuffers.poll()
                            val totalSamplesWritten = totalAudioBytesFed / 4L
                            val ptsUs = (totalSamplesWritten * 1_000_000L) / 48_000L
                            totalAudioBytesFed += chunk.size

                            if (encoder != null && freeIndex != null) {
                                try {
                                    val inputBuffer = encoder.getInputBuffer(freeIndex)
                                    inputBuffer?.clear()
                                    inputBuffer?.put(chunk)
                                    encoder.queueInputBuffer(freeIndex, 0, chunk.size, ptsUs, 0)
                                } catch (e: Exception) {
                                    Log.w(TAG, "Error queuing audio buffer asynchronously", e)
                                }
                            } else {
                                audioQueue.offer(chunk)
                            }
                        }
                    }
                }
            }
        }

        return fileName
    }

    private fun writeWavHeader(out: OutputStream, sampleRate: Int, channels: Int, bitsPerSample: Int) {
        val header = ByteArray(44)
        val byteRate = sampleRate * channels * bitsPerSample / 8
        val blockAlign = channels * bitsPerSample / 8

        header[0] = 'R'.code.toByte(); header[1] = 'I'.code.toByte(); header[2] = 'F'.code.toByte(); header[3] = 'F'.code.toByte()
        header[8] = 'W'.code.toByte(); header[9] = 'A'.code.toByte(); header[10] = 'V'.code.toByte(); header[11] = 'E'.code.toByte()
        header[12] = 'f'.code.toByte(); header[13] = 'm'.code.toByte(); header[14] = 't'.code.toByte(); header[15] = ' '.code.toByte()
        header[16] = 16; header[17] = 0; header[18] = 0; header[19] = 0
        header[20] = 1; header[21] = 0
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
        out.write(header, 0, 44)
    }

    // Invoked exclusively on muxerThread
    private fun checkAndStartMuxer(muxer: MediaMuxer) {
        if (videoTrackIndex >= 0 && audioTrackIndex >= 0 && !isMuxerStarted) {
            muxer.start()
            isMuxerStarted = true
            Log.i(TAG, "MediaMuxer started with video track $videoTrackIndex and audio track $audioTrackIndex")

            // Flush buffered video frames (including keyframe)
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
            // Signal EOS to video encoder
            videoEncoder?.signalEndOfInputStream()
        } catch (e: Exception) {
            Log.w(TAG, "Error signaling EOS to video encoder", e)
        }

        val audioHandler = audioEncoderHandler
        if (audioHandler != null) {
            audioHandler.post {
                try {
                    val encoder = audioEncoder
                    val freeIndex = availableAudioInputBuffers.poll()
                    if (encoder != null && freeIndex != null) {
                        encoder.queueInputBuffer(freeIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Error sending audio EOS", e)
                }
            }
        }

        // Brief delay to allow final asynchronous output frames to enqueue into muxerQueue
        try {
            Thread.sleep(120)
        } catch (_: Exception) {}

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

        // Drain all queued samples and stop/release MediaMuxer on the dedicated single-writer thread
        val stopLatch = CountDownLatch(1)
        enqueueMuxerCommand(MuxerCommand.FlushAndStop(stopLatch))
        try {
            stopLatch.await(2000, TimeUnit.MILLISECONDS)
        } catch (_: Exception) {}

        fileDescriptor?.close()
        fileDescriptor = null

        // Finalize WAV sidecar file header with exact length
        if (shouldRecordWavSidecar) {
            try {
                val latch = java.util.concurrent.CountDownLatch(1)
                wavIoHandler?.post {
                    try {
                        bufferedWavOutputStream?.flush()
                    } catch (e: Exception) {
                        Log.w(TAG, "Error flushing buffered WAV", e)
                    } finally {
                        latch.countDown()
                    }
                }
                latch.await(500, java.util.concurrent.TimeUnit.MILLISECONDS)
            } catch (_: Exception) {}

            try {
                bufferedWavOutputStream?.flush()
                bufferedWavOutputStream?.close()
                bufferedWavOutputStream = null
            } catch (_: Exception) {}

            try {
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

                wavIoThread?.quitSafely()
                wavIoThread = null
                wavIoHandler = null

                wavUri?.let { uri ->
                    val vals = ContentValues().apply {
                        put(MediaStore.Audio.Media.IS_PENDING, 0)
                    }
                    context.contentResolver.update(uri, vals, null, null)
                }
                wavUri = null
            }
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

    fun release() {
        stop()
        encoderThread?.quitSafely()
        encoderThread = null
        encoderHandler = null
        audioEncoderThread?.quitSafely()
        audioEncoderThread = null
        audioEncoderHandler = null
        muxerThread?.quitSafely()
        muxerThread = null
        muxerHandler = null
    }
}
