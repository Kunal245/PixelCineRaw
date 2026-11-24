package com.kunal.pixelcineraw.camera

import android.graphics.Bitmap

/**
 * Immutable UI and camera engine state emitted to Jetpack Compose.
 */
data class CinemaState(
    // Sensor & Lens
    val selectedLens: LensSelection = LensSelection.WIDE_1X,
    val availableLenses: List<LensSelection> = listOf(LensSelection.ULTRAWIDE, LensSelection.WIDE_1X, LensSelection.CROP_2X),
    val isFrontCamera: Boolean = false,
    val currentSensorSpec: CameraSensorSpec? = null,
    val zoomRatio: Float = 1.0f,
    val minZoomRatio: Float = 0.5f,
    val maxZoomRatio: Float = 8.0f,
    val isZoomSliderVisible: Boolean = false,
    val isTransitioningLens: Boolean = false,
    val transitionScale: Float = 1.0f,
    val transitionBitmap: Bitmap? = null,

    // Exposure (Shutter & ISO & EV)
    val isManualExposure: Boolean = true,
    val shutterUnit: ShutterUnit = ShutterUnit.ANGLE,
    val shutterAngle: Float = 180f, // 180 degree rule default
    val shutterSpeedNs: Long = 20_000_000L, // 1/50s for 50Hz indoor anti-banding default
    val iso: Int = 100,
    val isAutoIso: Boolean = false,
    val autoIsoCap: Int = 3200,
    val isNdNeeded: Boolean = false,
    val antiBandingMode: AntiBandingMode = AntiBandingMode.HZ_50,
    val evCompensation: Float = 0.0f, // -2.0 to +2.0 EV bias
    val isAeAfLocked: Boolean = false,

    // Focus
    val focusMode: FocusMode = FocusMode.AUTO_CONTINUOUS,
    val focusDistance: Float = 0.0f, // 0.0 = infinity, max = minimumFocusDistance (macro)
    val tapToFocusPoint: Pair<Float, Float>? = null, // Normalized [0, 1] x, y

    // White Balance
    val isManualWb: Boolean = false,
    val wbKelvin: Int = 5600,
    val wbTint: Int = 0,

    // Video Specs & Hardware Lights
    val isTorchOn: Boolean = false,
    val torchLevel: Float = 1.0f,
    val targetFps: Int = 24, // 24, 30, 60
    val videoResolution: CustomResolution = CustomResolution.UHD_4K,
    val supportedResolutions: List<CustomResolution> = listOf(
        CustomResolution.UHD_4K,
        CustomResolution.DCI_4K,
        CustomResolution.QHD_1440P,
        CustomResolution.FHD_1080P
    ),
    val videoCodec: VideoCodec = VideoCodec.HEVC_10BIT,
    val targetBitrateMbps: Int = 180, // Default 180 Mbps 10-bit GOP for Tensor G4
    val ispMode: IspMode = IspMode.CLEAN,
    val intraFrameMode: IntraFrameMode = IntraFrameMode.GOP_LONG,
    val encoderChromaBadge: String = "10B 4:2:0 GOP",
    val recordWavSidecar: Boolean = false,
    val colorProfile: ColorProfile = ColorProfile.FLAT_LOG,
    val hdrPipelineStatus: HdrPipelineStatus = HdrPipelineStatus.NONE,
    val recordingFormat: RecordingFormat = RecordingFormat.VIDEO_PRO,
    val stabilizationMode: StabilizationMode = StabilizationMode.OIS,
    val oisEnabed: Boolean = true,
    val eisEnabled: Boolean = false,

    // Recording Runtime
    val isRecording: Boolean = false,
    val isRecordingPaused: Boolean = false,
    val recordingTimeSeconds: Long = 0L,
    val recordedBytes: Long = 0L,
    val droppedFramesCount: Int = 0,
    val currentSavedFilePath: String? = null,
    val lastRecordedClipUri: String? = null,
    val lastRecordedClipThumbnail: Bitmap? = null,

    // Monitoring Overlays & Clean Viewfinder Mode
    val isCleanViewfinder: Boolean = false,
    val isDebandEnabled: Boolean = false,
    val showHistogram: Boolean = true,
    val histogramData: HistogramData = HistogramData(),
    val showFocusPeaking: Boolean = false,
    val peakingColor: PeakingColor = PeakingColor.GREEN,
    val peakingSensitivity: Float = 0.6f, // 0.1 to 1.0
    val showZebras: Boolean = false,
    val zebraIreThreshold: Int = 95, // 70 to 100%
    val showLutPreview: Boolean = true,
    val lutPreset: LutPreset = LutPreset.REC709_NATURAL,
    val isLutBakeEnabled: Boolean = false,
    val customLutFileName: String? = null,
    val showFalseColor: Boolean = false,
    val showWaveform: Boolean = false,
    val frameGuide: FrameGuide = FrameGuide.NONE,
    val showGrid: Boolean = true,
    val showHorizonLeveler: Boolean = false,
    val anamorphicDeSqueeze: AnamorphicDeSqueeze = AnamorphicDeSqueeze.OFF,

    // Pro Cinema Suite Drawer & Advanced Controls
    val isProToolsDrawerOpen: Boolean = false,
    val rackFocusPointA: Float? = null,
    val rackFocusPointB: Float? = null,
    val isRackPulling: Boolean = false,
    val rackPullDurationMs: Long = 2000L,
    val isGyroflowLoggingEnabled: Boolean = true,
    val deviceRotationDegrees: Int = 90,
    val orientationLockMode: OrientationLockMode = OrientationLockMode.AUTO,

    // System Telemetry
    val batteryPercent: Int = 100,
    val remainingStorageGb: Float = 64.0f,
    val remainingRecordingMinutes: Int = 120,
    val thermalStatus: ThermalStatus = ThermalStatus.NOMINAL,
    val measuredFps: Float = 24.0f,
    val audioPeakDbL: Float = -60f,
    val audioPeakDbR: Float = -60f,
    val audioGain: Float = 1.0f,
    val isExternalMicConnected: Boolean = false
)

