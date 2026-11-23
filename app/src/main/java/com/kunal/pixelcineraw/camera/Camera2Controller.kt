package com.kunal.pixelcineraw.camera

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.SurfaceTexture
import android.hardware.camera2.*
import android.hardware.camera2.params.MeteringRectangle
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.hardware.camera2.params.TonemapCurve
import android.media.Image
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.util.Range
import android.util.Size
import android.view.Surface
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.concurrent.Executors
import kotlin.math.max
import kotlin.math.min

/**
 * High-performance Camera2 Controller providing low-level hardware control
 * over Google Pixel 9a (Tensor G4) camera sensors.
 */
class Camera2Controller(
    private val context: Context,
    private val scope: CoroutineScope
) {
    companion object {
        private const val TAG = "PixelCineCam2"
        private const val FOCUS_AREA_SIZE = 150
    }

    private val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager

    // Background Threading for deterministic Camera2 loop
    private var cameraThread: HandlerThread? = null
    private var cameraHandler: Handler? = null

    // Probed Sensors
    private val _probedSensors = mutableListOf<CameraSensorSpec>()
    val probedSensors: List<CameraSensorSpec> get() = _probedSensors

    // Active Camera Objects
    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var previewRequestBuilder: CaptureRequest.Builder? = null
    private var previewSurface: Surface? = null
    private var videoRecordingSurface: Surface? = null
    private var rawImageReader: ImageReader? = null
    var lastCaptureResult: TotalCaptureResult? = null
        private set
    var onRawFrameAvailable: ((Image, TotalCaptureResult) -> Unit)? = null

    // State flow
    private val _state = MutableStateFlow(CinemaState())
    val state: StateFlow<CinemaState> = _state.asStateFlow()

    // FPS Metering
    private var frameCount = 0
    private var lastFpsTimestamp = System.nanoTime()

    init {
        startBackgroundThread()
        probeSensors()
    }

    private fun startBackgroundThread() {
        if (cameraThread == null) {
            cameraThread = HandlerThread("PixelCineCamera").apply {
                start()
                cameraHandler = Handler(looper)
            }
        }
    }

    fun stopBackgroundThread() {
        cameraThread?.quitSafely()
        try {
            cameraThread?.join(1000)
            cameraThread = null
            cameraHandler = null
        } catch (e: InterruptedException) {
            Log.e(TAG, "Error stopping camera thread", e)
        }
    }

    /**
     * Probes all camera sensors on the device, specifically tuning for Pixel 9a's
     * 48MP main sensor (1x), 13MP ultrawide (0.5x), and 2x crop capability.
     */
    fun probeSensors() {
        _probedSensors.clear()
        try {
            val cameraIds = cameraManager.cameraIdList
            var mainWideSpec: CameraSensorSpec? = null
            var ultraWideSpec: CameraSensorSpec? = null

            for (id in cameraIds) {
                val chars = cameraManager.getCameraCharacteristics(id)
                val facing = chars.get(CameraCharacteristics.LENS_FACING) ?: continue
                if (facing != CameraCharacteristics.LENS_FACING_BACK) continue

                val focalLengths = chars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS) ?: floatArrayOf(0f)
                val apertures = chars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_APERTURES) ?: floatArrayOf(0f)
                val isoRange = chars.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE) ?: Range(100, 3200)
                val exposureRange = chars.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE) ?: Range(100_000L, 1_000_000_000L)
                val maxAnalogIso = chars.get(CameraCharacteristics.SENSOR_MAX_ANALOG_SENSITIVITY) ?: 800
                val minFocusDist = chars.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE) ?: 0.0f
                val activeArray = chars.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE) ?: Rect(0, 0, 4000, 3000)
                val pixelArray = chars.get(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE) ?: Size(4000, 3000)

                val capabilities = chars.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES) ?: intArrayOf()
                val isManual = capabilities.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR)
                val isRaw = capabilities.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_RAW)

                val oisModes = chars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION) ?: intArrayOf()
                val supportsOis = oisModes.contains(CameraCharacteristics.LENS_OPTICAL_STABILIZATION_MODE_ON)

                val eisModes = chars.get(CameraCharacteristics.CONTROL_AVAILABLE_VIDEO_STABILIZATION_MODES) ?: intArrayOf()
                val supportsEis = eisModes.contains(CameraCharacteristics.CONTROL_VIDEO_STABILIZATION_MODE_ON)

                val physicalIds = chars.physicalCameraIds

                val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                val previewSizes = map?.getOutputSizes(SurfaceTexture::class.java)?.toList() ?: emptyList()
                val videoSizes = map?.getOutputSizes(android.media.MediaRecorder::class.java)?.toList() ?: emptyList()

                // Check for 10-bit dynamic range profile (Android 13+)
                val dynamicRangeProfiles = chars.get(CameraCharacteristics.REQUEST_AVAILABLE_DYNAMIC_RANGE_PROFILES)
                val supports10Bit = dynamicRangeProfiles != null

                // Detect ultrawide vs main wide by focal length (Pixel 9a ultrawide is ~2.2mm, main is ~6.8mm)
                val minFocal = focalLengths.minOrNull() ?: 5.0f
                val isUltraWide = minFocal < 3.0f
                val isMain = !isUltraWide

                val sensorOrientation = chars.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90

                val spec = CameraSensorSpec(
                    cameraId = id,
                    lensFacing = facing,
                    focalLengths = focalLengths,
                    apertures = apertures,
                    isoRange = isoRange,
                    exposureTimeRange = exposureRange,
                    maxAnalogSensitivity = maxAnalogIso,
                    minimumFocusDistance = minFocusDist,
                    activeArraySize = activeArray,
                    pixelArraySize = pixelArray,
                    isManualSensorCapable = isManual,
                    isRawCapable = isRaw,
                    is10BitDynamicRangeSupported = supports10Bit,
                    supportsOis = supportsOis,
                    supportsEis = supportsEis,
                    physicalCameraIds = physicalIds,
                    previewSizes = previewSizes,
                    videoSizes = videoSizes,
                    isUltraWide = isUltraWide,
                    isMainWide = isMain,
                    sensorOrientation = sensorOrientation
                )

                _probedSensors.add(spec)

                if (isMain && mainWideSpec == null) {
                    mainWideSpec = spec
                } else if (isUltraWide && ultraWideSpec == null) {
                    ultraWideSpec = spec
                }
            }

            // Build list of supported cinema custom resolutions from selected sensor capabilities
            val initialSpec = mainWideSpec ?: _probedSensors.firstOrNull()
            val probedResolutions = mutableListOf<CustomResolution>()

            if (initialSpec != null) {
                // Collect all valid hardware output sizes for preview/video
                val allVideoSizes: List<Size> = (initialSpec.videoSizes + initialSpec.previewSizes)
                    .filter { it.width >= 640 && it.height >= 480 } // filter out tiny thumbnails
                    .distinctBy { "${it.width}x${it.height}" }
                    .sortedWith(compareByDescending<Size> { it.width * it.height }.thenByDescending { it.width })

                for (size in allVideoSizes) {
                    probedResolutions.add(CustomResolution.fromSize(size.width, size.height))
                }
            }

            if (probedResolutions.isEmpty()) {
                probedResolutions.add(CustomResolution.FHD_1080P)
                probedResolutions.add(CustomResolution.UHD_4K)
            }

            if (initialSpec != null) {
                _state.update {
                    it.copy(
                        currentSensorSpec = initialSpec,
                        supportedResolutions = probedResolutions,
                        videoResolution = probedResolutions.firstOrNull { r -> r.width == 3840 && r.height == 2160 }
                            ?: probedResolutions.firstOrNull { r -> r.width == 1920 && r.height == 1080 }
                            ?: probedResolutions.first(),
                        iso = initialSpec.isoRange.lower.coerceAtLeast(100),
                        focusDistance = 0.0f // Infinity
                    )
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to probe camera sensors", e)
        }
    }

    /**
     * Attaches the preview surface and starts the camera stream.
     */
    fun attachPreviewSurface(surface: Surface) {
        previewSurface = surface
        val currentSpec = _state.value.currentSensorSpec
        if (currentSpec != null) {
            openCamera(currentSpec.cameraId)
        }
    }

    fun detachPreviewSurface() {
        closeCamera()
        previewSurface = null
    }

    @SuppressLint("MissingPermission")
    fun openCamera(cameraId: String) {
        startBackgroundThread()
        closeCamera()

        val handler = cameraHandler ?: return

        try {
            cameraManager.openCamera(cameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    cameraDevice = camera
                    startCaptureSession()
                }

                override fun onDisconnected(camera: CameraDevice) {
                    camera.close()
                    cameraDevice = null
                }

                override fun onError(camera: CameraDevice, error: Int) {
                    Log.e(TAG, "Camera device error: $error")
                    camera.close()
                    cameraDevice = null
                }
            }, handler)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to open camera: $cameraId", e)
        }
    }

    fun closeCamera() {
        try {
            captureSession?.stopRepeating()
            captureSession?.close()
        } catch (e: Exception) {
            Log.w(TAG, "Error closing capture session", e)
        } finally {
            captureSession = null
        }

        try {
            cameraDevice?.close()
        } catch (e: Exception) {
            Log.w(TAG, "Error closing camera device", e)
        } finally {
            cameraDevice = null
        }

        rawImageReader?.close()
        rawImageReader = null
    }

    val optimalPreviewSize: Size
        get() {
            val spec = _state.value.currentSensorSpec ?: return Size(1920, 1080)
            val selectedRes = _state.value.videoResolution
            val targetRatio = selectedRes.width.toFloat() / selectedRes.height.toFloat()

            // 1. Try finding exact matching preview size
            val exactMatch = spec.previewSizes.firstOrNull {
                it.width == selectedRes.width && it.height == selectedRes.height
            }
            if (exactMatch != null) return exactMatch

            // 2. Try finding same aspect ratio matching preview size
            val matchingAspect = spec.previewSizes
                .filter { Math.abs((it.width.toFloat() / it.height.toFloat()) - targetRatio) < 0.05f }
                .maxByOrNull { it.width * it.height }
            if (matchingAspect != null) return matchingAspect

            // 3. Fallback to 16:9 maximum supported preview size
            return spec.previewSizes
                .filter { it.width * 9 == it.height * 16 }
                .maxByOrNull { it.width * it.height }
                ?: (spec.previewSizes.firstOrNull() ?: Size(1920, 1080))
        }

    /**
     * Creates a deterministic Camera2 CaptureSession supporting:
     * 1. Viewfinder preview Surface
     * 2. Video recording Surface (when active)
     * 3. RAW ImageReader Surface (when in RAW mode)
     */
    fun startCaptureSession(onConfigured: () -> Unit = {}) {
        val device = cameraDevice ?: return
        val surface = previewSurface ?: return
        val handler = cameraHandler ?: return
        val spec = _state.value.currentSensorSpec ?: return

        try {
            val outputConfigs = mutableListOf<OutputConfiguration>()
            outputConfigs.add(OutputConfiguration(surface))

            // If video recording surface is attached, add it
            videoRecordingSurface?.let { recSurface ->
                if (recSurface.isValid) {
                    outputConfigs.add(OutputConfiguration(recSurface))
                }
            }

            // If RAW mode is selected, configure RAW_SENSOR ImageReader
            if (_state.value.recordingFormat == RecordingFormat.RAW_DNG && spec.isRawCapable) {
                val rawSize = spec.pixelArraySize
                rawImageReader = ImageReader.newInstance(
                    rawSize.width,
                    rawSize.height,
                    ImageFormat.RAW_SENSOR,
                    5 // 5-frame ring buffer
                ).apply {
                    setOnImageAvailableListener({ reader ->
                        val image = reader.acquireLatestImage()
                        val result = lastCaptureResult
                        if (image != null && result != null) {
                            onRawFrameAvailable?.invoke(image, result)
                        } else {
                            image?.close()
                        }
                    }, handler)
                    outputConfigs.add(OutputConfiguration(this.surface))
                }
            }

            val sessionConfig = SessionConfiguration(
                SessionConfiguration.SESSION_REGULAR,
                outputConfigs,
                Executors.newSingleThreadExecutor(),
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(session: CameraCaptureSession) {
                        captureSession = session
                        createAndSetRepeatingRequest()
                        onConfigured()
                    }

                    override fun onConfigureFailed(session: CameraCaptureSession) {
                        Log.e(TAG, "CaptureSession configuration failed")
                    }
                }
            )

            device.createCaptureSession(sessionConfig)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start capture session", e)
        }
    }

    fun startRecordingSession(surface: Surface, fileName: String, onStarted: () -> Unit = {}) {
        videoRecordingSurface = surface
        _state.update {
            it.copy(
                isRecording = true,
                currentSavedFilePath = fileName,
                recordingTimeSeconds = 0L,
                recordedBytes = 0L,
                droppedFramesCount = 0
            )
        }
        startCaptureSession(onConfigured = onStarted)
    }

    fun stopRecordingSession(onStopped: () -> Unit = {}) {
        videoRecordingSurface = null
        _state.update { it.copy(isRecording = false) }
        createAndSetRepeatingRequest()
        startCaptureSession(onConfigured = onStopped)
    }

    /**
     * Builds and submits the repeating CaptureRequest with full manual cinema controls.
     */
    fun createAndSetRepeatingRequest() {
        val device = cameraDevice ?: return
        val session = captureSession ?: return
        val pSurface = previewSurface ?: return
        val handler = cameraHandler ?: return
        val spec = _state.value.currentSensorSpec ?: return
        val stateVal = _state.value

        try {
            val template = if (stateVal.isRecording) {
                CameraDevice.TEMPLATE_RECORD
            } else {
                CameraDevice.TEMPLATE_PREVIEW
            }

            val builder = device.createCaptureRequest(template).apply {
                addTarget(pSurface)

                // Add recording surface if recording
                videoRecordingSurface?.let { recSurface ->
                    if (recSurface.isValid) {
                        addTarget(recSurface)
                    }
                }

                // Add raw surface if in RAW mode
                rawImageReader?.let { reader ->
                    if (reader.surface.isValid) {
                        addTarget(reader.surface)
                    }
                }
            }

            // Apply Manual Sensor parameters
            applySensorParameters(builder, spec, stateVal)

            previewRequestBuilder = builder

            session.setRepeatingRequest(builder.build(), captureCallback, handler)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to set repeating request", e)
        }
    }

    /**
     * Applies full manual camera parameters: Shutter speed / angle, ISO, Focus, WB, OIS, EIS,
     * Log tone curve, and 2x in-sensor lossless crop.
     */
    private fun applySensorParameters(
        builder: CaptureRequest.Builder,
        spec: CameraSensorSpec,
        state: CinemaState
    ) {
        // 1. Target FPS Range
        val fpsRange = Range(state.targetFps, state.targetFps)
        builder.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, fpsRange)

        // 2. Exposure & Sensitivity (Shutter & ISO)
        if (state.isManualExposure) {
            builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)

            // Calculate exposure time in nanoseconds
            val exposureNs = if (state.shutterUnit == ShutterUnit.ANGLE) {
                ColorMath.shutterAngleToNanos(state.shutterAngle, state.targetFps)
            } else {
                state.shutterSpeedNs
            }.coerceIn(spec.exposureTimeRange.lower, spec.exposureTimeRange.upper)

            builder.set(CaptureRequest.SENSOR_EXPOSURE_TIME, exposureNs)

            val safeIso = state.iso.coerceIn(spec.isoRange.lower, spec.isoRange.upper)
            builder.set(CaptureRequest.SENSOR_SENSITIVITY, safeIso)

            // Clamp frame duration to ensure sensor doesn't drop target FPS
            val minFrameDurationNs = 1_000_000_000L / state.targetFps
            builder.set(CaptureRequest.SENSOR_FRAME_DURATION, minFrameDurationNs)
        } else {
            builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
            builder.set(CaptureRequest.CONTROL_AE_LOCK, false)
        }

        // 3. Manual Focus / Tap to Focus
        when (state.focusMode) {
            FocusMode.MANUAL -> {
                builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF)
                val clampedDist = state.focusDistance.coerceIn(0.0f, max(0.1f, spec.minimumFocusDistance))
                builder.set(CaptureRequest.LENS_FOCUS_DISTANCE, clampedDist)
            }
            FocusMode.AUTO_CONTINUOUS -> {
                builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO)
            }
            FocusMode.LOCKED -> {
                builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_AUTO)
                builder.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_IDLE)
            }
        }

        // 4. White Balance (Manual Kelvin / Tint vs Auto)
        if (state.isManualWb) {
            builder.set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_OFF)
            builder.set(CaptureRequest.COLOR_CORRECTION_MODE, CaptureRequest.COLOR_CORRECTION_MODE_TRANSFORM_MATRIX)
            val rggb = ColorMath.kelvinToRggbChannelVector(state.wbKelvin, state.wbTint)
            builder.set(CaptureRequest.COLOR_CORRECTION_GAINS, rggb)
        } else {
            builder.set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_AUTO)
        }

        // 5. Optical & Electronic Stabilization (OIS / EIS)
        if (spec.supportsOis) {
            val oisMode = if (state.oisEnabed) {
                CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_ON
            } else {
                CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_OFF
            }
            builder.set(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE, oisMode)
        }

        if (spec.supportsEis) {
            val eisMode = if (state.eisEnabled) {
                CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_ON
            } else {
                CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_OFF
            }
            builder.set(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE, eisMode)
        }

        // 6. 2x Lossless In-Sensor Center Crop (Pixel 9a 48MP Quad-PD)
        val activeArray = spec.activeArraySize
        if (state.selectedLens == LensSelection.CROP_2X) {
            val cropWidth = activeArray.width() / 2
            val cropHeight = activeArray.height() / 2
            val cropLeft = activeArray.left + (activeArray.width() - cropWidth) / 2
            val cropTop = activeArray.top + (activeArray.height() - cropHeight) / 2
            val cropRect = Rect(cropLeft, cropTop, cropLeft + cropWidth, cropTop + cropHeight)
            builder.set(CaptureRequest.SCALER_CROP_REGION, cropRect)
        } else {
            builder.set(CaptureRequest.SCALER_CROP_REGION, activeArray)
        }

        // 7. Cinema Flat Log Tone Curve
        if (state.colorProfile == ColorProfile.FLAT_LOG) {
            applyLogToneCurve(builder)
        } else {
            builder.set(CaptureRequest.TONEMAP_MODE, CaptureRequest.TONEMAP_MODE_FAST)
        }
    }

    /**
     * Applies a flat logarithmic tone curve to the Camera2 pipeline for grading headroom.
     */
    private fun applyLogToneCurve(builder: CaptureRequest.Builder) {
        try {
            // 32-point Cine-Log tone curve lifting shadows while keeping highlights linear
            val points = 32
            val curve = FloatArray(points * 2)
            for (i in 0 until points) {
                val inVal = i.toFloat() / (points - 1)
                // Logarithmic transfer: out = ln(1 + 9 * in) / ln(10) (logC / S-Log3 shape)
                val outVal = Math.log10(1.0 + 9.0 * inVal).toFloat().coerceIn(0.0f, 1.0f)
                curve[i * 2] = inVal
                curve[i * 2 + 1] = outVal
            }
            val tonemapCurve = TonemapCurve(curve, curve, curve)
            builder.set(CaptureRequest.TONEMAP_MODE, CaptureRequest.TONEMAP_MODE_CONTRAST_CURVE)
            builder.set(CaptureRequest.TONEMAP_CURVE, tonemapCurve)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to apply log tonemap curve", e)
        }
    }

    /**
     * Real-time metadata capture callback for live metrics and viewfinder updates.
     */
    private val captureCallback = object : CameraCaptureSession.CaptureCallback() {
        override fun onCaptureCompleted(
            session: CameraCaptureSession,
            request: CaptureRequest,
            result: TotalCaptureResult
        ) {
            lastCaptureResult = result

            // Measure actual FPS
            frameCount++
            val now = System.nanoTime()
            val elapsed = now - lastFpsTimestamp
            if (elapsed >= 1_000_000_000L) {
                val fps = (frameCount * 1_000_000_000.0 / elapsed).toFloat()
                frameCount = 0
                lastFpsTimestamp = now
                _state.update { it.copy(measuredFps = fps) }
            }

            // Sync actual sensor exposure if in auto mode
            if (!_state.value.isManualExposure) {
                val actualExposure = result.get(CaptureResult.SENSOR_EXPOSURE_TIME)
                val actualIso = result.get(CaptureResult.SENSOR_SENSITIVITY)
                if (actualExposure != null && actualIso != null) {
                    _state.update {
                        it.copy(
                            shutterSpeedNs = actualExposure,
                            iso = actualIso
                        )
                    }
                }
            }

            // Sync actual focus distance if in auto focus
            if (_state.value.focusMode != FocusMode.MANUAL) {
                val actualFocusDist = result.get(CaptureResult.LENS_FOCUS_DISTANCE)
                if (actualFocusDist != null) {
                    _state.update { it.copy(focusDistance = actualFocusDist) }
                }
            }
        }
    }

    /**
     * User Action: Switch Lens (0.5x Ultrawide, 1x Main Wide, 2x Lossless Center Crop)
     */
    fun selectLens(lens: LensSelection) {
        val targetSpec = when (lens) {
            LensSelection.ULTRAWIDE -> _probedSensors.find { it.isUltraWide } ?: _probedSensors.firstOrNull()
            LensSelection.WIDE_1X, LensSelection.CROP_2X -> _probedSensors.find { it.isMainWide } ?: _probedSensors.firstOrNull()
        }

        val probedResolutions = mutableListOf<CustomResolution>()
        if (targetSpec != null) {
            val allVideoSizes: List<Size> = (targetSpec.videoSizes + targetSpec.previewSizes)
                .filter { it.width >= 640 && it.height >= 480 }
                .distinctBy { "${it.width}x${it.height}" }
                .sortedWith(compareByDescending<Size> { it.width * it.height }.thenByDescending { it.width })
            for (size in allVideoSizes) {
                probedResolutions.add(CustomResolution.fromSize(size.width, size.height))
            }
        }

        _state.update {
            it.copy(
                selectedLens = lens,
                currentSensorSpec = targetSpec ?: it.currentSensorSpec,
                supportedResolutions = if (probedResolutions.isNotEmpty()) probedResolutions else it.supportedResolutions
            )
        }

        if (targetSpec != null && targetSpec.cameraId != cameraDevice?.id) {
            openCamera(targetSpec.cameraId)
        } else {
            createAndSetRepeatingRequest()
        }
    }

    /**
     * User Action: Set Shutter (Speed in Nanos or Angle in Degrees)
     */
    fun setShutterSpeed(nanos: Long) {
        _state.update {
            it.copy(
                isManualExposure = true,
                shutterSpeedNs = nanos,
                shutterAngle = ColorMath.nanosToShutterAngle(nanos, it.targetFps)
            )
        }
        createAndSetRepeatingRequest()
    }

    fun setShutterAngle(degrees: Float) {
        val nanos = ColorMath.shutterAngleToNanos(degrees, _state.value.targetFps)
        _state.update {
            it.copy(
                isManualExposure = true,
                shutterAngle = degrees,
                shutterSpeedNs = nanos
            )
        }
        createAndSetRepeatingRequest()
    }

    fun toggleShutterUnit() {
        _state.update {
            it.copy(
                shutterUnit = if (it.shutterUnit == ShutterUnit.ANGLE) ShutterUnit.SPEED else ShutterUnit.ANGLE
            )
        }
    }

    /**
     * User Action: Set ISO
     */
    fun setIso(iso: Int) {
        _state.update {
            it.copy(
                isManualExposure = true,
                iso = iso
            )
        }
        createAndSetRepeatingRequest()
    }

    fun toggleExposureMode() {
        _state.update {
            it.copy(isManualExposure = !it.isManualExposure)
        }
        createAndSetRepeatingRequest()
    }

    /**
     * User Action: Set Manual Focus Distance (0.0 = Infinity, max = macro)
     */
    fun setFocusDistance(distance: Float) {
        _state.update {
            it.copy(
                focusMode = FocusMode.MANUAL,
                focusDistance = distance
            )
        }
        createAndSetRepeatingRequest()
    }

    fun setFocusMode(mode: FocusMode) {
        _state.update { it.copy(focusMode = mode) }
        createAndSetRepeatingRequest()
    }

    /**
     * User Action: Tap-to-Focus
     * Converts normalized [0, 1] preview touch coordinates to Camera2 MeteringRectangle.
     */
    fun tapToFocus(normX: Float, normY: Float) {
        val spec = _state.value.currentSensorSpec ?: return
        val activeArray = spec.activeArraySize
        val session = captureSession ?: return
        val builder = previewRequestBuilder ?: return
        val handler = cameraHandler ?: return

        val sensorX = (normX * activeArray.width()).toInt().coerceIn(0, activeArray.width())
        val sensorY = (normY * activeArray.height()).toInt().coerceIn(0, activeArray.height())

        val left = max(0, sensorX - FOCUS_AREA_SIZE)
        val top = max(0, sensorY - FOCUS_AREA_SIZE)
        val right = min(activeArray.width(), sensorX + FOCUS_AREA_SIZE)
        val bottom = min(activeArray.height(), sensorY + FOCUS_AREA_SIZE)

        val meteringRect = MeteringRectangle(Rect(left, top, right, bottom), MeteringRectangle.METERING_WEIGHT_MAX)

        _state.update {
            it.copy(
                tapToFocusPoint = Pair(normX, normY),
                focusMode = FocusMode.AUTO_CONTINUOUS
            )
        }

        try {
            builder.set(CaptureRequest.CONTROL_AF_REGIONS, arrayOf(meteringRect))
            builder.set(CaptureRequest.CONTROL_AE_REGIONS, arrayOf(meteringRect))
            builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_AUTO)
            builder.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_START)

            session.capture(builder.build(), null, handler)

            // Reset trigger to IDLE
            builder.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_IDLE)
            session.setRepeatingRequest(builder.build(), captureCallback, handler)
        } catch (e: Exception) {
            Log.e(TAG, "Failed tap-to-focus trigger", e)
        }
    }

    /**
     * User Action: White Balance (Kelvin & Tint)
     */
    fun setWhiteBalance(kelvin: Int, tint: Int = 0) {
        _state.update {
            it.copy(
                isManualWb = true,
                wbKelvin = kelvin,
                wbTint = tint
            )
        }
        createAndSetRepeatingRequest()
    }

    fun toggleAutoWb() {
        _state.update {
            it.copy(isManualWb = !it.isManualWb)
        }
        createAndSetRepeatingRequest()
    }

    /**
     * User Action: Target FPS (24, 30, 60)
     */
    fun setTargetFps(fps: Int) {
        _state.update {
            val newShutterNs = if (it.shutterUnit == ShutterUnit.ANGLE) {
                ColorMath.shutterAngleToNanos(it.shutterAngle, fps)
            } else {
                it.shutterSpeedNs
            }
            it.copy(
                targetFps = fps,
                shutterSpeedNs = newShutterNs
            )
        }
        createAndSetRepeatingRequest()
    }

    /**
     * User Action: Color Profile (Log, HLG, Rec709)
     */
    fun setColorProfile(profile: ColorProfile) {
        _state.update { it.copy(colorProfile = profile) }
        createAndSetRepeatingRequest()
    }

    /**
     * User Action: Stabilization (OIS / EIS)
     */
    fun toggleOis() {
        _state.update { it.copy(oisEnabed = !it.oisEnabed) }
        createAndSetRepeatingRequest()
    }

    fun toggleEis() {
        _state.update { it.copy(eisEnabled = !it.eisEnabled) }
        createAndSetRepeatingRequest()
    }

    /**
     * User Action: Video Resolution & Bitrate
     */
    fun setVideoResolution(res: CustomResolution) {
        _state.update { it.copy(videoResolution = res) }
        startCaptureSession()
    }

    fun setVideoCodec(codec: VideoCodec) {
        _state.update { it.copy(videoCodec = codec) }
    }

    fun setBitrateMbps(mbps: Int) {
        _state.update { it.copy(targetBitrateMbps = mbps.coerceIn(20, 250)) }
    }

    fun setRecordingFormat(format: RecordingFormat) {
        _state.update { it.copy(recordingFormat = format) }
        startCaptureSession()
    }

    // Monitoring Overlays toggles
    fun toggleHistogram() = _state.update { it.copy(showHistogram = !it.showHistogram) }
    fun toggleFocusPeaking() = _state.update { it.copy(showFocusPeaking = !it.showFocusPeaking) }
    fun setPeakingColor(color: PeakingColor) = _state.update { it.copy(peakingColor = color) }
    fun setPeakingSensitivity(sens: Float) = _state.update { it.copy(peakingSensitivity = sens.coerceIn(0.1f, 1.0f)) }
    fun toggleZebras() = _state.update { it.copy(showZebras = !it.showZebras) }
    fun setZebraThreshold(ire: Int) = _state.update { it.copy(zebraIreThreshold = ire.coerceIn(50, 100)) }
    fun toggleLutPreview() = _state.update { it.copy(showLutPreview = !it.showLutPreview) }
    fun setFrameGuide(guide: FrameGuide) = _state.update { it.copy(frameGuide = guide) }
    fun toggleGrid() = _state.update { it.copy(showGrid = !it.showGrid) }

    fun updateTelemetry(
        battery: Int,
        storageGb: Float,
        thermal: ThermalStatus,
        micConnected: Boolean
    ) {
        _state.update {
            val bitrateBytesPerSec = (it.targetBitrateMbps * 1_000_000L) / 8L
            val remainingMins = if (bitrateBytesPerSec > 0) {
                ((storageGb * 1_000_000_000L) / (bitrateBytesPerSec * 60L)).toInt()
            } else 120

            it.copy(
                batteryPercent = battery,
                remainingStorageGb = storageGb,
                remainingRecordingMinutes = remainingMins,
                thermalStatus = thermal,
                isExternalMicConnected = micConnected
            )
        }
    }

    fun setRecordingSurface(surface: Surface?) {
        videoRecordingSurface = surface
    }

    fun setRecordingActive(active: Boolean, path: String? = null) {
        _state.update {
            it.copy(
                isRecording = active,
                currentSavedFilePath = path,
                recordingTimeSeconds = 0L
            )
        }
        createAndSetRepeatingRequest()
    }

    fun updateAudioLevels(peakL: Float, peakR: Float) {
        _state.update {
            it.copy(audioPeakDbL = peakL, audioPeakDbR = peakR)
        }
    }

    fun updateRecordingMetrics(seconds: Long, bytes: Long, droppedFrames: Int) {
        _state.update {
            it.copy(
                recordingTimeSeconds = seconds,
                recordedBytes = bytes,
                droppedFramesCount = droppedFrames
            )
        }
    }

    fun updateHistogramData(data: HistogramData) {
        _state.update {
            it.copy(histogramData = data)
        }
    }

    fun getCameraCharacteristics(cameraId: String): CameraCharacteristics {
        return cameraManager.getCameraCharacteristics(cameraId)
    }
}
