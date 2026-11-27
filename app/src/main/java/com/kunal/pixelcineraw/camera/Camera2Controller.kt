package com.kunal.pixelcineraw.camera

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.SurfaceTexture
import android.hardware.camera2.*
import android.hardware.camera2.params.DynamicRangeProfiles
import android.hardware.camera2.params.MeteringRectangle
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.RggbChannelVector
import android.hardware.camera2.params.SessionConfiguration
import android.hardware.display.DisplayManager
import android.media.Image
import android.media.ImageReader
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import android.util.Log
import android.util.Range
import android.util.Size
import android.view.PixelCopy
import android.view.Surface
import android.view.WindowManager
import com.kunal.pixelcineraw.gl.HlgGlRenderer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

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

    // Transition Pre-matching Cache
    private var lastExposureNs: Long? = null
    private var lastIso: Int? = null
    private var lastColorGains: RggbChannelVector? = null

    // HLG Fallback & OpenGL ES BT.2020 Render Pipeline (Lazily instantiated for HLG10)
    private var hardwareHlgFallback = false
    private var hlgGlRenderer: HlgGlRenderer? = null
    var onFirstEncoderFrame: ((Long) -> Unit)? = null
        set(value) {
            field = value
            hlgGlRenderer?.onFirstEncoderFrame = value
        }

    // Settings repository
    val settingsRepo = CameraSettingsRepository(context)

    // State flow initialized with persisted settings
    private val _state = MutableStateFlow(settingsRepo.loadSettings(CinemaState()))
    val state: StateFlow<CinemaState> = _state.asStateFlow()

    // Sensor pointers
    private var mainCameraSpec: CameraSensorSpec? = null
    private var frontCameraSpec: CameraSensorSpec? = null
    private var ultraWideCameraSpec: CameraSensorSpec? = null

    // Gyroflow & Rack Focus
    private val gyroflowLogger by lazy { com.kunal.pixelcineraw.imu.GyroflowLogger(context) }
    private var rackFocusJob: kotlinx.coroutines.Job? = null

    // FPS Metering
    private var frameCount = 0
    private var lastFpsTimestamp = System.nanoTime()

    // Lock-free VSYNC Zoom Sync State
    private val pendingZoomRatio = AtomicReference<Float>(_state.value.zoomRatio)
    @Volatile private var lastAppliedZoomRatio = _state.value.zoomRatio
    @Volatile private var smoothedZoomRatio = _state.value.zoomRatio
    private var isZoomCheckScheduled = false

    // Lock-free VSYNC ISO Sync State (Sub-Stop Analog Base + Gain Boost)
    private val pendingEffectiveIso = AtomicReference<Float>(_state.value.iso.toFloat())
    @Volatile private var currentEffectiveIso = _state.value.iso.toFloat()
    @Volatile private var lastAppliedIso = _state.value.iso
    @Volatile private var lastAppliedBoost = 100
    private var isIsoCheckScheduled = false

    // Lock-free VSYNC Focus Sync State
    private val pendingFocusDistance = AtomicReference<Float>(_state.value.focusDistance)
    @Volatile private var lastAppliedFocusDistance = _state.value.focusDistance
    @Volatile private var smoothedFocusDistance = _state.value.focusDistance
    private var isFocusCheckScheduled = false

    // Lock-free VSYNC Shutter Exposure Sync State
    private val pendingExposureNs = AtomicReference<Long>(_state.value.shutterSpeedNs)
    @Volatile private var lastAppliedExposureNs = _state.value.shutterSpeedNs
    @Volatile private var smoothedExposureNs = _state.value.shutterSpeedNs.toDouble()
    private var isExposureCheckScheduled = false

    // Lock-free VSYNC White Balance Sync State
    private val pendingWbKelvin = AtomicInteger(_state.value.wbKelvin)
    private val pendingWbTint = AtomicInteger(_state.value.wbTint)
    @Volatile private var lastAppliedWbKelvin = _state.value.wbKelvin
    @Volatile private var lastAppliedWbTint = _state.value.wbTint
    @Volatile private var smoothedWbKelvin = _state.value.wbKelvin.toFloat()
    private var isWbCheckScheduled = false

    // Reusable pre-allocated Rect for zero-allocation crop regions
    private val cachedCropRect = Rect()

    // 30ms Coalesced Exposure Compensation (EV) Throttle State
    private var lastEvRequestTimeMs = 0L
    private var isEvApplyScheduled = false
    private val pendingEvApplyRunnable = Runnable {
        isEvApplyScheduled = false
        lastEvRequestTimeMs = SystemClock.uptimeMillis()
        createAndSetRepeatingRequest()
    }
    private val persistEvRunnable = Runnable {
        persistState()
    }

    // Throttled UI Readouts for Auto Exposure & Focus (prevents recomposition flooding)
    private var lastAutoExposureReadoutTime = 0L
    private var lastAutoFocusReadoutTime = 0L

    // 12Hz Telemetry & Histogram Dispatch Throttle State
    private var lastHistogramUpdateTime = 0L
    private var pendingHistogramData: HistogramData? = null
    private var isHistogramDispatchScheduled = false
    private var lastAudioUpdateTime = 0L
    private var lastMetricsUpdateTime = 0L

    // iPhone-Style Cinema Auto-ISO Controller State (180° Shutter + AUTO ISO)
    @Volatile private var currentContinuousIso = _state.value.iso.toFloat()
    @Volatile private var latestEvError = 0f
    private var autoIsoSustainedFrames = 0
    private var framesSinceLastHardwareIsoUpdate = 0
    private var activeTapMeteringRect: MeteringRectangle? = null

    init {
        startBackgroundThread()
        probeSensors()
    }

    private fun startBackgroundThread() {
        if (cameraThread == null) {
            cameraThread = HandlerThread("PixelCineCamera", Process.THREAD_PRIORITY_URGENT_DISPLAY).apply {
                start()
                cameraHandler = Handler(looper)
            }
            cameraHandler?.post {
                try {
                    Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_DISPLAY)
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to set URGENT_DISPLAY priority on camera thread", e)
                }
            }
        }
    }

    fun stopBackgroundThread() {
        hlgGlRenderer?.release()
        hlgGlRenderer = null
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
            val cameraIds = cameraManager.cameraIdList.toMutableList()
            for (id in cameraManager.cameraIdList) {
                try {
                    val chars = cameraManager.getCameraCharacteristics(id)
                    chars.physicalCameraIds.forEach { physId ->
                        if (!cameraIds.contains(physId)) {
                            cameraIds.add(physId)
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Error querying physical IDs for $id", e)
                }
            }
            var mainWideSpec: CameraSensorSpec? = null
            var ultraWideSpec: CameraSensorSpec? = null
            var frontSpec: CameraSensorSpec? = null

            for (id in cameraIds) {
                val chars = cameraManager.getCameraCharacteristics(id)
                val facing = chars.get(CameraCharacteristics.LENS_FACING) ?: continue

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
                val supportsPreviewStab = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    eisModes.contains(CameraCharacteristics.CONTROL_VIDEO_STABILIZATION_MODE_PREVIEW_STABILIZATION)
                } else false

                val physicalIds = chars.physicalCameraIds

                val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                val stSizes = map?.getOutputSizes(SurfaceTexture::class.java)?.toList() ?: emptyList()
                val shSizes = map?.getOutputSizes(android.view.SurfaceHolder::class.java)?.toList() ?: emptyList()
                val previewSizes = if (stSizes.isNotEmpty()) stSizes else shSizes
                val videoSizes = map?.getOutputSizes(android.media.MediaRecorder::class.java)?.toList() ?: emptyList()

                // Check for 10-bit dynamic range profile (Android 13+)
                val dynamicRangeProfiles = chars.get(CameraCharacteristics.REQUEST_AVAILABLE_DYNAMIC_RANGE_PROFILES)
                val supports10Bit = dynamicRangeProfiles != null
                val supportsHlg10 = dynamicRangeProfiles?.supportedProfiles?.contains(DynamicRangeProfiles.HLG10) == true

                // Detect ultrawide vs main wide by focal length (Pixel 9a ultrawide is ~2.2mm, main is ~6.8mm)
                val minFocal = focalLengths.minOrNull() ?: 5.0f
                val isUltraWide = (facing == CameraCharacteristics.LENS_FACING_BACK) && minFocal < 3.0f
                val isMain = (facing == CameraCharacteristics.LENS_FACING_BACK) && !isUltraWide

                val sensorOrientation = chars.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: (if (facing == CameraCharacteristics.LENS_FACING_FRONT) 270 else 90)
                val zoomRange = chars.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE) ?: Range(1.0f, 8.0f)
                val isLogicalMultiCam = (facing == CameraCharacteristics.LENS_FACING_BACK) &&
                        capabilities.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_LOGICAL_MULTI_CAMERA) &&
                        zoomRange.lower <= 0.6f

                val nrModes = chars.get(CameraCharacteristics.NOISE_REDUCTION_AVAILABLE_NOISE_REDUCTION_MODES) ?: intArrayOf()
                val edgeModes = chars.get(CameraCharacteristics.EDGE_AVAILABLE_EDGE_MODES) ?: intArrayOf()

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
                    isHlg10Supported = supportsHlg10,
                    supportsOis = supportsOis,
                    supportsEis = supportsEis,
                    supportsPreviewStabilization = supportsPreviewStab,
                    physicalCameraIds = physicalIds,
                    previewSizes = previewSizes,
                    surfaceTextureSizes = stSizes,
                    videoSizes = videoSizes,
                    supportedNoiseReductionModes = nrModes,
                    supportedEdgeModes = edgeModes,
                    isUltraWide = isUltraWide,
                    isMainWide = isMain,
                    isLogicalMultiCamera = isLogicalMultiCam,
                    sensorOrientation = sensorOrientation,
                    zoomRatioRange = zoomRange
                )

                _probedSensors.add(spec)

                if (facing == CameraCharacteristics.LENS_FACING_FRONT && frontSpec == null) {
                    frontSpec = spec
                } else if (isLogicalMultiCam && mainWideSpec == null) {
                    mainWideSpec = spec
                } else if (isMain && mainWideSpec == null) {
                    mainWideSpec = spec
                } else if (isUltraWide && ultraWideSpec == null) {
                    ultraWideSpec = spec
                }
            }

            mainCameraSpec = mainWideSpec
            frontCameraSpec = frontSpec
            ultraWideCameraSpec = ultraWideSpec

            // Build list of supported cinema custom resolutions from selected sensor capabilities
            val initialSpec = mainWideSpec ?: _probedSensors.firstOrNull()
            val probedResolutions = buildSupportedResolutions(initialSpec)

            if (initialSpec != null) {
                val (minZ, maxZ) = zoomBoundsForSpec(initialSpec)
                _state.update { current ->
                    val matchedRes = probedResolutions.find { r ->
                        r.width == current.videoResolution.width && r.height == current.videoResolution.height
                    } ?: probedResolutions.firstOrNull { r -> r.width == 3840 && r.height == 2160 }
                      ?: probedResolutions.firstOrNull { r -> r.width == 1920 && r.height == 1080 }
                      ?: CustomResolution.UHD_4K

                    val safeIso = current.iso.coerceIn(initialSpec.isoRange.lower, initialSpec.isoRange.upper)
                    current.copy(
                        currentSensorSpec = initialSpec,
                        supportedResolutions = probedResolutions,
                        videoResolution = matchedRes,
                        iso = safeIso,
                        minZoomRatio = minZ,
                        maxZoomRatio = maxZ,
                        zoomRatio = current.zoomRatio.coerceIn(minZ, maxZ)
                    )
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to probe camera sensors", e)
        }
    }

    private fun buildSupportedResolutions(spec: CameraSensorSpec?): List<CustomResolution> {
        val resolutions = mutableListOf<CustomResolution>()
        if (spec != null) {
            val allVideoSizes: List<Size> = (spec.videoSizes + spec.previewSizes)
                .filter { it.width >= 640 && it.height >= 480 }
                .distinctBy { "${it.width}x${it.height}" }
                .sortedWith(compareByDescending<Size> { it.width * it.height }.thenByDescending { it.width })
            for (size in allVideoSizes) {
                resolutions.add(CustomResolution.fromSize(size.width, size.height))
            }
        }
        if (resolutions.isEmpty()) {
            resolutions.add(CustomResolution.FHD_1080P)
            resolutions.add(CustomResolution.UHD_4K)
        }
        return resolutions
    }

    private fun zoomBoundsForSpec(spec: CameraSensorSpec): Pair<Float, Float> {
        val minZ = if (spec.zoomRatioRange.lower < 1.0f) 0.5f else spec.zoomRatioRange.lower
        val maxZ = max(minZ, spec.zoomRatioRange.upper)
        return minZ to maxZ
    }

    private fun clampTargetFpsForSpec(spec: CameraSensorSpec, desiredFps: Int): Int {
        val ranges = try {
            getCameraCharacteristics(spec.cameraId)
                .get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)
        } catch (_: Exception) {
            null
        } ?: return desiredFps

        if (ranges.any { it.lower == desiredFps && it.upper == desiredFps }) {
            return desiredFps
        }
        val bestFixed = ranges
            .filter { it.lower == it.upper && it.upper <= desiredFps }
            .maxByOrNull { it.upper }?.upper
        if (bestFixed != null) return bestFixed

        val maxUpper = ranges.maxOfOrNull { it.upper } ?: desiredFps
        return minOf(desiredFps, maxUpper).coerceAtLeast(15)
    }

    fun getDisplayRotation(): Int {
        val isPort = context.resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_PORTRAIT
        if (isPort) return Surface.ROTATION_0

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                context.display?.rotation ?: Surface.ROTATION_90
            } catch (e: Exception) {
                Surface.ROTATION_90
            }
        } else {
            @Suppress("DEPRECATION")
            (context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager)?.defaultDisplay?.rotation ?: Surface.ROTATION_90
        }
    }

    fun setDisplayRotation(rotation: Int) {
        hlgGlRenderer?.setDisplayRotation(rotation)
    }

    /**
     * Computes MediaMuxer orientationHint (0, 90, 180, 270) relative to the native
     * landscape (ROTATION_90) encoder surface so all devices/players rotate upright in hardware.
     */
    fun computeVideoOrientationHint(): Int {
        if (_state.value.orientationLockMode == OrientationLockMode.LOCK_PORTRAIT) {
            return 90
        }
        return when (getDisplayRotation()) {
            Surface.ROTATION_0 -> 90
            Surface.ROTATION_90 -> 0
            Surface.ROTATION_180 -> 270
            Surface.ROTATION_270 -> 180
            else -> 90
        }
    }

    /**
     * Attaches the preview surface and starts the camera stream.
     */
    fun attachPreviewSurface(surface: Surface) {
        previewSurface = surface
        hlgGlRenderer?.setDisplayRotation(getDisplayRotation())
        val currentSpec = _state.value.currentSensorSpec
        if (currentSpec != null) {
            openCamera(currentSpec.cameraId)
        }
    }

    fun detachPreviewSurface() {
        closeCamera()
        hlgGlRenderer?.setPreviewSurface(null, 0, 0, 0, 0)
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

    val optimalStreamSize: Size
        get() {
            val spec = _state.value.currentSensorSpec ?: return Size(3840, 2160)
            val selectedRes = _state.value.videoResolution
            val targetRatio = selectedRes.width.toFloat() / selectedRes.height.toFloat()

            // Strictly restrict to SurfaceTexture output sizes supported on hardware and <= 3840x2160
            val validSizes = (if (spec.surfaceTextureSizes.isNotEmpty()) spec.surfaceTextureSizes else spec.previewSizes)
                .filter { it.width <= 3840 && it.height <= 2160 }

            // 1. Exact match with selected video resolution (e.g. 3840x2160, 1920x1080)
            val exactMatch = validSizes.firstOrNull {
                it.width == selectedRes.width && it.height == selectedRes.height
            }
            if (exactMatch != null) return exactMatch

            // 2. Stream with matching aspect ratio (tolerance 0.02)
            val matchingAspect = validSizes
                .filter { Math.abs((it.width.toFloat() / it.height.toFloat()) - targetRatio) < 0.02f }
                .sortedByDescending { it.width * it.height }
            if (matchingAspect.isNotEmpty()) {
                val targetPixels = selectedRes.width * selectedRes.height
                return matchingAspect.firstOrNull { it.width * it.height >= targetPixels }
                    ?: matchingAspect.first()
            }

            // 3. For custom aspect ratios (e.g. 2.39:1 Scope, 1.90:1 DCI, 1:1 Square, 4:3 Open Gate):
            // Pick native 4:3 stream <= 3840x2160 (e.g. 2048x1536, 1920x1440, 1600x1200)
            val fourThreeStreams = validSizes
                .filter { Math.abs((it.width.toFloat() / it.height.toFloat()) - (4f / 3f)) < 0.05f }
                .sortedByDescending { it.width * it.height }
            if (fourThreeStreams.isNotEmpty()) {
                return fourThreeStreams.first()
            }

            // 4. Fallback: largest valid SurfaceTexture stream size <= 3840x2160
            return validSizes.maxByOrNull { it.width * it.height } ?: Size(1920, 1080)
        }

    val optimalPreviewSize: Size
        get() = optimalStreamSize

    /**
     * Creates a deterministic Camera2 CaptureSession supporting:
     * 1. Viewfinder preview Surface (10-bit HLG or SDR)
     * 2. Video recording Surface (when active)
     * 3. Automatic onConfigureFailed fallback to 64-point BT.2100 HLG curve
     */
    fun startCaptureSession(onConfigured: () -> Unit = {}) {
        val device = cameraDevice ?: return
        val surface = previewSurface ?: return
        val handler = cameraHandler ?: return
        val spec = _state.value.currentSensorSpec ?: return

        // If HLG10 HDR is selected and current camera does not support HLG10,
        // automatically switch to physical camera "0" before configuring session
        if (_state.value.colorProfile == ColorProfile.HLG_10BIT && !spec.isHlg10Supported) {
            val hlgSpec = _probedSensors.find { it.cameraId == "0" && it.isHlg10Supported }
                ?: _probedSensors.find { it.isHlg10Supported && it.isMainWide }
                ?: _probedSensors.find { it.isHlg10Supported }
            if (hlgSpec != null && hlgSpec.cameraId != cameraDevice?.id) {
                Log.d(TAG, "Switching to physical camera ${hlgSpec.cameraId} for HLG10 HDR support")
                _state.update {
                    it.copy(
                        currentSensorSpec = hlgSpec,
                        selectedLens = LensSelection.WIDE_1X,
                        zoomRatio = 1.0f
                    )
                }
                openCamera(hlgSpec.cameraId)
                return
            }
        }

        try {
            // Properly close existing capture session before reconfiguring
            try {
                captureSession?.stopRepeating()
                captureSession?.close()
            } catch (e: Exception) {
                Log.w(TAG, "Error closing previous capture session", e)
            } finally {
                captureSession = null
            }

            val streamSize = optimalStreamSize
            val vRes = _state.value.videoResolution
            val renderer = hlgGlRenderer ?: HlgGlRenderer(context).also {
                it.onFirstEncoderFrame = onFirstEncoderFrame
                it.setTargetFps(_state.value.targetFps)
                it.onHistogramExtracted = { data ->
                    updateHistogramData(data)
                }
                it.isAutoIsoEnabled = _state.value.isAutoIso && _state.value.isManualExposure
                it.setManualIso(_state.value.iso.toFloat())
                val activeRange = getActiveSensorIsoRange()
                it.minSensorIso = activeRange.lower.toFloat()
                it.maxSensorIso = minOf(_state.value.autoIsoCap.toFloat(), activeRange.upper.toFloat())
                it.setDeband(_state.value.isDebandEnabled)
                val lutMode = when (_state.value.lutPreset) {
                    LutPreset.OFF -> 0
                    LutPreset.REC709_NATURAL -> 1
                    LutPreset.KODAK_2383 -> 2
                    LutPreset.CINEMA_TEAL -> 3
                }
                it.setLutPreset(lutMode)
                it.setLutBakeEnabled(_state.value.isLutBakeEnabled)
                hlgGlRenderer = it
            }

            if (!renderer.isReady) {
                renderer.initialize(
                    previewSurface = surface,
                    viewWidth = vRes.width,
                    viewHeight = vRes.height,
                    streamWidth = streamSize.width,
                    streamHeight = streamSize.height
                ) {
                    cameraHandler?.post {
                        startCaptureSession(onConfigured)
                    }
                }
                return
            }

            val isPortrait = getDisplayRotation() == Surface.ROTATION_0 || getDisplayRotation() == Surface.ROTATION_180 ||
                    _state.value.orientationLockMode == OrientationLockMode.LOCK_PORTRAIT
            val prevW = if (isPortrait) minOf(vRes.width, vRes.height) else maxOf(vRes.width, vRes.height)
            val prevH = if (isPortrait) maxOf(vRes.width, vRes.height) else minOf(vRes.width, vRes.height)
            renderer.setDisplayRotation(getDisplayRotation())
            renderer.setCameraOrientation(spec.isFrontCamera, spec.sensorOrientation)
            renderer.setPreviewSurface(surface, prevW, prevH, streamSize.width, streamSize.height)
            renderer.setColorMode(_state.value.colorProfile)

            if (videoRecordingSurface != null) {
                val encW = maxOf(vRes.width, vRes.height)
                val encH = minOf(vRes.width, vRes.height)
                renderer.setEncoderSurface(videoRecordingSurface, encW, encH, _state.value.targetFps)
            } else {
                renderer.setEncoderSurface(null, 0, 0)
            }

            val outputConfigs = mutableListOf<OutputConfiguration>()
            val glInputSurface = renderer.getCameraInputSurface()
            if (glInputSurface != null && glInputSurface.isValid) {
                val glOutputConfig = OutputConfiguration(glInputSurface).apply {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        setMirrorMode(OutputConfiguration.MIRROR_MODE_NONE)
                    }
                }
                outputConfigs.add(glOutputConfig)
            } else {
                Log.w(TAG, "GL input surface invalid, falling back to direct SurfaceView")
                outputConfigs.add(OutputConfiguration(surface))
            }

            // In RAW mode, attach RAW_SENSOR stream
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
                        val status = if (_state.value.colorProfile == ColorProfile.HLG_10BIT) {
                            HdrPipelineStatus.CURVE_HLG10
                        } else {
                            HdrPipelineStatus.NONE
                        }
                        _state.update { it.copy(hdrPipelineStatus = status) }
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
        val vRes = _state.value.videoResolution
        // Always configure GL encoder viewport in native landscape dimensions (max x min)
        val encW = maxOf(vRes.width, vRes.height)
        val encH = minOf(vRes.width, vRes.height)
        lastMetricsUpdateTime = 0L
        hlgGlRenderer?.setRecordingActive(true)
        hlgGlRenderer?.setEncoderSurface(surface, encW, encH, _state.value.targetFps)

        if (_state.value.isGyroflowLoggingEnabled) {
            try {
                val gcsvName = fileName.removeSuffix(".mp4") + ".gcsv"
                val values = android.content.ContentValues().apply {
                    put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, gcsvName)
                    put(android.provider.MediaStore.MediaColumns.MIME_TYPE, "text/csv")
                    put(android.provider.MediaStore.MediaColumns.RELATIVE_PATH, "${android.os.Environment.DIRECTORY_MOVIES}/PixelCineRaw")
                }
                val uri = context.contentResolver.insert(android.provider.MediaStore.Files.getContentUri("external"), values)
                if (uri != null) {
                    val pfd = context.contentResolver.openFileDescriptor(uri, "rw")
                    if (pfd != null) {
                        gyroflowLogger.start(java.io.FileOutputStream(pfd.fileDescriptor))
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to start Gyroflow logger", e)
            }
        }

        onStarted()
    }

    fun stopRecordingSession(onStopped: () -> Unit = {}) {
        try {
            gyroflowLogger.stop()
        } catch (e: Exception) {
            Log.w(TAG, "Error stopping Gyroflow logger", e)
        }
        hlgGlRenderer?.setRecordingActive(false)
        hlgGlRenderer?.setEncoderSurface(null, 0, 0)
        videoRecordingSurface = null
        _state.update { it.copy(isRecording = false) }
        onStopped()
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

            val streamSurface = hlgGlRenderer?.inputSurface ?: pSurface

            val builder = device.createCaptureRequest(template).apply {
                addTarget(streamSurface)

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
            lastAppliedZoomRatio = stateVal.zoomRatio
            smoothedZoomRatio = stateVal.zoomRatio
            pendingZoomRatio.set(stateVal.zoomRatio)
            lastAppliedIso = stateVal.iso
            currentEffectiveIso = stateVal.iso.toFloat()
            pendingEffectiveIso.set(stateVal.iso.toFloat())
            lastAppliedFocusDistance = stateVal.focusDistance
            smoothedFocusDistance = stateVal.focusDistance
            pendingFocusDistance.set(stateVal.focusDistance)
            lastAppliedExposureNs = stateVal.shutterSpeedNs
            smoothedExposureNs = stateVal.shutterSpeedNs.toDouble()
            pendingExposureNs.set(stateVal.shutterSpeedNs)
            lastAppliedWbKelvin = stateVal.wbKelvin
            lastAppliedWbTint = stateVal.wbTint
            smoothedWbKelvin = stateVal.wbKelvin.toFloat()
            pendingWbKelvin.set(stateVal.wbKelvin)
            pendingWbTint.set(stateVal.wbTint)
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
        // 1. Locked Constant Target FPS Range [effectiveFps, effectiveFps]
        val effectiveFps = clampTargetFpsForSpec(spec, state.targetFps)
        val fpsRange = Range(effectiveFps, effectiveFps)
        builder.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, fpsRange)

        // 2. Anti-Banding Mode (50Hz / 60Hz / Auto / Off)
        builder.set(CaptureRequest.CONTROL_AE_ANTIBANDING_MODE, state.antiBandingMode.camera2Mode)

        // 2b. AE Metering Regions & Scene Mode Configuration (mcpro24fps Center-Weighted Protection)
        // Disables Google's aggressive night/backlight scene detection overrides so brightness doesn't pump mid-pan
        builder.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
        builder.set(CaptureRequest.CONTROL_SCENE_MODE, CaptureRequest.CONTROL_SCENE_MODE_DISABLED)

        val chars = getCameraCharacteristics(spec.cameraId)
        val maxAeRegions = chars.get(CameraCharacteristics.CONTROL_MAX_REGIONS_AE) ?: 0
        if (maxAeRegions > 0) {
            val tapRect = activeTapMeteringRect
            if (tapRect != null && state.tapToFocusPoint != null) {
                builder.set(CaptureRequest.CONTROL_AE_REGIONS, arrayOf(tapRect))
            } else {
                when (state.aeMeteringMode) {
                    AeMeteringMode.CENTER -> {
                        val activeArray = spec.activeArraySize
                        val cwW = (activeArray.width() * 0.38f).roundToInt()
                        val cwH = (activeArray.height() * 0.38f).roundToInt()
                        val cwLeft = activeArray.left + (activeArray.width() - cwW) / 2
                        val cwTop = activeArray.top + (activeArray.height() - cwH) / 2
                        val centerRect = MeteringRectangle(
                            Rect(cwLeft, cwTop, cwLeft + cwW, cwTop + cwH),
                            1000
                        )
                        val aeRegions = if (maxAeRegions >= 2) {
                            val peripheralRect = MeteringRectangle(activeArray, 150)
                            arrayOf(centerRect, peripheralRect)
                        } else {
                            arrayOf(centerRect)
                        }
                        builder.set(CaptureRequest.CONTROL_AE_REGIONS, aeRegions)
                    }
                    AeMeteringMode.MATRIX -> {
                        val fullFrameRect = MeteringRectangle(spec.activeArraySize, MeteringRectangle.METERING_WEIGHT_MAX)
                        builder.set(CaptureRequest.CONTROL_AE_REGIONS, arrayOf(fullFrameRect))
                    }
                }
            }
        }

        // 3. Exposure & Sensitivity (Shutter & ISO & EV)
        val useHardwareAe = !state.isManualExposure || state.isAutoIso

        if (useHardwareAe) {
            builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
            builder.set(CaptureRequest.CONTROL_AE_LOCK, state.isAeAfLocked)

            // Highlight headroom bias: In HLG10, HDR10+, and PIXEL-LOG modes, bias by -1.0 EV below user's EV compensation
            val isHdrOrLog = state.colorProfile == ColorProfile.HLG_10BIT ||
                    state.colorProfile == ColorProfile.HDR10_PLUS ||
                    state.colorProfile == ColorProfile.FLAT_LOG

            val step = chars.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_STEP) ?: android.util.Rational(1, 3)
            val range = chars.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE) ?: Range(-6, 6)
            val stepVal = step.toFloat().coerceAtLeast(0.01f)

            val targetEv = if (isHdrOrLog) state.evCompensation - 1.0f else state.evCompensation
            val evIndex = (targetEv / stepVal).roundToInt().coerceIn(range.lower, range.upper)
            builder.set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, evIndex)

            // If manual shutter (e.g. 180° shutter or manual speed) is selected while ISO is Auto, lock shutter speed
            if (state.isManualExposure) {
                val baseExposureNs = computeTargetShutterSpeedNs(
                    shutterAngle = state.shutterAngle,
                    targetFps = state.targetFps,
                    antiBandingMode = state.antiBandingMode,
                    manualSpeedNs = state.shutterSpeedNs,
                    shutterUnit = state.shutterUnit
                )
                val exposureNs = baseExposureNs.coerceIn(spec.exposureTimeRange.lower, spec.exposureTimeRange.upper)
                builder.set(CaptureRequest.SENSOR_EXPOSURE_TIME, exposureNs)
                val minFrameDurationNs = 1_000_000_000L / state.targetFps
                builder.set(CaptureRequest.SENSOR_FRAME_DURATION, minFrameDurationNs)
            }

            // Pre-match exposure from previous sensor during physical transition to eliminate brightness flash
            if (state.isTransitioningLens) {
                lastExposureNs?.let { exp ->
                    val safeExp = exp.coerceIn(spec.exposureTimeRange.lower, spec.exposureTimeRange.upper)
                    builder.set(CaptureRequest.SENSOR_EXPOSURE_TIME, safeExp)
                }
                lastIso?.let { iso ->
                    val safeIso = iso.coerceIn(spec.isoRange.lower, spec.isoRange.upper)
                    builder.set(CaptureRequest.SENSOR_SENSITIVITY, safeIso)
                }
            }
        } else {
            builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)

            // Calculate exposure time in nanoseconds with anti-banding 180° sync
            val baseExposureNs = computeTargetShutterSpeedNs(
                shutterAngle = state.shutterAngle,
                targetFps = state.targetFps,
                antiBandingMode = state.antiBandingMode,
                manualSpeedNs = state.shutterSpeedNs,
                shutterUnit = state.shutterUnit
            )
            // Apply EV compensation bias in manual mode: 2^ev multiplier
            val evMultiplier = Math.pow(2.0, state.evCompensation.toDouble()).toFloat()
            val exposureNs = (baseExposureNs * evMultiplier).toLong()
                .coerceIn(spec.exposureTimeRange.lower, spec.exposureTimeRange.upper)

            builder.set(CaptureRequest.SENSOR_EXPOSURE_TIME, exposureNs)

            // Hardware SENSOR_SENSITIVITY with fixed boost = 100 (avoids Tensor G4 analog/digital bank glitches)
            val activeRange = spec.isoRange
            val effectiveIso = currentEffectiveIso.coerceIn(activeRange.lower.toFloat(), activeRange.upper.toFloat())
            val applyBase = effectiveIso.roundToInt().coerceIn(activeRange.lower, activeRange.upper)

            val boostRange = chars.get(CameraCharacteristics.CONTROL_POST_RAW_SENSITIVITY_BOOST_RANGE)
            val supportsBoost = boostRange != null

            builder.set(CaptureRequest.SENSOR_SENSITIVITY, applyBase)
            if (supportsBoost) {
                builder.set(CaptureRequest.CONTROL_POST_RAW_SENSITIVITY_BOOST, 100)
            }
            lastAppliedIso = applyBase
            lastAppliedBoost = 100

            // Clamp frame duration to ensure sensor doesn't drop target FPS
            val minFrameDurationNs = 1_000_000_000L / state.targetFps
            builder.set(CaptureRequest.SENSOR_FRAME_DURATION, minFrameDurationNs)
        }

        // 2b. Torch / Continuous Video Light
        val flashAvailable = chars.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
        if (flashAvailable) {
            val flashMode = if (state.isTorchOn) CaptureRequest.FLASH_MODE_TORCH else CaptureRequest.FLASH_MODE_OFF
            builder.set(CaptureRequest.FLASH_MODE, flashMode)
        }

        // 3. Manual Focus / Tap to Focus
        when {
            state.isAeAfLocked -> {
                builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_AUTO)
                builder.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_IDLE)
            }
            state.focusMode == FocusMode.MANUAL -> {
                builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF)
                val clampedDist = state.focusDistance.coerceIn(0.0f, max(0.1f, spec.minimumFocusDistance))
                builder.set(CaptureRequest.LENS_FOCUS_DISTANCE, clampedDist)
            }
            state.focusMode == FocusMode.AUTO_CONTINUOUS -> {
                builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO)
            }
            else -> {
                builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_AUTO)
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
            builder.set(CaptureRequest.COLOR_CORRECTION_MODE, CaptureRequest.COLOR_CORRECTION_MODE_HIGH_QUALITY)
            // Pre-match color gains from previous sensor during physical transition to eliminate color flash
            if (state.isTransitioningLens && lastColorGains != null) {
                builder.set(CaptureRequest.COLOR_CORRECTION_GAINS, lastColorGains)
            }
        }

        // 5. Optical & Electronic Stabilization (OFF / OIS / OIS+EIS)
        when (state.stabilizationMode) {
            StabilizationMode.OFF -> {
                if (spec.supportsOis) {
                    builder.set(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE, CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_OFF)
                }
                builder.set(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE, CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_OFF)
            }
            StabilizationMode.OIS -> {
                if (spec.supportsOis) {
                    builder.set(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE, CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_ON)
                }
                builder.set(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE, CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_OFF)
            }
            StabilizationMode.OIS_EIS -> {
                if (spec.supportsOis) {
                    builder.set(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE, CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_ON)
                }
                val eisMode = if (spec.supportsPreviewStabilization && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_PREVIEW_STABILIZATION
                } else if (spec.supportsEis) {
                    CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_ON
                } else {
                    CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_OFF
                }
                builder.set(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE, eisMode)
            }
        }

        // 6. Hardware-Accelerated Smooth Zoom (CONTROL_ZOOM_RATIO)
        val zoomRatio = state.zoomRatio
        val targetHardwareZoom = if (spec.isLogicalMultiCamera) {
            // Logical multi-camera directly handles the full range (e.g. 0.5x to 8.0x) natively
            zoomRatio.coerceIn(spec.zoomRatioRange.lower, spec.zoomRatioRange.upper)
        } else if (spec.isUltraWide) {
            // Physical Ultrawide sensor (displays 0.5x to 0.99x)
            (zoomRatio / 0.5f).coerceIn(spec.zoomRatioRange.lower, spec.zoomRatioRange.upper)
        } else {
            // Physical Main wide sensor (displays 1.0x to 8.0x)
            zoomRatio.coerceIn(spec.zoomRatioRange.lower, spec.zoomRatioRange.upper)
        }

        try {
            builder.set(CaptureRequest.CONTROL_ZOOM_RATIO, targetHardwareZoom)
        } catch (e: Exception) {
            // Fallback for devices without CONTROL_ZOOM_RATIO support
            val activeArray = spec.activeArraySize
            val cropW = (activeArray.width() / targetHardwareZoom).toInt()
            val cropH = (activeArray.height() / targetHardwareZoom).toInt()
            val cropL = (activeArray.width() - cropW) / 2
            val cropT = (activeArray.height() - cropH) / 2
            cachedCropRect.set(cropL, cropT, cropL + cropW, cropT + cropH)
            builder.set(CaptureRequest.SCALER_CROP_REGION, cachedCropRect)
        }

        // 7. Tone Curves: High quality ISP tone-mapping across all modes for neutral white highlights
        builder.set(CaptureRequest.TONEMAP_MODE, CaptureRequest.TONEMAP_MODE_HIGH_QUALITY)

        // 8. Clean Cinema ISP vs Stock Processing
        if (state.ispMode == IspMode.CLEAN) {
            val edgeMode = when {
                spec.supportedEdgeModes.contains(CaptureRequest.EDGE_MODE_OFF) -> CaptureRequest.EDGE_MODE_OFF
                spec.supportedEdgeModes.contains(CaptureRequest.EDGE_MODE_FAST) -> CaptureRequest.EDGE_MODE_FAST
                else -> CaptureRequest.EDGE_MODE_HIGH_QUALITY
            }
            builder.set(CaptureRequest.EDGE_MODE, edgeMode)

            val nrMode = when {
                spec.supportedNoiseReductionModes.contains(CaptureRequest.NOISE_REDUCTION_MODE_OFF) -> CaptureRequest.NOISE_REDUCTION_MODE_OFF
                spec.supportedNoiseReductionModes.contains(CaptureRequest.NOISE_REDUCTION_MODE_FAST) -> CaptureRequest.NOISE_REDUCTION_MODE_FAST
                else -> CaptureRequest.NOISE_REDUCTION_MODE_HIGH_QUALITY
            }
            builder.set(CaptureRequest.NOISE_REDUCTION_MODE, nrMode)

            try {
                builder.set(CaptureRequest.SHADING_MODE, CaptureRequest.SHADING_MODE_HIGH_QUALITY)
                builder.set(CaptureRequest.COLOR_CORRECTION_ABERRATION_MODE, CaptureRequest.COLOR_CORRECTION_ABERRATION_MODE_HIGH_QUALITY)
                builder.set(CaptureRequest.HOT_PIXEL_MODE, CaptureRequest.HOT_PIXEL_MODE_HIGH_QUALITY)
                builder.set(CaptureRequest.DISTORTION_CORRECTION_MODE, CaptureRequest.DISTORTION_CORRECTION_MODE_HIGH_QUALITY)
            } catch (e: Exception) {
                Log.w(TAG, "Optional ISP quality modes not supported on this sensor", e)
            }
        } else {
            val edgeMode = if (spec.supportedEdgeModes.contains(CaptureRequest.EDGE_MODE_HIGH_QUALITY)) {
                CaptureRequest.EDGE_MODE_HIGH_QUALITY
            } else CaptureRequest.EDGE_MODE_FAST
            builder.set(CaptureRequest.EDGE_MODE, edgeMode)

            val nrMode = if (spec.supportedNoiseReductionModes.contains(CaptureRequest.NOISE_REDUCTION_MODE_HIGH_QUALITY)) {
                CaptureRequest.NOISE_REDUCTION_MODE_HIGH_QUALITY
            } else CaptureRequest.NOISE_REDUCTION_MODE_FAST
            builder.set(CaptureRequest.NOISE_REDUCTION_MODE, nrMode)
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
            lastExposureNs = result.get(CaptureResult.SENSOR_EXPOSURE_TIME)
            lastIso = result.get(CaptureResult.SENSOR_SENSITIVITY)
            lastColorGains = result.get(CaptureResult.COLOR_CORRECTION_GAINS)

            val frameTimestampNs = result.get(CaptureResult.SENSOR_TIMESTAMP)
            if (frameTimestampNs != null && lastIso != null) {
                hlgGlRenderer?.timestampIsoMap?.put(frameTimestampNs, lastIso!!)
            }

            // If transitioning between physical lenses, first captured frame marks completion
            if (_state.value.isTransitioningLens) {
                _state.update {
                    it.copy(
                        isTransitioningLens = false,
                        transitionScale = 1.0f,
                        transitionBitmap = null
                    )
                }
            }

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

            // Tensor G4 Hardware AE readout sync (throttled to 5Hz to prevent recomposition flooding)
            val nowMs = SystemClock.uptimeMillis()
            val isUsingHardwareAe = !_state.value.isManualExposure || _state.value.isAutoIso
            if (isUsingHardwareAe && (nowMs - lastAutoExposureReadoutTime >= 200L)) {
                val actualExposure = result.get(CaptureResult.SENSOR_EXPOSURE_TIME)
                val actualIso = result.get(CaptureResult.SENSOR_SENSITIVITY)
                if (actualIso != null) {
                    val expDiff = if (actualExposure != null) Math.abs(actualExposure - _state.value.shutterSpeedNs) else 0L
                    val isoDiff = Math.abs(actualIso - _state.value.iso)
                    if (isoDiff >= 1 || (!_state.value.isManualExposure && expDiff > 500_000L)) {
                        lastAutoExposureReadoutTime = nowMs
                        _state.update {
                            it.copy(
                                iso = actualIso,
                                shutterSpeedNs = if (!it.isManualExposure && actualExposure != null) actualExposure else it.shutterSpeedNs
                            )
                        }
                    }
                }
            } else if (!_state.value.isAutoIso) {
                autoIsoSustainedFrames = 0
                framesSinceLastHardwareIsoUpdate = 0
            }

            // Sync actual focus distance if in auto focus (throttled to 5Hz)
            if (_state.value.focusMode != FocusMode.MANUAL && (nowMs - lastAutoFocusReadoutTime >= 200L)) {
                val actualFocusDist = result.get(CaptureResult.LENS_FOCUS_DISTANCE)
                if (actualFocusDist != null && Math.abs(actualFocusDist - _state.value.focusDistance) > 0.05f) {
                    lastAutoFocusReadoutTime = nowMs
                    _state.update { it.copy(focusDistance = actualFocusDist) }
                }
            }

            // Multi-Frame Hardware Parameter Ramping: ONLY call when manual parameter has actually changed!
            // Stops immediately when target reached - NEVER touches setRepeatingRequest while panning!
            val isManualIsoActive = _state.value.isManualExposure && !_state.value.isAutoIso
            val hasPendingRamp = (Math.abs(pendingZoomRatio.get() - lastAppliedZoomRatio) > 0.001f) ||
                    (isManualIsoActive && Math.abs(pendingEffectiveIso.get() - currentEffectiveIso) >= 0.5f) ||
                    (_state.value.isManualExposure && (Math.abs(pendingExposureNs.get() - lastAppliedExposureNs) > 50_000L)) ||
                    (_state.value.focusMode == FocusMode.MANUAL && (Math.abs(pendingFocusDistance.get() - lastAppliedFocusDistance) > 0.002f)) ||
                    (_state.value.isManualWb && (pendingWbKelvin.get() != lastAppliedWbKelvin || pendingWbTint.get() != lastAppliedWbTint))

            if (hasPendingRamp) {
                applyHardwareParametersRamping(force = false)
            }
        }
    }

    /**
     * User Action: Switch Lens (0.5x Ultrawide, 1x Main Wide, 2x Lossless Center Crop)
     */
    fun selectLens(lens: LensSelection) {
        hardwareHlgFallback = false
        val currentSpec = _state.value.currentSensorSpec

        // If current camera is a logical multi-camera covering 0.5x..8.0x, keep same camera device!
        if (currentSpec != null && currentSpec.isLogicalMultiCamera && lens != LensSelection.FRONT) {
            val targetZoom = when (lens) {
                LensSelection.ULTRAWIDE -> 0.5f
                LensSelection.WIDE_1X -> 1.0f
                LensSelection.CROP_2X -> 2.0f
                LensSelection.FRONT -> 1.0f
            }
            _state.update {
                it.copy(
                    selectedLens = lens,
                    zoomRatio = targetZoom
                )
            }
            pendingZoomRatio.set(targetZoom)
            val isoRange = getActiveSensorIsoRange()
            if (_state.value.iso !in isoRange.lower..isoRange.upper) {
                val clamped = _state.value.iso.coerceIn(isoRange.lower, isoRange.upper)
                _state.update { it.copy(iso = clamped) }
                pendingEffectiveIso.set(clamped.toFloat())
                currentEffectiveIso = clamped.toFloat()
            }
            persistState()
            cameraHandler?.post { applyHardwareParametersRamping(force = true) }
            return
        }

        val targetSpec = when (lens) {
            LensSelection.ULTRAWIDE -> _probedSensors.find { it.isUltraWide } ?: _probedSensors.firstOrNull()
            LensSelection.WIDE_1X, LensSelection.CROP_2X -> _probedSensors.find { it.isMainWide } ?: _probedSensors.firstOrNull()
            LensSelection.FRONT -> frontCameraSpec ?: _probedSensors.find { it.isFrontCamera } ?: _probedSensors.firstOrNull()
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

        val targetZoom = when (lens) {
            LensSelection.ULTRAWIDE -> 0.5f
            LensSelection.WIDE_1X -> 1.0f
            LensSelection.CROP_2X -> 2.0f
            LensSelection.FRONT -> 1.0f
        }

        val isSwitchingDevice = targetSpec != null && targetSpec.cameraId != cameraDevice?.id
        val (minZ, maxZ) = if (targetSpec != null) zoomBoundsForSpec(targetSpec) else (_state.value.minZoomRatio to _state.value.maxZoomRatio)
        val clampedZoom = targetZoom.coerceIn(minZ, maxZ)

        _state.update {
            it.copy(
                selectedLens = lens,
                zoomRatio = clampedZoom,
                minZoomRatio = minZ,
                maxZoomRatio = maxZ,
                currentSensorSpec = targetSpec ?: it.currentSensorSpec,
                supportedResolutions = if (probedResolutions.isNotEmpty()) probedResolutions else it.supportedResolutions,
                isTransitioningLens = isSwitchingDevice,
                transitionScale = if (lens == LensSelection.ULTRAWIDE) 0.95f else 1.05f
            )
        }
        pendingZoomRatio.set(clampedZoom)

        val isoRange = getActiveSensorIsoRange()
        if (_state.value.iso !in isoRange.lower..isoRange.upper) {
            val clamped = _state.value.iso.coerceIn(isoRange.lower, isoRange.upper)
            _state.update { it.copy(iso = clamped) }
            pendingEffectiveIso.set(clamped.toFloat())
            currentEffectiveIso = clamped.toFloat()
        }
        persistState()

        if (isSwitchingDevice && targetSpec != null) {
            val surface = previewSurface
            if (surface != null && surface.isValid) {
                try {
                    val pSize = optimalPreviewSize
                    if (pSize.width > 0 && pSize.height > 0) {
                        val bitmap = Bitmap.createBitmap(pSize.width, pSize.height, Bitmap.Config.ARGB_8888)
                        PixelCopy.request(surface, bitmap, { copyResult ->
                            if (copyResult == PixelCopy.SUCCESS) {
                                _state.update { it.copy(transitionBitmap = bitmap) }
                            }
                        }, cameraHandler ?: Handler(Looper.getMainLooper()))
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to capture transition frame", e)
                }
            }
            openCamera(targetSpec.cameraId)
        } else {
            createAndSetRepeatingRequest()
        }
    }

    /**
     * User Action: Smooth Zoom Ratio update.
     * Updates UI state instantly at 120Hz, queues zoom into lock-free AtomicReference,
     * and throttles Camera2 setRepeatingRequest cleanly to actual frame completion (VSYNC).
     */
    fun setZoomRatio(ratio: Float, forceUpdate: Boolean = false) {
        val clampedZoom = ratio.coerceIn(_state.value.minZoomRatio, _state.value.maxZoomRatio)
        val currentSpec = _state.value.currentSensorSpec

        val newLensSelection = when {
            clampedZoom < 1.0f -> LensSelection.ULTRAWIDE
            clampedZoom >= 2.0f -> LensSelection.CROP_2X
            else -> LensSelection.WIDE_1X
        }

        _state.update {
            it.copy(
                zoomRatio = clampedZoom,
                selectedLens = newLensSelection
            )
        }

        pendingZoomRatio.set(clampedZoom)

        val isoRange = getActiveSensorIsoRange()
        if (_state.value.iso !in isoRange.lower..isoRange.upper) {
            val clamped = _state.value.iso.coerceIn(isoRange.lower, isoRange.upper)
            _state.update { it.copy(iso = clamped) }
            pendingEffectiveIso.set(clamped.toFloat())
            currentEffectiveIso = clamped.toFloat()
        }
        persistState()

        if (currentSpec != null && currentSpec.isLogicalMultiCamera) {
            if (forceUpdate) {
                cameraHandler?.post { applyHardwareParametersRamping(force = true) }
            } else if (!isZoomCheckScheduled) {
                isZoomCheckScheduled = true
                cameraHandler?.post {
                    isZoomCheckScheduled = false
                    applyHardwareParametersRamping(force = false)
                }
            }
            return
        }

        // Fallback for separate physical camera devices
        val currentLens = _state.value.selectedLens
        val needsUltrawide = clampedZoom < 1.0f
        val isCurrentlyUltrawide = currentLens == LensSelection.ULTRAWIDE

        if (needsUltrawide && !isCurrentlyUltrawide) {
            selectLens(LensSelection.ULTRAWIDE)
        } else if (!needsUltrawide && isCurrentlyUltrawide) {
            selectLens(LensSelection.WIDE_1X)
        }

        if (forceUpdate) {
            cameraHandler?.post { applyHardwareParametersRamping(force = true) }
        } else if (!isZoomCheckScheduled) {
            isZoomCheckScheduled = true
            cameraHandler?.post {
                isZoomCheckScheduled = false
                applyHardwareParametersRamping(force = false)
            }
        }
    }

    /**
     * Multi-frame hardware parameter ramping synced 1:1 to camera frame VSYNC.
     * Damps ISO, Focus, Shutter Exposure, White Balance gains, and Zoom with exponential
     * decay (current += (target - current) * 0.22f) and batches all changed hardware keys
     * into at most ONE setRepeatingRequest per camera frame to eliminate HAL queue flooding.
     */
    private fun applyHardwareParametersRamping(force: Boolean = false) {
        val builder = previewRequestBuilder ?: return
        val session = captureSession ?: return
        val handler = cameraHandler ?: return
        val spec = _state.value.currentSensorSpec ?: return
        val stateVal = _state.value

        var needsRepeatingRequest = force

        // 1. Hardware Zoom Ratio Ramping
        val targetZoom = pendingZoomRatio.get()
        if (force || Math.abs(targetZoom - smoothedZoomRatio) < 0.005f) {
            smoothedZoomRatio = targetZoom
        } else {
            val deltaZoom = targetZoom - smoothedZoomRatio
            smoothedZoomRatio += deltaZoom * 0.22f
        }
        val applyZoom = smoothedZoomRatio
        if (force || Math.abs(applyZoom - lastAppliedZoomRatio) > 0.001f) {
            val targetHardwareZoom = if (spec.isLogicalMultiCamera) {
                applyZoom.coerceIn(spec.zoomRatioRange.lower, spec.zoomRatioRange.upper)
            } else if (spec.isUltraWide) {
                (applyZoom / 0.5f).coerceIn(spec.zoomRatioRange.lower, spec.zoomRatioRange.upper)
            } else {
                applyZoom.coerceIn(spec.zoomRatioRange.lower, spec.zoomRatioRange.upper)
            }

            try {
                builder.set(CaptureRequest.CONTROL_ZOOM_RATIO, targetHardwareZoom)
            } catch (_: Exception) {
                val activeArray = spec.activeArraySize
                val cropW = (activeArray.width() / targetHardwareZoom).toInt()
                val cropH = (activeArray.height() / targetHardwareZoom).toInt()
                val cropL = (activeArray.width() - cropW) / 2
                val cropT = (activeArray.height() - cropH) / 2
                cachedCropRect.set(cropL, cropT, cropL + cropW, cropT + cropH)
                builder.set(CaptureRequest.SCALER_CROP_REGION, cachedCropRect)
            }
            lastAppliedZoomRatio = applyZoom
            needsRepeatingRequest = true
        }

        // 2. ISO Sensitivity Ramping (Active only for manual ISO)
        if (stateVal.isManualExposure && !stateVal.isAutoIso) {
            val activeRange = getActiveSensorIsoRange()
            val targetIso = pendingEffectiveIso.get()
            val clampedTargetIso = targetIso.coerceIn(activeRange.lower.toFloat(), activeRange.upper.toFloat())
            if (force) {
                currentEffectiveIso = clampedTargetIso
            } else if (clampedTargetIso > currentEffectiveIso) {
                currentEffectiveIso = minOf(clampedTargetIso, currentEffectiveIso * 1.04f)
            } else if (clampedTargetIso < currentEffectiveIso) {
                currentEffectiveIso = maxOf(clampedTargetIso, currentEffectiveIso * 0.96f)
            }
            val applyBase = currentEffectiveIso.roundToInt().coerceIn(activeRange.lower, activeRange.upper)

            val chars = getCameraCharacteristics(spec.cameraId)
            val boostRange = chars.get(CameraCharacteristics.CONTROL_POST_RAW_SENSITIVITY_BOOST_RANGE)
            val supportsBoost = boostRange != null

            if (force || applyBase != lastAppliedIso) {
                builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
                builder.set(CaptureRequest.SENSOR_SENSITIVITY, applyBase)
                if (supportsBoost) {
                    builder.set(CaptureRequest.CONTROL_POST_RAW_SENSITIVITY_BOOST, 100)
                }
                lastAppliedIso = applyBase
                lastAppliedBoost = 100
                needsRepeatingRequest = true
            }
        }

        // 3. Shutter Exposure Time Ramping
        if (stateVal.isManualExposure) {
            val targetExp = pendingExposureNs.get().coerceIn(spec.exposureTimeRange.lower, spec.exposureTimeRange.upper)
            val evMultiplier = if (stateVal.isAutoIso) 1.0 else Math.pow(2.0, stateVal.evCompensation.toDouble())
            val biasedTargetExp = (targetExp * evMultiplier).toLong()
                .coerceIn(spec.exposureTimeRange.lower, spec.exposureTimeRange.upper)

            if (force || Math.abs(biasedTargetExp - smoothedExposureNs) < 100_000.0) {
                smoothedExposureNs = biasedTargetExp.toDouble()
            } else {
                val deltaExp = biasedTargetExp - smoothedExposureNs
                smoothedExposureNs += deltaExp * 0.22
            }
            val applyExp = smoothedExposureNs.toLong().coerceIn(spec.exposureTimeRange.lower, spec.exposureTimeRange.upper)
            if (force || Math.abs(applyExp - lastAppliedExposureNs) > 50_000L) {
                if (stateVal.isAutoIso) {
                    builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                } else {
                    builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
                }
                builder.set(CaptureRequest.SENSOR_EXPOSURE_TIME, applyExp)
                builder.set(CaptureRequest.SENSOR_FRAME_DURATION, 1_000_000_000L / stateVal.targetFps)
                lastAppliedExposureNs = applyExp
                needsRepeatingRequest = true
            }
        }

        // 4. Focus Distance Ramping
        if (stateVal.focusMode == FocusMode.MANUAL) {
            val targetDist = pendingFocusDistance.get()
            val maxDiopters = max(0.1f, spec.minimumFocusDistance)
            val clampedTargetDist = targetDist.coerceIn(0.0f, maxDiopters)
            if (force || Math.abs(clampedTargetDist - smoothedFocusDistance) < 0.003f) {
                smoothedFocusDistance = clampedTargetDist
            } else {
                val deltaDist = clampedTargetDist - smoothedFocusDistance
                smoothedFocusDistance += deltaDist * 0.22f
            }
            val applyDist = smoothedFocusDistance
            if (force || Math.abs(applyDist - lastAppliedFocusDistance) > 0.002f) {
                builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF)
                builder.set(CaptureRequest.LENS_FOCUS_DISTANCE, applyDist)
                lastAppliedFocusDistance = applyDist
                needsRepeatingRequest = true
            }
        }

        // 5. White Balance Ramping (Kelvin / RGGB Color Gains)
        if (stateVal.isManualWb) {
            val targetKelvin = pendingWbKelvin.get()
            val targetTint = pendingWbTint.get()
            if (force || Math.abs(targetKelvin - smoothedWbKelvin) < 4f) {
                smoothedWbKelvin = targetKelvin.toFloat()
            } else {
                val deltaK = targetKelvin - smoothedWbKelvin
                smoothedWbKelvin += deltaK * 0.22f
            }
            val applyKelvin = smoothedWbKelvin.roundToInt()
            if (force || applyKelvin != lastAppliedWbKelvin || targetTint != lastAppliedWbTint) {
                builder.set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_OFF)
                builder.set(CaptureRequest.COLOR_CORRECTION_MODE, CaptureRequest.COLOR_CORRECTION_MODE_TRANSFORM_MATRIX)
                val rggb = ColorMath.kelvinToRggbChannelVector(applyKelvin, targetTint)
                builder.set(CaptureRequest.COLOR_CORRECTION_GAINS, rggb)
                lastAppliedWbKelvin = applyKelvin
                lastAppliedWbTint = targetTint
                needsRepeatingRequest = true
            }
        }

        // Always ensure CONTROL_AE_TARGET_FPS_RANGE is strictly locked to [targetFps, targetFps]
        builder.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, Range(stateVal.targetFps, stateVal.targetFps))

        // Submit batched request ONLY if a hardware key actually changed
        if (needsRepeatingRequest) {
            try {
                session.setRepeatingRequest(builder.build(), captureCallback, handler)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to submit smoothed repeating request", e)
            }
        }
    }

    fun setZoomSliderVisibility(visible: Boolean) {
        _state.update { it.copy(isZoomSliderVisible = visible) }
    }

    /**
     * Computes exposure time in nanoseconds, enforcing physical 180° cinema motion blur
     * with exact 50Hz / 60Hz indoor flicker-free multiples.
     */
    fun computeTargetShutterSpeedNs(
        shutterAngle: Float,
        targetFps: Int,
        antiBandingMode: AntiBandingMode,
        manualSpeedNs: Long = 20_000_000L,
        shutterUnit: ShutterUnit = ShutterUnit.ANGLE
    ): Long {
        if (shutterUnit == ShutterUnit.SPEED) {
            return manualSpeedNs
        }
        // Physical 180° Cinema Shutter with indoor flicker-free anti-banding alignment
        if (shutterAngle == 180f) {
            return when (antiBandingMode) {
                AntiBandingMode.HZ_50 -> {
                    // Exact 50Hz flicker-free multiples: 1/50s or 1/100s
                    if (targetFps <= 30) 20_000_000L // 1/50s
                    else 10_000_000L // 1/100s
                }
                AntiBandingMode.HZ_60 -> {
                    // Exact 60Hz flicker-free multiples: 1/60s or 1/120s
                    if (targetFps <= 30) 16_666_667L // 1/60s
                    else 8_333_333L // 1/120s
                }
                else -> {
                    ColorMath.shutterAngleToNanos(shutterAngle, targetFps)
                }
            }
        }
        return ColorMath.shutterAngleToNanos(shutterAngle, targetFps)
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
        pendingExposureNs.set(nanos)
        persistState()

        if (!isExposureCheckScheduled) {
            isExposureCheckScheduled = true
            cameraHandler?.post {
                isExposureCheckScheduled = false
                applyHardwareParametersRamping(force = false)
            }
        }
    }

    fun setShutterAngle(degrees: Float) {
        val nanos = computeTargetShutterSpeedNs(degrees, _state.value.targetFps, _state.value.antiBandingMode)
        _state.update {
            it.copy(
                isManualExposure = true,
                shutterAngle = degrees,
                shutterSpeedNs = nanos
            )
        }
        pendingExposureNs.set(nanos)
        persistState()

        if (!isExposureCheckScheduled) {
            isExposureCheckScheduled = true
            cameraHandler?.post {
                isExposureCheckScheduled = false
                applyHardwareParametersRamping(force = false)
            }
        }
    }

    fun toggleShutterUnit() {
        _state.update {
            it.copy(
                shutterUnit = if (it.shutterUnit == ShutterUnit.ANGLE) ShutterUnit.SPEED else ShutterUnit.ANGLE
            )
        }
        persistState()
    }

    fun persistState() {
        settingsRepo.saveSettings(_state.value)
    }

    fun resetToDefaults() {
        val defaultState = settingsRepo.resetToDefaults()
        val spec = _state.value.currentSensorSpec
        _state.update {
            defaultState.copy(
                currentSensorSpec = spec,
                supportedResolutions = it.supportedResolutions
            )
        }
        pendingZoomRatio.set(defaultState.zoomRatio)
        lastAppliedZoomRatio = defaultState.zoomRatio
        smoothedZoomRatio = defaultState.zoomRatio

        pendingEffectiveIso.set(defaultState.iso.toFloat())
        currentEffectiveIso = defaultState.iso.toFloat()
        lastAppliedIso = defaultState.iso
        lastAppliedBoost = 100

        pendingFocusDistance.set(defaultState.focusDistance)
        lastAppliedFocusDistance = defaultState.focusDistance
        smoothedFocusDistance = defaultState.focusDistance

        pendingExposureNs.set(defaultState.shutterSpeedNs)
        lastAppliedExposureNs = defaultState.shutterSpeedNs
        smoothedExposureNs = defaultState.shutterSpeedNs.toDouble()

        pendingWbKelvin.set(defaultState.wbKelvin)
        pendingWbTint.set(defaultState.wbTint)
        lastAppliedWbKelvin = defaultState.wbKelvin
        smoothedWbKelvin = defaultState.wbKelvin.toFloat()

        selectLens(defaultState.selectedLens)
        createAndSetRepeatingRequest()
    }

    /**
     * Queries the active physical sensor's exact sensitivity range.
     * When using a logical multi-camera with 0.5x ultrawide, retrieves the physical
     * ultrawide sensor's characteristics so ISO bounds are never clamped to the main sensor.
     */
    fun getActiveSensorIsoRange(): Range<Int> {
        val stateVal = _state.value
        val currentSpec = stateVal.currentSensorSpec ?: return Range(50, 6400)
        if (currentSpec.isLogicalMultiCamera) {
            val isUltrawide = stateVal.selectedLens == LensSelection.ULTRAWIDE || stateVal.zoomRatio < 1.0f
            val physSpec = if (isUltrawide) {
                _probedSensors.find { it.isUltraWide }
            } else {
                _probedSensors.find { it.isMainWide }
            }
            if (physSpec != null) {
                return physSpec.isoRange
            }
        }
        return currentSpec.isoRange
    }

    /**
     * User Action: Set ISO (Sub-Stop Analog Base + Post-RAW Gain Boost)
     */
    fun setIso(iso: Int) {
        setEffectiveIso(iso.toFloat())
    }

    fun setEffectiveIso(iso: Float) {
        val activeRange = getActiveSensorIsoRange()
        val clampedIso = iso.coerceIn(activeRange.lower.toFloat(), activeRange.upper.toFloat())
        currentContinuousIso = clampedIso
        autoIsoSustainedFrames = 0
        hlgGlRenderer?.isAutoIsoEnabled = false
        hlgGlRenderer?.setManualIso(clampedIso)
        hlgGlRenderer?.setExposureTrim(1.0f)
        val lockedExposureNs = lastExposureNs ?: _state.value.shutterSpeedNs
        val wasAuto = _state.value.isAutoIso || !_state.value.isManualExposure
        _state.update {
            it.copy(
                isManualExposure = true,
                isAutoIso = false,
                iso = clampedIso.roundToInt(),
                shutterSpeedNs = lockedExposureNs
            )
        }
        pendingExposureNs.set(lockedExposureNs)
        pendingEffectiveIso.set(clampedIso)
        currentEffectiveIso = clampedIso
        persistState()

        if (wasAuto) {
            createAndSetRepeatingRequest()
        } else if (!isIsoCheckScheduled) {
            isIsoCheckScheduled = true
            cameraHandler?.post {
                isIsoCheckScheduled = false
                applyHardwareParametersRamping(force = false)
            }
        }
    }

    fun toggleExposureMode() {
        val willBeManual = !_state.value.isManualExposure
        if (willBeManual) {
            // Zero-flash auto-to-manual handoff: lock current auto exposure parameters
            val exp = lastExposureNs ?: _state.value.shutterSpeedNs
            val iso = lastIso ?: _state.value.iso
            currentContinuousIso = iso.toFloat()
            autoIsoSustainedFrames = 0
            hlgGlRenderer?.isAutoIsoEnabled = false
            hlgGlRenderer?.setManualIso(iso.toFloat())
            hlgGlRenderer?.setExposureTrim(1.0f)
            _state.update {
                it.copy(
                    isManualExposure = true,
                    isAutoIso = false,
                    isNdNeeded = false,
                    shutterSpeedNs = exp,
                    iso = iso
                )
            }
            pendingExposureNs.set(exp)
            pendingEffectiveIso.set(iso.toFloat())
            currentEffectiveIso = iso.toFloat()
            lastAppliedIso = iso
            lastAppliedExposureNs = exp
        } else {
            autoIsoSustainedFrames = 0
            hlgGlRenderer?.isAutoIsoEnabled = false
            hlgGlRenderer?.setExposureTrim(1.0f)
            _state.update {
                it.copy(
                    isManualExposure = false,
                    isAutoIso = false,
                    isNdNeeded = false
                )
            }
        }
        persistState()
        createAndSetRepeatingRequest()
    }

    fun toggleAutoIso() {
        val willBeAuto = !_state.value.isAutoIso
        autoIsoSustainedFrames = 0
        hlgGlRenderer?.isAutoIsoEnabled = false
        hlgGlRenderer?.setExposureTrim(1.0f)
        _state.update { it.copy(isAutoIso = willBeAuto) }
        persistState()
        createAndSetRepeatingRequest()
    }

    fun setAutoShutter() {
        autoIsoSustainedFrames = 0
        hlgGlRenderer?.isAutoIsoEnabled = false
        hlgGlRenderer?.setExposureTrim(1.0f)
        _state.update {
            it.copy(
                isManualExposure = false,
                isAutoIso = false,
                isNdNeeded = false
            )
        }
        persistState()
        cameraHandler?.post { applyHardwareParametersRamping(force = true) }
    }

    fun cycleAntiBandingMode() {
        val next = when (_state.value.antiBandingMode) {
            AntiBandingMode.HZ_50 -> AntiBandingMode.HZ_60
            AntiBandingMode.HZ_60 -> AntiBandingMode.AUTO
            AntiBandingMode.AUTO -> AntiBandingMode.OFF
            AntiBandingMode.OFF -> AntiBandingMode.HZ_50
        }
        setAntiBandingMode(next)
    }

    fun setAntiBandingMode(mode: AntiBandingMode) {
        _state.update { it.copy(antiBandingMode = mode) }
        if (_state.value.shutterUnit == ShutterUnit.ANGLE) {
            val nanos = computeTargetShutterSpeedNs(_state.value.shutterAngle, _state.value.targetFps, mode)
            _state.update { it.copy(shutterSpeedNs = nanos) }
            pendingExposureNs.set(nanos)
        }
        persistState()
        cameraHandler?.post { applyHardwareParametersRamping(force = true) }
    }

    /**
     * User Action: AE Metering Mode (Center-Weighted Subject vs Full-Frame Matrix)
     * Prevents ceiling tube lights, lamps, and windows at frame edges from causing exposure plunges when panning.
     */
    fun cycleAeMeteringMode() {
        val next = when (_state.value.aeMeteringMode) {
            AeMeteringMode.CENTER -> AeMeteringMode.MATRIX
            AeMeteringMode.MATRIX -> AeMeteringMode.CENTER
        }
        setAeMeteringMode(next)
    }

    fun setAeMeteringMode(mode: AeMeteringMode) {
        _state.update { it.copy(aeMeteringMode = mode) }
        persistState()
        cameraHandler?.post { applyHardwareParametersRamping(force = true) }
    }

    /**
     * Resets any active tap-to-meter / tap-to-focus lock back to the active AE metering mode.
     */
    fun resetTapToFocus() {
        activeTapMeteringRect = null
        _state.update { it.copy(tapToFocusPoint = null) }
        cameraHandler?.post { applyHardwareParametersRamping(force = true) }
    }

    /**
     * User Action: Set Manual Focus Distance (0.0 = Infinity, max = macro) (Synchronized to VSYNC)
     */
    fun setFocusDistance(distance: Float) {
        val spec = _state.value.currentSensorSpec
        val maxDiopters = max(0.1f, spec?.minimumFocusDistance ?: 10.0f)
        val clampedDist = distance.coerceIn(0.0f, maxDiopters)

        _state.update {
            it.copy(
                focusMode = FocusMode.MANUAL,
                focusDistance = clampedDist
            )
        }
        pendingFocusDistance.set(clampedDist)
        persistState()

        if (!isFocusCheckScheduled) {
            isFocusCheckScheduled = true
            cameraHandler?.post {
                isFocusCheckScheduled = false
                applyHardwareParametersRamping(force = false)
            }
        }
    }

    fun setFocusMode(mode: FocusMode) {
        _state.update { it.copy(focusMode = mode) }
        persistState()
        createAndSetRepeatingRequest()
    }

    /**
     * User Action: Tap-to-Focus
     * Converts normalized [0, 1] preview touch coordinates to Camera2 MeteringRectangle,
     * transforming accurately for SENSOR_ORIENTATION (90°/270° landscape), video aspect ratio
     * sensor crop, and active hardware zoom ratio.
     */
    fun tapToFocus(normX: Float, normY: Float) {
        val spec = _state.value.currentSensorSpec ?: return
        val activeArray = spec.activeArraySize
        val session = captureSession ?: return
        val builder = previewRequestBuilder ?: return
        val handler = cameraHandler ?: return

        val clampedNormX = normX.coerceIn(0f, 1f)
        val clampedNormY = normY.coerceIn(0f, 1f)

        // Account for display rotation in landscape (ROTATION_90 vs ROTATION_270)
        val displayRot = getDisplayRotation()
        val (u, v) = if (displayRot == Surface.ROTATION_270) {
            Pair(1.0f - clampedNormX, 1.0f - clampedNormY)
        } else {
            Pair(clampedNormX, clampedNormY)
        }

        // Active sensor array dimensions
        val wa = activeArray.width().toFloat()
        val ha = activeArray.height().toFloat()
        val sensorAspect = wa / ha

        // Video stream aspect ratio
        val vRes = _state.value.videoResolution
        val resW = maxOf(vRes.width, vRes.height).toFloat()
        val resH = minOf(vRes.width, vRes.height).toFloat()
        val videoAspect = resW / resH

        // Aspect-ratio crop mapping from video viewport to sensor active array
        val (xBase, yBase) = when {
            videoAspect > sensorAspect -> {
                // Video is wider than sensor: full width used, top/bottom cropped
                val hVisible = wa / videoAspect
                val yOffset = (ha - hVisible) / 2.0f
                Pair(u * wa, yOffset + v * hVisible)
            }
            videoAspect < sensorAspect -> {
                // Video is narrower than sensor: full height used, left/right cropped
                val wVisible = ha * videoAspect
                val xOffset = (wa - wVisible) / 2.0f
                Pair(xOffset + u * wVisible, v * ha)
            }
            else -> {
                Pair(u * wa, v * ha)
            }
        }

        // Zoom scale transformation (CONTROL_ZOOM_RATIO scales around active array center)
        val zoomRatio = _state.value.zoomRatio
        val targetHardwareZoom = if (spec.isLogicalMultiCamera) {
            zoomRatio.coerceIn(spec.zoomRatioRange.lower, spec.zoomRatioRange.upper)
        } else if (spec.isUltraWide) {
            (zoomRatio / 0.5f).coerceIn(spec.zoomRatioRange.lower, spec.zoomRatioRange.upper)
        } else {
            zoomRatio.coerceIn(spec.zoomRatioRange.lower, spec.zoomRatioRange.upper)
        }.coerceAtLeast(1.0f)

        val cx = wa / 2.0f
        val cy = ha / 2.0f
        val sensorX = cx + (xBase - cx) / targetHardwareZoom
        val sensorY = cy + (yBase - cy) / targetHardwareZoom

        val sensorXInt = sensorX.roundToInt().coerceIn(0, activeArray.width())
        val sensorYInt = sensorY.roundToInt().coerceIn(0, activeArray.height())

        val halfArea = (FOCUS_AREA_SIZE / (2 * targetHardwareZoom)).roundToInt().coerceAtLeast(40)
        val left = max(0, sensorXInt - halfArea)
        val top = max(0, sensorYInt - halfArea)
        val right = min(activeArray.width(), sensorXInt + halfArea)
        val bottom = min(activeArray.height(), sensorYInt + halfArea)

        val meteringRect = MeteringRectangle(Rect(left, top, right, bottom), MeteringRectangle.METERING_WEIGHT_MAX)
        activeTapMeteringRect = meteringRect

        _state.update {
            it.copy(
                tapToFocusPoint = Pair(clampedNormX, clampedNormY),
                focusMode = FocusMode.AUTO_CONTINUOUS
            )
        }

        try {
            builder.set(CaptureRequest.CONTROL_AF_REGIONS, arrayOf(meteringRect))
            builder.set(CaptureRequest.CONTROL_AE_REGIONS, arrayOf(meteringRect))
            builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_AUTO)
            builder.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_START)
            builder.set(CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER, CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER_START)

            session.capture(builder.build(), null, handler)

            // Reset triggers to IDLE
            builder.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_IDLE)
            builder.set(CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER, CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER_IDLE)
            session.setRepeatingRequest(builder.build(), captureCallback, handler)
            Log.d(TAG, "Triggered tap-to-focus at sensor ($sensorXInt, $sensorYInt), zoom=$targetHardwareZoom")
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
        pendingWbKelvin.set(kelvin)
        pendingWbTint.set(tint)
        persistState()

        if (!isWbCheckScheduled) {
            isWbCheckScheduled = true
            cameraHandler?.post {
                isWbCheckScheduled = false
                applyHardwareParametersRamping(force = false)
            }
        }
    }

    fun toggleAutoWb() {
        _state.update {
            it.copy(isManualWb = !it.isManualWb)
        }
        persistState()
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
        hlgGlRenderer?.setTargetFps(fps)
        persistState()
        createAndSetRepeatingRequest()
    }

    /**
     * User Action: Color Profile (Rec.709 8-bit, Pixel-Log, HLG10 HDR 10-bit)
     *
     * mcpro24fps unified architecture: updates GLSL shader uniform uColorMode instantly
     * with ZERO CameraCaptureSession restart and ZERO SurfaceView recreation!
     */
    fun setColorProfile(profile: ColorProfile) {
        val status = when (profile) {
            ColorProfile.HLG_10BIT -> HdrPipelineStatus.CURVE_HLG10
            ColorProfile.HDR10_PLUS -> HdrPipelineStatus.HDR10_PLUS
            else -> HdrPipelineStatus.NONE
        }

        _state.update { it.copy(colorProfile = profile, hdrPipelineStatus = status) }

        // Instantly switch shader color mode on the running GL thread
        hlgGlRenderer?.setColorMode(profile)
        persistState()

        // Update sensor tone curves on the fly without stopping repeating stream
        createAndSetRepeatingRequest()
    }

    /**
     * User Action: Stabilization (OFF / OIS / OIS+EIS)
     */
    fun setStabilizationMode(mode: StabilizationMode) {
        _state.update {
            it.copy(
                stabilizationMode = mode,
                oisEnabed = mode != StabilizationMode.OFF,
                eisEnabled = mode == StabilizationMode.OIS_EIS
            )
        }
        persistState()
        createAndSetRepeatingRequest()
    }

    fun cycleStabilizationMode() {
        val nextMode = when (_state.value.stabilizationMode) {
            StabilizationMode.OFF -> StabilizationMode.OIS
            StabilizationMode.OIS -> StabilizationMode.OIS_EIS
            StabilizationMode.OIS_EIS -> StabilizationMode.OFF
        }
        setStabilizationMode(nextMode)
    }

    fun toggleOis() {
        cycleStabilizationMode()
    }

    fun toggleEis() {
        cycleStabilizationMode()
    }

    /**
     * User Action: Cinema ISP Mode (Clean vs Stock)
     */
    fun toggleIspMode() {
        val nextMode = if (_state.value.ispMode == IspMode.CLEAN) IspMode.STOCK else IspMode.CLEAN
        _state.update { it.copy(ispMode = nextMode) }
        persistState()
        createAndSetRepeatingRequest()
    }

    /**
     * User Action: Intra-Frame Encoding Mode (ALL-I vs Long GOP)
     */
    fun toggleIntraFrameMode() {
        val nextMode = if (_state.value.intraFrameMode == IntraFrameMode.ALL_INTRA) IntraFrameMode.GOP_LONG else IntraFrameMode.ALL_INTRA
        val badge = updateChromaBadgeString(_state.value.encoderChromaBadge, nextMode)
        _state.update { it.copy(intraFrameMode = nextMode, encoderChromaBadge = badge) }
        persistState()
    }

    fun setIntraFrameMode(mode: IntraFrameMode) {
        val badge = updateChromaBadgeString(_state.value.encoderChromaBadge, mode)
        _state.update { it.copy(intraFrameMode = mode, encoderChromaBadge = badge) }
        persistState()
    }

    /**
     * User Action: 48kHz WAV PCM Sidecar Recording Toggle
     */
    fun toggleRecordWavSidecar() {
        _state.update { it.copy(recordWavSidecar = !it.recordWavSidecar) }
        persistState()
    }

    fun setEncoderChromaBadge(badge: String) {
        _state.update { it.copy(encoderChromaBadge = badge) }
    }

    private fun updateChromaBadgeString(current: String, mode: IntraFrameMode): String {
        val base = if (current.contains("4:2:2")) "10B 4:2:2" else "10B 4:2:0"
        return if (mode == IntraFrameMode.ALL_INTRA) "$base ALL-I" else "$base GOP"
    }

    /**
     * User Action: Video Resolution & Bitrate
     */
    fun setVideoResolution(res: CustomResolution) {
        _state.update { it.copy(videoResolution = res) }
        persistState()
        startCaptureSession()
    }

    fun setVideoCodec(codec: VideoCodec) {
        _state.update { it.copy(videoCodec = codec) }
        persistState()
    }

    fun setBitrateMbps(mbps: Int) {
        _state.update { it.copy(targetBitrateMbps = mbps.coerceIn(20, 280)) }
        persistState()
    }

    fun setRecordingFormat(format: RecordingFormat) {
        _state.update { it.copy(recordingFormat = format) }
        startCaptureSession()
    }

    // Monitoring Overlays & Cinema Controls toggles
    fun toggleHistogram() {
        _state.update { it.copy(showHistogram = !it.showHistogram) }
        persistState()
    }
    fun toggleFocusPeaking() {
        _state.update { it.copy(showFocusPeaking = !it.showFocusPeaking) }
        persistState()
    }
    fun setPeakingColor(color: PeakingColor) {
        _state.update { it.copy(peakingColor = color) }
        persistState()
    }
    fun setPeakingSensitivity(sens: Float) {
        _state.update { it.copy(peakingSensitivity = sens.coerceIn(0.1f, 1.0f)) }
        persistState()
    }
    fun toggleZebras() {
        _state.update { it.copy(showZebras = !it.showZebras) }
        persistState()
    }
    fun setZebraThreshold(ire: Int) {
        _state.update { it.copy(zebraIreThreshold = ire.coerceIn(50, 100)) }
        persistState()
    }
    fun toggleLutPreview() {
        _state.update { it.copy(showLutPreview = !it.showLutPreview) }
        persistState()
    }
    fun setFrameGuide(guide: FrameGuide) {
        _state.update { it.copy(frameGuide = guide) }
        persistState()
    }
    fun toggleGrid() {
        _state.update { it.copy(showGrid = !it.showGrid) }
        persistState()
    }
    fun toggleHorizonLeveler() {
        _state.update { it.copy(showHorizonLeveler = !it.showHorizonLeveler) }
        persistState()
    }
    fun toggleCleanViewfinder() = _state.update { it.copy(isCleanViewfinder = !it.isCleanViewfinder) }

    fun toggleTorch() {
        val nextTorch = !_state.value.isTorchOn
        _state.update { it.copy(isTorchOn = nextTorch) }
        createAndSetRepeatingRequest()
    }

    fun setTorchLevel(level: Float) {
        _state.update { it.copy(torchLevel = level.coerceIn(0.1f, 1.0f), isTorchOn = true) }
        createAndSetRepeatingRequest()
    }

    fun setEvCompensation(ev: Float) {
        val clamped = (Math.round(ev * 10f) / 10f).coerceIn(-2.0f, 2.0f)
        if (_state.value.evCompensation == clamped) return
        _state.update { it.copy(evCompensation = clamped) }

        val handler = cameraHandler
        if (handler != null) {
            val nowMs = SystemClock.uptimeMillis()
            val elapsed = nowMs - lastEvRequestTimeMs
            if (elapsed >= 30L) {
                handler.removeCallbacks(pendingEvApplyRunnable)
                isEvApplyScheduled = false
                lastEvRequestTimeMs = nowMs
                handler.post { createAndSetRepeatingRequest() }
            } else if (!isEvApplyScheduled) {
                isEvApplyScheduled = true
                handler.postDelayed(pendingEvApplyRunnable, (30L - elapsed).coerceAtLeast(1L))
            }
            handler.removeCallbacks(persistEvRunnable)
            handler.postDelayed(persistEvRunnable, 300L)
        } else {
            persistState()
            createAndSetRepeatingRequest()
        }
    }

    fun commitEvCompensation(ev: Float) {
        val clamped = (Math.round(ev * 10f) / 10f).coerceIn(-2.0f, 2.0f)
        _state.update { it.copy(evCompensation = clamped) }
        val handler = cameraHandler
        if (handler != null) {
            handler.removeCallbacks(pendingEvApplyRunnable)
            handler.removeCallbacks(persistEvRunnable)
            isEvApplyScheduled = false
            lastEvRequestTimeMs = SystemClock.uptimeMillis()
            handler.post { createAndSetRepeatingRequest() }
        } else {
            createAndSetRepeatingRequest()
        }
        persistState()
    }

    fun toggleAeAfLock() {
        val nextLock = !_state.value.isAeAfLocked
        _state.update { it.copy(isAeAfLocked = nextLock) }
        createAndSetRepeatingRequest()
    }

    fun setRecordingPaused(paused: Boolean) {
        _state.update { it.copy(isRecordingPaused = paused) }
    }

    fun setLastRecordedClip(uri: String?, thumbnail: Bitmap?) {
        _state.update { it.copy(lastRecordedClipUri = uri, lastRecordedClipThumbnail = thumbnail) }
    }

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
        if (active) lastMetricsUpdateTime = 0L
        hlgGlRenderer?.setRecordingActive(active)
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
        val now = SystemClock.uptimeMillis()
        val throttleMs = if (_state.value.isRecording) 83L else 66L // 12Hz while recording, ~15Hz in standby
        if (now - lastAudioUpdateTime < throttleMs) {
            return
        }
        lastAudioUpdateTime = now
        _state.update {
            it.copy(audioPeakDbL = peakL, audioPeakDbR = peakR)
        }
    }

    fun updateRecordingMetrics(seconds: Long, bytes: Long, droppedFrames: Int) {
        val now = SystemClock.uptimeMillis()
        if (seconds == _state.value.recordingTimeSeconds && now - lastMetricsUpdateTime < 1000L) {
            return // 1Hz UI timer & byte readout throttle
        }
        lastMetricsUpdateTime = now
        _state.update {
            it.copy(
                recordingTimeSeconds = seconds,
                recordedBytes = bytes,
                droppedFramesCount = droppedFrames
            )
        }
    }

    fun updateHistogramData(data: HistogramData) {
        // Calculate scene luminance and highlight clipping from lumaBins
        var totalWeight = 0f
        var weightedLuma = 0f
        var highClipWeight = 0f
        for (i in 0 until 32) {
            val count = data.lumaBins[i]
            totalWeight += count
            weightedLuma += count * (i / 31.0f)
            if (i >= 30) {
                highClipWeight += count
            }
        }

        val activeRange = getActiveSensorIsoRange()
        var ndNeeded = false

        if (totalWeight > 0f) {
            val avgLuma = weightedLuma / totalWeight
            val highlightRatio = highClipWeight / totalWeight

            // ND Needed: at minimum ISO and highlights still clipping
            val isAtMinIso = currentContinuousIso <= (activeRange.lower + 15f)
            if (isAtMinIso && highlightRatio > 0.04f && avgLuma > 0.45f) {
                ndNeeded = true
            }

            // Native Tensor G4 Hardware AE handles Auto-ISO; keep software glide state cleared
            latestEvError = 0f
            autoIsoSustainedFrames = 0
        }

        val now = SystemClock.uptimeMillis()
        val currentIsoReadout = if (_state.value.isAutoIso || !_state.value.isManualExposure) {
            _state.value.iso
        } else {
            currentEffectiveIso.roundToInt()
        }
        if (now - lastHistogramUpdateTime < 83L) { // 12Hz throttle (1000ms / 12 ~= 83ms)
            pendingHistogramData = data
            if (!isHistogramDispatchScheduled) {
                isHistogramDispatchScheduled = true
                scope.launch(Dispatchers.Default) {
                    kotlinx.coroutines.delay(83L)
                    isHistogramDispatchScheduled = false
                    val latest = pendingHistogramData
                    if (latest != null) {
                        _state.update {
                            it.copy(
                                histogramData = latest,
                                isNdNeeded = ndNeeded,
                                iso = currentIsoReadout
                            )
                        }
                    }
                }
            }
            return
        }
        lastHistogramUpdateTime = now
        scope.launch(Dispatchers.Default) {
            _state.update {
                it.copy(
                    histogramData = data,
                    isNdNeeded = ndNeeded,
                    iso = currentIsoReadout
                )
            }
        }
    }

    fun flipCamera() {
        if (_state.value.isRecording) {
            Log.w(TAG, "Cannot flip camera while recording")
            return
        }

        val isCurrentlyFront = _state.value.isFrontCamera
        val targetIsFront = !isCurrentlyFront
        val targetSpec = if (isCurrentlyFront) {
            mainCameraSpec ?: _probedSensors.firstOrNull { it.lensFacing == CameraCharacteristics.LENS_FACING_BACK }
        } else {
            frontCameraSpec ?: _probedSensors.firstOrNull { it.lensFacing == CameraCharacteristics.LENS_FACING_FRONT }
        } ?: return

        val (minZ, maxZ) = zoomBoundsForSpec(targetSpec)
        val clampedZoom = 1.0f.coerceIn(minZ, maxZ)
        val probedResolutions = buildSupportedResolutions(targetSpec)
        val currentRes = _state.value.videoResolution
        val matchedRes = probedResolutions.find { it.width == currentRes.width && it.height == currentRes.height }
            ?: probedResolutions.firstOrNull { it.width == 1920 && it.height == 1080 }
            ?: probedResolutions.firstOrNull()
            ?: currentRes
        val safeFps = clampTargetFpsForSpec(targetSpec, _state.value.targetFps)
        val safeIso = _state.value.iso.coerceIn(targetSpec.isoRange.lower, targetSpec.isoRange.upper)

        _state.update {
            it.copy(
                isFrontCamera = targetIsFront,
                selectedLens = if (targetIsFront) LensSelection.FRONT else LensSelection.WIDE_1X,
                availableLenses = if (targetIsFront) {
                    listOf(LensSelection.FRONT)
                } else {
                    listOf(LensSelection.ULTRAWIDE, LensSelection.WIDE_1X, LensSelection.CROP_2X)
                },
                currentSensorSpec = targetSpec,
                supportedResolutions = probedResolutions,
                videoResolution = matchedRes,
                targetFps = safeFps,
                iso = safeIso,
                minZoomRatio = minZ,
                maxZoomRatio = maxZ,
                zoomRatio = clampedZoom,
                focusMode = FocusMode.AUTO_CONTINUOUS,
                isTorchOn = false,
                isAeAfLocked = if (targetIsFront) false else it.isAeAfLocked
            )
        }

        pendingZoomRatio.set(clampedZoom)
        lastAppliedZoomRatio = clampedZoom
        smoothedZoomRatio = clampedZoom
        pendingEffectiveIso.set(safeIso.toFloat())
        currentEffectiveIso = safeIso.toFloat()

        hlgGlRenderer?.setCameraOrientation(targetIsFront, targetSpec.sensorOrientation)
        hlgGlRenderer?.setTargetFps(safeFps)
        openCamera(targetSpec.cameraId)
    }

    // --- Pro Cinema Suite Controls ---

    fun toggleProToolsDrawer() {
        _state.update { it.copy(isProToolsDrawerOpen = !it.isProToolsDrawerOpen) }
    }

    fun setProToolsDrawerOpen(open: Boolean) {
        _state.update { it.copy(isProToolsDrawerOpen = open) }
    }

    fun setLutPreset(preset: LutPreset) {
        _state.update { it.copy(lutPreset = preset, showLutPreview = preset != LutPreset.OFF) }
        val mode = when (preset) {
            LutPreset.OFF -> 0
            LutPreset.REC709_NATURAL -> 1
            LutPreset.KODAK_2383 -> 2
            LutPreset.CINEMA_TEAL -> 3
        }
        hlgGlRenderer?.setLutPreset(mode)
        persistState()
    }

    fun setLutBakeEnabled(enabled: Boolean) {
        _state.update { it.copy(isLutBakeEnabled = enabled) }
        hlgGlRenderer?.setLutBakeEnabled(enabled)
        persistState()
    }

    fun toggleFalseColor() {
        val next = !_state.value.showFalseColor
        _state.update { it.copy(showFalseColor = next) }
        hlgGlRenderer?.setFalseColor(next)
        persistState()
    }

    fun toggleWaveform() {
        _state.update { it.copy(showWaveform = !it.showWaveform) }
        persistState()
    }

    fun setAnamorphicDeSqueeze(squeeze: AnamorphicDeSqueeze) {
        _state.update { it.copy(anamorphicDeSqueeze = squeeze) }
        hlgGlRenderer?.setAnamorphicScale(squeeze.factor)
        persistState()
    }

    fun setDeband(enabled: Boolean) {
        _state.update { it.copy(isDebandEnabled = enabled) }
        hlgGlRenderer?.setDeband(enabled)
        persistState()
    }

    fun toggleDeband() {
        setDeband(!_state.value.isDebandEnabled)
    }

    fun setGyroflowLoggingEnabled(enabled: Boolean) {
        _state.update { it.copy(isGyroflowLoggingEnabled = enabled) }
        persistState()
    }

    fun setOrientationLockMode(mode: OrientationLockMode) {
        _state.update { it.copy(orientationLockMode = mode) }
        persistState()
    }

    fun cycleOrientationLockMode() {
        val nextMode = when (_state.value.orientationLockMode) {
            OrientationLockMode.AUTO -> OrientationLockMode.LOCK_LANDSCAPE
            OrientationLockMode.LOCK_LANDSCAPE -> OrientationLockMode.LOCK_PORTRAIT
            OrientationLockMode.LOCK_PORTRAIT -> OrientationLockMode.AUTO
        }
        setOrientationLockMode(nextMode)
    }

    // --- Programmable A -> B Cinema Rack Focus ---

    fun setRackPointA() {
        val currentDist = _state.value.focusDistance
        _state.update { it.copy(rackFocusPointA = currentDist) }
    }

    fun setRackPointB() {
        val currentDist = _state.value.focusDistance
        _state.update { it.copy(rackFocusPointB = currentDist) }
    }

    fun setRackPullDurationMs(durationMs: Long) {
        _state.update { it.copy(rackPullDurationMs = durationMs) }
    }

    fun pullRackFocus(fromAtoB: Boolean = true) {
        val pA = _state.value.rackFocusPointA ?: 0.0f
        val pB = _state.value.rackFocusPointB ?: 5.0f
        val startDist = if (fromAtoB) pA else pB
        val endDist = if (fromAtoB) pB else pA
        val durationMs = _state.value.rackPullDurationMs

        rackFocusJob?.cancel()
        rackFocusJob = scope.launch(Dispatchers.Default) {
            _state.update { it.copy(isRackPulling = true) }
            val startTime = System.currentTimeMillis()
            val totalSteps = (durationMs / 16L).coerceAtLeast(10L)
            for (step in 0..totalSteps) {
                val elapsed = System.currentTimeMillis() - startTime
                val progress = (elapsed.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f)
                val easedProgress = (1.0f - kotlin.math.cos(progress * Math.PI.toFloat())) * 0.5f
                val currentDistance = startDist + (endDist - startDist) * easedProgress
                setFocusDistance(currentDistance)
                if (progress >= 1.0f) break
                kotlinx.coroutines.delay(16L)
            }
            _state.update { it.copy(isRackPulling = false) }
        }
    }

    fun getCameraCharacteristics(cameraId: String): CameraCharacteristics {
        return cameraManager.getCameraCharacteristics(cameraId)
    }
}
