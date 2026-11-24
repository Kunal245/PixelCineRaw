package com.kunal.pixelcineraw.camera

import android.app.Application
import android.content.ContentUris
import android.os.Build
import android.provider.MediaStore
import android.util.Log
import android.view.Surface
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.kunal.pixelcineraw.audio.AudioEngine
import com.kunal.pixelcineraw.raw.RawDngRecorder
import com.kunal.pixelcineraw.video.HevcLogRecorder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * ViewModel managing the cinema camera lifecycle, manual controls, monitoring telemetry,
 * 10-bit HEVC Log video recording, and RAW Bayer DNG capture.
 */
class CinemaViewModel(application: Application) : AndroidViewModel(application) {

    companion object {
        private const val TAG = "CinemaViewModel"
    }

    val cameraController = Camera2Controller(application.applicationContext, viewModelScope)
    val state: StateFlow<CinemaState> = cameraController.state

    val audioEngine = AudioEngine(application.applicationContext) { peakL, peakR ->
        cameraController.updateAudioLevels(peakL, peakR)
    }

    val hevcRecorder = HevcLogRecorder(application.applicationContext, audioEngine)
    val rawDngRecorder = RawDngRecorder(application.applicationContext)

    private val telemetryMonitor = SystemTelemetryMonitor(application.applicationContext) { battery, storageGb, thermal, mic ->
        cameraController.updateTelemetry(battery, storageGb, thermal, mic)
    }

    init {
        audioEngine.start()
        telemetryMonitor.start()

        val initialBadge = HevcLogRecorder.getChromaBadge(cameraController.state.value.intraFrameMode)
        cameraController.setEncoderChromaBadge(initialBadge)

        hevcRecorder.onMetricsUpdate = { secs, bytes, dropped ->
            cameraController.updateRecordingMetrics(secs, bytes, dropped)
        }

        rawDngRecorder.onMetricsUpdate = { secs, bytes, dropped ->
            cameraController.updateRecordingMetrics(secs, bytes, dropped)
        }

        loadLatestClipThumbnail()
    }

    fun loadLatestClipThumbnail() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val projection = arrayOf(
                    MediaStore.Video.Media._ID,
                    MediaStore.Video.Media.DATE_ADDED
                )
                val selection = "${MediaStore.Video.Media.RELATIVE_PATH} LIKE ?"
                val selectionArgs = arrayOf("%Movies/PixelCineRaw%")
                val sortOrder = "${MediaStore.Video.Media.DATE_ADDED} DESC"

                val resolver = getApplication<Application>().contentResolver
                val queryUri = MediaStore.Video.Media.EXTERNAL_CONTENT_URI

                resolver.query(queryUri, projection, selection, selectionArgs, sortOrder)?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
                        val id = cursor.getLong(idColumn)
                        val contentUri = ContentUris.withAppendedId(queryUri, id)

                        val thumbnail = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                            try {
                                resolver.loadThumbnail(contentUri, android.util.Size(160, 160), null)
                            } catch (e: Exception) {
                                null
                            }
                        } else null

                        cameraController.setLastRecordedClip(contentUri.toString(), thumbnail)
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to load latest clip thumbnail", e)
            }
        }
    }

    fun attachPreviewSurface(surface: Surface) {
        cameraController.attachPreviewSurface(surface)
    }

    fun detachPreviewSurface() {
        cameraController.detachPreviewSurface()
    }

    /**
     * Toggles recording between 10-bit HEVC Log mode and RAW Bayer DNG sequence mode.
     */
    fun toggleRecording() {
        val currentState = state.value
        if (currentState.isRecording) {
            stopRecording(currentState.recordingFormat)
        } else {
            startRecording(currentState)
        }
    }

    private fun startRecording(state: CinemaState) {
        try {
            when (state.recordingFormat) {
                RecordingFormat.VIDEO_PRO -> {
                    val rotation = cameraController.getDisplayRotation()
                    val isPortrait = rotation == android.view.Surface.ROTATION_0 || rotation == android.view.Surface.ROTATION_180 ||
                            state.orientationLockMode == OrientationLockMode.LOCK_PORTRAIT

                    // Prepare hardware video encoder and get recording surface
                    val surface = hevcRecorder.prepare(
                        resolution = state.videoResolution,
                        fps = state.targetFps,
                        bitrateMbps = state.targetBitrateMbps,
                        colorProfile = state.colorProfile,
                        codec = state.videoCodec,
                        intraFrameMode = state.intraFrameMode,
                        recordWavSidecar = state.recordWavSidecar,
                        isPortrait = isPortrait
                    )

                    cameraController.onFirstEncoderFrame = { startNs ->
                        hevcRecorder.onFirstVideoFrame(startNs)
                    }

                    val fileName = hevcRecorder.start()

                    cameraController.startRecordingSession(surface, fileName, isPortrait) {
                        Log.i(TAG, "Camera2 session active and streaming to encoder: $fileName (isPortrait=$isPortrait)")
                    }
                }
                RecordingFormat.RAW_DNG -> {
                    val spec = state.currentSensorSpec ?: return
                    val chars = cameraController.getCameraCharacteristics(spec.cameraId)

                    cameraController.onRawFrameAvailable = { image, result ->
                        rawDngRecorder.enqueueRawFrame(image, result)
                    }

                    val sessionFolder = rawDngRecorder.start(chars)
                    cameraController.setRecordingActive(true, sessionFolder)
                    Log.i(TAG, "Started RAW DNG stream recording to $sessionFolder")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start recording", e)
            cameraController.setRecordingActive(false)
        }
    }

    private fun stopRecording(format: RecordingFormat) {
        try {
            when (format) {
                RecordingFormat.VIDEO_PRO -> {
                    cameraController.onFirstEncoderFrame = null
                    cameraController.stopRecordingSession {
                        Log.i(TAG, "Camera2 session restored to clean preview mode")
                    }
                    hevcRecorder.stop()
                    Log.i(TAG, "Stopped video recording successfully")
                    viewModelScope.launch {
                        delay(600L)
                        loadLatestClipThumbnail()
                    }
                }
                RecordingFormat.RAW_DNG -> {
                    cameraController.onRawFrameAvailable = null
                    rawDngRecorder.stop()
                    cameraController.setRecordingActive(false)
                    Log.i(TAG, "Stopped RAW DNG recording successfully")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping recording", e)
            cameraController.setRecordingActive(false)
        }
    }

    override fun onCleared() {
        super.onCleared()
        if (state.value.isRecording) {
            stopRecording(state.value.recordingFormat)
        }
        audioEngine.stop()
        telemetryMonitor.stop()
        cameraController.closeCamera()
        cameraController.stopBackgroundThread()
    }
}