/**
 * Customizable sensor resolution probed from the hardware Camera2 capabilities.
 */
data class CustomResolution(
    val label: String,
    val width: Int,
    val height: Int,
    val aspectLabel: String,
    val aspectRatio: Float
) {
    companion object {
        fun fromSize(width: Int, height: Int): CustomResolution {
            val ratio = width.toFloat() / height.toFloat()
            val aspectLabel = when {
                Math.abs(ratio - (16f / 9f)) < 0.05f -> "16:9"
                Math.abs(ratio - (4f / 3f)) < 0.05f -> "4:3"
                Math.abs(ratio - (1.90f)) < 0.05f || Math.abs(ratio - (256f / 135f)) < 0.05f -> "1.90:1 (DCI)"
                Math.abs(ratio - (2.39f)) < 0.08f -> "2.39:1 (Scope)"
                Math.abs(ratio - 1.0f) < 0.05f -> "1:1 (Square)"
                Math.abs(ratio - (3f / 2f)) < 0.05f -> "3:2"
                Math.abs(ratio - (20f / 9f)) < 0.08f -> "20:9 (Full)"
                else -> String.format(java.util.Locale.US, "%.2f:1", ratio)
            }

            val friendlyName = when {
                width == 3840 && height == 2160 -> "4K UHD"
                width == 4096 && height == 2160 -> "4K DCI"
                width == 2560 && height == 1440 -> "2.5K QHD"
                width == 1920 && height == 1080 -> "1080p FHD"
                width == 1280 && height == 720 -> "720p HD"
                width >= 3000 && Math.abs(ratio - (4f / 3f)) < 0.05f -> "4:3 Open Gate"
                else -> "${width}×${height}"
            }

            return CustomResolution(
                label = "$friendlyName ($aspectLabel)",
                width = width,
                height = height,
                aspectLabel = aspectLabel,
                aspectRatio = ratio
            )
        }

        val UHD_4K = fromSize(3840, 2160)
        val DCI_4K = fromSize(4096, 2160)
        val QHD_1440P = fromSize(2560, 1440)
        val FHD_1080P = fromSize(1920, 1080)
        val HD_720P = fromSize(1280, 720)
    }
}

enum class FrameGuide(val label: String, val aspectRatio: Float?) {
    NONE("Off", null),
    RATIO_2_39("2.39:1 CinemaScope", 2.39f),
    RATIO_16_9("16:9 Broadcast", 16f / 9f),
    RATIO_4_3("4:3 Open Gate", 4f / 3f),
    RATIO_1_1("1:1 Square", 1.0f)
}

enum class ThermalStatus(val label: String, val isWarning: Boolean) {
    NOMINAL("NORMAL", false),
    LIGHT("WARM", false),
    MODERATE("THROTTLING RISK", true),
    SEVERE("CRITICAL HEAT", true),
    EMERGENCY("EMERGENCY SHUTDOWN", true)
}

data class HistogramData(
    val redBins: FloatArray = FloatArray(32),
    val greenBins: FloatArray = FloatArray(32),
    val blueBins: FloatArray = FloatArray(32),
    val lumaBins: FloatArray = FloatArray(32)
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as HistogramData
        return redBins.contentEquals(other.redBins) &&
                greenBins.contentEquals(other.greenBins) &&
                blueBins.contentEquals(other.blueBins) &&
                lumaBins.contentEquals(other.lumaBins)
    }

    override fun hashCode(): Int {
        var result = redBins.contentHashCode()
        result = 31 * result + greenBins.contentHashCode()
        result = 31 * result + blueBins.contentHashCode()
        result = 31 * result + lumaBins.contentHashCode()
        return result
    }
}

/**
 * Active HDR hardware and tonemapping pipeline indicator.
 */
enum class HdrPipelineStatus(val label: String) {
    NONE(""),
    HW_HLG10("HW-HLG10"),
    CURVE_HLG10("10B-CURVE-HLG"),
    HDR10_PLUS("HDR10+ PQ")
}
